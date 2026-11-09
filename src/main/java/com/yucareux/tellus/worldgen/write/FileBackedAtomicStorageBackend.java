package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit, opt-in file backend for the generic canonical storage boundary.
 *
 * <p>This is plain-file transaction storage, not Minecraft world persistence. A
 * v2 record is length-bounded, checksummed and parsed strictly. Publication is
 * accepted only through {@code ATOMIC_MOVE}; there is deliberately no copy or
 * replace fallback. Data is forced through the file channel before publication.
 * A directory force is attempted after publication where the platform exposes a
 * directory channel; platforms that do not expose that operation retain the
 * atomic-file result but report the durability boundary in diagnostics.</p>
 *
 * <p>Each key is protected by a JVM lock and a sibling lock file, so separate
 * backend instances (and cooperating processes) serialize commit/reload/recovery.
 * Temporary names contain a random ownership token. Recovery removes only names
 * produced by this backend after acquiring the corresponding key lock; unrelated
 * {@code .tmp} files and active locked temporary files remain untouched.</p>
 */
public final class FileBackedAtomicStorageBackend<M> implements AtomicStorageBackend<M> {
   static final int MAX_RECORD_BYTES = 4 * 1024 * 1024;
   static final int MAX_ITEMS = 100_000;
   static final int MAX_VALUE_BYTES = 512 * 1024;
   static final int MAX_LINE_CHARS = 1_000_000;
   private static final String MAGIC = "tellus-storage-v2";
   private static final String CHECKSUM_PREFIX = "checksum=";
   private static final String LOCK_DIRECTORY = ".locks";
   private static final Pattern TEMP_PATTERN = Pattern.compile(
      "^([0-9a-f]{64})\\.txn\\.tmp\\.([0-9a-fA-F-]{36})$");
   private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
   private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
   private static final ReentrantLock[] JVM_LOCK_STRIPES = createLockStripes();

   private final Path directory;
   private final Function<? super M, String> encode;
   private final Function<String, ? extends M> decode;

   public FileBackedAtomicStorageBackend(Path directory,
      Function<? super M, String> encode, Function<String, ? extends M> decode) {
      this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
      this.encode = Objects.requireNonNull(encode, "encode");
      this.decode = Objects.requireNonNull(decode, "decode");
   }

   public Path directory() { return directory; }

   /**
    * Removes only backend-owned, tokenized temporary records after per-key
    * locking. A held key lock causes the candidate to be retained.
    */
   public int recover() {
      if (!Files.isDirectory(directory)) return 0;
      int removed = 0;
      try (var paths = Files.list(directory)) {
         for (Path path : paths.toList()) {
            Matcher matcher = TEMP_PATTERN.matcher(path.getFileName().toString());
            if (!matcher.matches()) continue;
            String hash = matcher.group(1);
            try {
               if (withHashLock(hash, () -> Files.deleteIfExists(path))) removed++;
            } catch (IOException | RuntimeException ignored) {
               // Active/foreign locks and filesystem races are fail-closed.
            }
         }
      } catch (IOException ignored) {
         // Recovery is best effort and never touches committed records.
      }
      return removed;
   }

   @Override
   public StorageBackendResult<M> commit(StorageTransaction<M> transaction) {
      Objects.requireNonNull(transaction, "transaction");
      if (!transaction.commitEligible()) {
         return StorageBackendResult.noTransaction(StorageBackendStatus.REJECTED,
            "transaction is not commit eligible");
      }
      final byte[] candidate;
      try {
         candidate = serialize(transaction);
      } catch (Exception exception) {
         return StorageBackendResult.noTransaction(StorageBackendStatus.FAILED,
            "file commit failed: " + detail(exception));
      }
      String hash = hash(transaction.key().stableKey());
      try {
         return withHashLock(hash, () -> {
            Files.createDirectories(directory);
            Path destination = destination(hash);
            if (Files.exists(destination)) {
               ParsedRecord<M> previous = read(destination);
               if (!previous.transaction().key().equals(transaction.key())) {
                  return StorageBackendResult.noTransaction(StorageBackendStatus.FAILED,
                     "storage key hash collision or corrupted key");
               }
               if (Arrays.equals(previous.body(), bodyWithoutChecksum(candidate))) {
                  return StorageBackendResult.alreadyCommitted(previous.transaction(),
                     "idempotent commit already exists");
               }
               if (previous.transaction().idempotencyKey().equals(transaction.idempotencyKey())) {
                  return StorageBackendResult.noTransaction(StorageBackendStatus.IDEMPOTENCY_CONFLICT,
                     "same idempotency key has different transaction content");
               }
               return StorageBackendResult.noTransaction(StorageBackendStatus.IDEMPOTENCY_CONFLICT,
                  "canonical key already has another idempotency key");
            }
            Path temporary = temporary(destination);
            try {
               writeAndForce(temporary, candidate);
               atomicPublish(temporary, destination);
            } catch (Exception failure) {
               // Leave a tokenized temporary for a later locked recovery pass.
               return StorageBackendResult.noTransaction(StorageBackendStatus.FAILED,
                  "file commit failed: " + detail(failure));
            }
            String directoryDiagnostic = forceDirectory(directory);
            if (directoryDiagnostic == null) {
               return StorageBackendResult.committed(transaction, "committed atomically to file backend");
            }
            return StorageBackendResult.committed(transaction,
               "committed atomically; directory force unavailable: " + directoryDiagnostic);
         });
      } catch (Exception exception) {
         return StorageBackendResult.noTransaction(StorageBackendStatus.FAILED,
            "file commit failed: " + detail(exception));
      }
   }

   @Override
   public StorageBackendResult<M> reload(CanonicalPersistenceKey key, String idempotencyKey) {
      Objects.requireNonNull(key, "key");
      requireText(idempotencyKey, "idempotencyKey");
      String hash = hash(key.stableKey());
      Path destination = destination(hash);
      if (!Files.exists(destination)) {
         return StorageBackendResult.noTransaction(StorageBackendStatus.NOT_FOUND, "no committed transaction");
      }
      try {
         return withHashLock(hash, () -> {
            if (!Files.exists(destination)) {
               return StorageBackendResult.noTransaction(StorageBackendStatus.NOT_FOUND, "no committed transaction");
            }
            ParsedRecord<M> parsed = read(destination);
            StorageTransaction<M> transaction = parsed.transaction();
            if (!transaction.key().equals(key)) {
               return StorageBackendResult.noTransaction(StorageBackendStatus.FAILED,
                  "stored canonical key does not match requested key");
            }
            if (!transaction.idempotencyKey().equals(idempotencyKey)) {
               return StorageBackendResult.noTransaction(StorageBackendStatus.IDEMPOTENCY_CONFLICT,
                  "reload idempotency key does not match committed record");
            }
            return StorageBackendResult.committed(transaction, "reloaded committed transaction");
         });
      } catch (Exception exception) {
         return StorageBackendResult.noTransaction(StorageBackendStatus.FAILED,
            "file reload failed: " + detail(exception));
      }
   }

   private ParsedRecord<M> read(Path path) throws IOException {
      long size = Files.size(path);
      if (size > MAX_RECORD_BYTES) throw new IOException("record exceeds byte limit");
      byte[] bytes = readBounded(path);
      String text = decodeUtf8(bytes);
      String[] lines = text.split("\\n", -1);
      if (lines.length < 4 || !lines[lines.length - 1].isEmpty()) {
         throw new IOException("record must end with one newline");
      }
      for (String line : lines) {
         if (line.length() > MAX_LINE_CHARS || line.indexOf('\r') >= 0) {
            throw new IOException("record line exceeds limit or has invalid newline");
         }
      }
      int checksumIndex = lines.length - 2;
      if (checksumIndex < 12) throw new IOException("record is missing required fields");
      if (!lines[checksumIndex].startsWith(CHECKSUM_PREFIX)
         || !isHexDigest(lines[checksumIndex].substring(CHECKSUM_PREFIX.length()))) {
         throw new IOException("missing or invalid checksum");
      }
      byte[] body = Arrays.copyOf(bytes, bytes.length - (lines[checksumIndex].getBytes(StandardCharsets.UTF_8).length + 1));
      String actual = hashBytes(body);
      String supplied = lines[checksumIndex].substring(CHECKSUM_PREFIX.length());
      if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) {
         throw new IOException("record checksum mismatch");
      }
      if (!MAGIC.equals(lines[0])) throw new IOException("invalid transaction header");
      String geometry = decode(lines[1], "geometry");
      GeneratorOwner owner = parseOwner(lines[2]);
      int face = parseInt(lines[3], "face");
      long blockX = parseLong(lines[4], "blockX");
      long blockZ = parseLong(lines[5], "blockZ");
      long chunkX = parseLong(lines[6], "chunkX");
      long chunkZ = parseLong(lines[7], "chunkZ");
      CanonicalPersistenceKey key = new CanonicalPersistenceKey(geometry, owner, face, blockX, blockZ, chunkX, chunkZ);
      String idempotency = decode(lines[8], "idempotency");
      int index = 9;
      if (index >= checksumIndex || !lines[index].startsWith("writes=")) throw new IOException("missing writes");
      int writeCount = parseCount(lines[index++], "writes");
      if (writeCount > MAX_ITEMS || checksumIndex - index < writeCount + 2) throw new IOException("invalid write count");
      List<M> writes = new ArrayList<>(writeCount);
      for (int i = 0; i < writeCount; i++) writes.add(decode.apply(decode(lines[index++], "write")));
      if (index >= checksumIndex || !lines[index].startsWith("diagnostics=")) throw new IOException("missing diagnostics");
      int diagnosticCount = parseCount(lines[index++], "diagnostics");
      if (diagnosticCount > MAX_ITEMS || checksumIndex - index < diagnosticCount + 1) throw new IOException("invalid diagnostics");
      List<String> diagnostics = new ArrayList<>(diagnosticCount);
      for (int i = 0; i < diagnosticCount; i++) diagnostics.add(decode(lines[index++], "diagnostic"));
      if (index >= checksumIndex || !lines[index].startsWith("seam=")) throw new IOException("missing seam");
      String seam = lines[index++].substring("seam=".length());
      if (index != checksumIndex) throw new IOException("trailing record content");
      Optional<SeamProjectionIdentity> seamProjection = seam.equals("none") ? Optional.empty() : Optional.of(readSeam(seam));
      StorageTransaction<M> transaction = new StorageTransaction<>(StorageTransactionStatus.PREPARED,
         key, idempotency, writes, diagnostics, seamProjection);
      return new ParsedRecord(transaction, body);
   }

   private byte[] serialize(StorageTransaction<M> transaction) {
      if (transaction.writes().size() > MAX_ITEMS || transaction.diagnostics().size() > MAX_ITEMS) {
         throw new IllegalArgumentException("transaction item count exceeds limit");
      }
      StringBuilder output = new StringBuilder(256);
      appendLine(output, MAGIC);
      CanonicalPersistenceKey key = transaction.key();
      appendLine(output, encoded(key.geometryKey(), "geometry"));
      appendLine(output, key.owner().name());
      appendLine(output, Integer.toString(key.face()));
      appendLine(output, Long.toString(key.blockX()));
      appendLine(output, Long.toString(key.blockZ()));
      appendLine(output, Long.toString(key.chunkX()));
      appendLine(output, Long.toString(key.chunkZ()));
      appendLine(output, encoded(transaction.idempotencyKey(), "idempotency"));
      appendLine(output, "writes=" + transaction.writes().size());
      for (M write : transaction.writes()) {
         appendLine(output, encoded(Objects.requireNonNull(encode.apply(write), "encoded write"), "write"));
      }
      appendLine(output, "diagnostics=" + transaction.diagnostics().size());
      for (String diagnostic : transaction.diagnostics()) appendLine(output, encoded(diagnostic, "diagnostic"));
      StringBuilder seamLine = new StringBuilder("seam=");
      if (transaction.seamProjection().isEmpty()) seamLine.append("none");
      else {
         SeamProjectionIdentity seam = transaction.seamProjection().orElseThrow();
         seamLine.append(encoded(seam.projectionId(), "projection")).append(':').append(seam.proxyXBits()).append(':').append(seam.proxyZBits())
            .append(':').append(seam.targetFace()).append(':').append(seam.targetChunkX()).append(':').append(seam.targetChunkZ());
      }
      appendLine(output, seamLine.toString());
      byte[] body = utf8(output.toString());
      String complete = output.append(CHECKSUM_PREFIX).append(hashBytes(body)).append('\n').toString();
      byte[] bytes = utf8(complete);
      if (bytes.length > MAX_RECORD_BYTES) throw new IllegalArgumentException("serialized record exceeds byte limit");
      return bytes;
   }

   private static void appendLine(StringBuilder output, String line) {
      Objects.requireNonNull(line, "line");
      if (line.length() > MAX_LINE_CHARS || (long) output.length() + line.length() + 1 > MAX_RECORD_BYTES) {
         throw new IllegalArgumentException("serialized record exceeds byte limit");
      }
      output.append(line).append('\n');
   }

   private static String encoded(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.length() > MAX_VALUE_BYTES) throw new IllegalArgumentException(name + " exceeds byte limit");
      byte[] raw = utf8(value);
      if (raw.length > MAX_VALUE_BYTES) throw new IllegalArgumentException(name + " exceeds byte limit");
      String encoded = ENCODER.encodeToString(raw);
      if (encoded.length() > MAX_LINE_CHARS) throw new IllegalArgumentException(name + " line exceeds limit");
      return encoded;
   }

   private static SeamProjectionIdentity readSeam(String value) {
      String[] fields = value.split(":", -1);
      if (fields.length != 6) throw new IllegalArgumentException("invalid seam");
      return new SeamProjectionIdentity(decode(fields[0], "projection"), parseLong(fields[1], "proxyXBits"), parseLong(fields[2], "proxyZBits"),
         parseInt(fields[3], "targetFace"), parseLong(fields[4], "targetChunkX"), parseLong(fields[5], "targetChunkZ"));
   }

   private Path destination(String hash) { return directory.resolve(hash + ".txn"); }
   private static Path temporary(Path destination) { return destination.resolveSibling(destination.getFileName() + ".tmp." + UUID.randomUUID()); }
   private Path lockPath(String hash) { return directory.resolve(LOCK_DIRECTORY).resolve(hash + ".lock"); }

   private void writeAndForce(Path path, byte[] bytes) throws IOException {
      try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
         ByteBuffer buffer = ByteBuffer.wrap(bytes);
         while (buffer.hasRemaining()) channel.write(buffer);
         channel.force(true);
      }
   }

   private static void atomicPublish(Path source, Path destination) throws IOException {
      try {
         Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
         throw new IOException("atomic move is not supported", unsupported);
      }
   }

   private static String forceDirectory(Path directory) {
      try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
         channel.force(true);
         return null;
      } catch (UnsupportedOperationException | IOException failure) {
         return detail(failure);
      }
   }

   private <T> T withHashLock(String hash, IoSupplier<T> action) throws IOException {
      Files.createDirectories(directory);
      Files.createDirectories(directory.resolve(LOCK_DIRECTORY));
      ReentrantLock jvmLock = JVM_LOCK_STRIPES[Math.floorMod(hash.hashCode(), JVM_LOCK_STRIPES.length)];
      jvmLock.lock();
      try (FileChannel channel = FileChannel.open(lockPath(hash), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
         FileLock fileLock;
         try {
            fileLock = channel.lock();
         } catch (OverlappingFileLockException overlap) {
            throw new IOException("key is locked by an active backend instance", overlap);
         }
         try (fileLock) {
            return action.get();
         }
      } finally {
         jvmLock.unlock();
      }
   }

   private static byte[] bodyWithoutChecksum(byte[] complete) {
      String text = decodeUtf8(complete);
      int marker = text.lastIndexOf(CHECKSUM_PREFIX);
      if (marker < 0) throw new IllegalArgumentException("missing checksum");
      return Arrays.copyOf(complete, marker == 0 ? 0 : utf8(text.substring(0, marker)).length);
   }

   private static byte[] readBounded(Path path) throws IOException {
      ByteBuffer buffer = ByteBuffer.allocate(MAX_RECORD_BYTES + 1);
      try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
         while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0 || read == 0) break;
         }
      }
      if (buffer.hasRemaining()) {
         int length = buffer.position();
         return Arrays.copyOf(buffer.array(), length);
      }
      throw new IOException("record exceeds byte limit");
   }

   private static String decode(String value, String name) {
      if (value.length() > MAX_LINE_CHARS) throw new IllegalArgumentException(name + " line exceeds limit");
      try {
         byte[] bytes = DECODER.decode(value);
         if (bytes.length > MAX_VALUE_BYTES) throw new IllegalArgumentException(name + " exceeds byte limit");
         return decodeUtf8(bytes);
      } catch (IllegalArgumentException exception) {
         throw new IllegalArgumentException("invalid " + name, exception);
      }
   }

   private static String decodeUtf8(byte[] bytes) {
      try {
         return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException exception) {
         throw new IllegalArgumentException("invalid UTF-8", exception);
      }
   }

   private static byte[] utf8(String value) {
      Objects.requireNonNull(value, "value");
      if (value.length() > MAX_RECORD_BYTES) throw new IllegalArgumentException("UTF-8 input exceeds byte limit");
      try {
         ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value));
         if (encoded.remaining() > MAX_RECORD_BYTES) throw new IllegalArgumentException("UTF-8 output exceeds byte limit");
         byte[] bytes = new byte[encoded.remaining()];
         encoded.get(bytes);
         return bytes;
      } catch (CharacterCodingException exception) {
         throw new IllegalArgumentException("invalid UTF-16 input", exception);
      }
   }
   private static String hashBytes(byte[] bytes) { return hashBytesHex(bytes); }
   private static String hashBytesHex(byte[] bytes) {
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
         StringBuilder result = new StringBuilder(64);
         for (byte item : digest) result.append(String.format("%02x", item));
         return result.toString();
      } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
   }
   private static String hash(String value) { return hashBytes(utf8(value)); }
   private static boolean isHexDigest(String value) { return value.length() == 64 && value.matches("[0-9a-f]{64}"); }
   private static int parseCount(String line, String name) {
      String prefix = name + "=";
      if (!line.startsWith(prefix)) throw new IllegalArgumentException("missing " + name);
      int result = parseInt(line.substring(prefix.length()), name);
      if (result < 0 || result > MAX_ITEMS) throw new IllegalArgumentException(name + " count exceeds limit");
      return result;
   }
   private static int parseInt(String value, String name) {
      try { return Integer.parseInt(value); } catch (RuntimeException exception) { throw new IllegalArgumentException("invalid " + name, exception); }
   }
   private static long parseLong(String value, String name) {
      try { return Long.parseLong(value); } catch (RuntimeException exception) { throw new IllegalArgumentException("invalid " + name, exception); }
   }
   private static GeneratorOwner parseOwner(String value) {
      try { return GeneratorOwner.valueOf(value); } catch (RuntimeException exception) { throw new IllegalArgumentException("invalid owner", exception); }
   }
   private static ReentrantLock[] createLockStripes() {
      ReentrantLock[] locks = new ReentrantLock[256];
      for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
      return locks;
   }

   private static String detail(Exception exception) {
      return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
   }
   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }

   @FunctionalInterface
   private interface IoSupplier<T> { T get() throws IOException; }
   private record ParsedRecord<T>(StorageTransaction<T> transaction, byte[] body) { }
}
