package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedAtomicStorageBackendTest {
   @TempDir Path directory;

   @Test
   void commitReloadAndRepeatedCommitAreIdempotent() {
      var backend = backend();
      var transaction = transaction("op-1", List.of("stone", "water"));
      assertEquals(StorageBackendStatus.COMMITTED, backend.commit(transaction).status());
      var repeated = backend.commit(transaction);
      assertEquals(StorageBackendStatus.ALREADY_COMMITTED, repeated.status());
      assertEquals(transaction.writes(), repeated.transaction().orElseThrow().writes());
      var reloaded = backend.reload(transaction.key(), "op-1");
      assertEquals(StorageBackendStatus.COMMITTED, reloaded.status());
      assertEquals(transaction, reloaded.transaction().orElseThrow());
   }

   @Test
   void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
      var backend = backend();
      var first = transaction("same-op", List.of("stone"));
      assertEquals(StorageBackendStatus.COMMITTED, backend.commit(first).status());
      var changed = transaction("same-op", List.of("dirt"));
      assertEquals(StorageBackendStatus.IDEMPOTENCY_CONFLICT, backend.commit(changed).status());
      assertEquals(StorageBackendStatus.COMMITTED, backend.reload(first.key(), "same-op").status());
   }

   @Test
   void conflictingIdempotencyAndMissingRecordsAreExplicit() {
      var backend = backend();
      var first = transaction("op-1", List.of("stone"));
      backend.commit(first);
      var conflictingKey = new StorageTransaction<>(StorageTransactionStatus.PREPARED, first.key(), "op-2",
         List.of("dirt"), List.of("fixture"), Optional.empty());
      assertEquals(StorageBackendStatus.IDEMPOTENCY_CONFLICT,
         backend.commit(conflictingKey).status());
      assertEquals(StorageBackendStatus.IDEMPOTENCY_CONFLICT,
         backend.reload(first.key(), "op-2").status());
      assertEquals(StorageBackendStatus.NOT_FOUND,
         backend.reload(key("missing"), "op-1").status());
   }

   @Test
   void malformedContentReturnsFailureAndDoesNotBecomeNotFound() throws Exception {
      var backend = backend();
      var transaction = transaction("op-1", List.of("stone"));
      backend.commit(transaction);
      Path record = record();
      Files.writeString(record, "corrupt", StandardCharsets.UTF_8);
      assertEquals(StorageBackendStatus.FAILED, backend.reload(transaction.key(), "op-1").status());
   }

   @Test
   void trailingContentIsRejected() throws Exception {
      var backend = backend();
      var transaction = transaction("trailing", List.of("stone"));
      backend.commit(transaction);
      Path record = record();
      Files.writeString(record, Files.readString(record, StandardCharsets.UTF_8) + "trailing\n", StandardCharsets.UTF_8);
      assertEquals(StorageBackendStatus.FAILED, backend.reload(transaction.key(), "trailing").status());
   }

   @Test
   void checksumMutationIsRejected() throws Exception {
      var backend = backend();
      var transaction = transaction("checksum", List.of("stone"));
      backend.commit(transaction);
      Path record = record();
      String text = Files.readString(record, StandardCharsets.UTF_8);
      int marker = text.lastIndexOf("checksum=");
      char replacement = text.charAt(marker + "checksum=".length()) == '0' ? '1' : '0';
      text = text.substring(0, marker + "checksum=".length()) + replacement
         + text.substring(marker + "checksum=".length() + 1);
      Files.writeString(record, text, StandardCharsets.UTF_8);
      assertEquals(StorageBackendStatus.FAILED, backend.reload(transaction.key(), "checksum").status());
   }

   @Test
   void oversizedSerializedTransactionIsRejectedBeforeDirectoryCreation() throws Exception {
      var backend = backend();
      String oversized = "x".repeat(FileBackedAtomicStorageBackend.MAX_VALUE_BYTES + 1);
      assertEquals(StorageBackendStatus.FAILED,
         backend.commit(transaction("oversized", List.of(oversized))).status());
      assertTrue(directoryEmpty());
   }

   @Test
   void oversizedCommittedRecordIsRejectedOnRead() throws Exception {
      var backend = backend();
      var transaction = transaction("oversized-read", List.of("stone"));
      backend.commit(transaction);
      Files.write(record(), new byte[FileBackedAtomicStorageBackend.MAX_RECORD_BYTES + 1]);
      assertEquals(StorageBackendStatus.FAILED, backend.reload(transaction.key(), "oversized-read").status());
   }

   @Test
   void staleRecoveryKeepsUnrelatedTempAndDeletesOnlyOwnedTemp() throws Exception {
      var backend = backend();
      var transaction = transaction("recovery", List.of("stone"));
      backend.commit(transaction);
      Path record = record();
      String hash = record.getFileName().toString().substring(0, 64);
      Path unrelated = directory.resolve("unrelated.tmp");
      Path owned = directory.resolve(hash + ".txn.tmp." + UUID.randomUUID());
      Files.writeString(unrelated, "keep", StandardCharsets.UTF_8);
      Files.writeString(owned, "partial", StandardCharsets.UTF_8);
      assertEquals(1, backend.recover());
      assertTrue(Files.exists(unrelated));
      assertFalse(Files.exists(owned));
   }

   @Test
   void lockedActiveTempIsRetainedUntilLockIsReleased() throws Exception {
      var backend = backend();
      var transaction = transaction("active-temp", List.of("stone"));
      backend.commit(transaction);
      Path record = record();
      String hash = record.getFileName().toString().substring(0, 64);
      Path active = directory.resolve(hash + ".txn.tmp." + UUID.randomUUID());
      Files.writeString(active, "active", StandardCharsets.UTF_8);
      Path lock = directory.resolve(".locks").resolve(hash + ".lock");
      try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
           var held = channel.lock()) {
         assertEquals(0, backend.recover());
         assertTrue(Files.exists(active));
      }
      assertEquals(1, backend.recover());
      assertFalse(Files.exists(active));
   }

   @Test
   void separateBackendInstancesSerializeConcurrentCommitDeterministically() throws Exception {
      var firstBackend = backend();
      var secondBackend = backend();
      var transaction = transaction("concurrent", List.of("stone", "water"));
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
         Future<StorageBackendStatus> first = executor.submit(() -> firstBackend.commit(transaction).status());
         Future<StorageBackendStatus> second = executor.submit(() -> secondBackend.commit(transaction).status());
         Set<StorageBackendStatus> statuses = Set.of(first.get(), second.get());
         assertEquals(Set.of(StorageBackendStatus.COMMITTED, StorageBackendStatus.ALREADY_COMMITTED), statuses);
      } finally {
         executor.shutdownNow();
      }
      assertEquals(StorageBackendStatus.COMMITTED, firstBackend.reload(transaction.key(), "concurrent").status());
   }

   @Test
   void nonCommittableTransactionsAreRejectedWithoutCreatingFiles() throws Exception {
      var transaction = new StorageTransaction<String>(StorageTransactionStatus.NO_COMMIT_DISABLED,
         key("disabled"), "op", List.of(), List.of("disabled"), Optional.empty());
      assertEquals(StorageBackendStatus.REJECTED, backend().commit(transaction).status());
      assertTrue(directoryEmpty());
   }

   @Test
   void encoderFailureLeavesNoPublishedOrTemporaryRecord() throws Exception {
      var backend = new FileBackedAtomicStorageBackend<String>(directory,
         value -> { throw new IllegalStateException("fixture encoder"); }, value -> value);
      assertEquals(StorageBackendStatus.FAILED, backend.commit(transaction("encoder", List.of("stone"))).status());
      assertTrue(directoryEmpty());
   }

   @Test
   void unpairedSurrogateInGeometryFailsBeforePublication() throws Exception {
      var invalidKey = new CanonicalPersistenceKey("geometry-" + (char) 0xD800,
         GeneratorOwner.EARTH_PROJECTED, -1, 1, 17, Long.MIN_VALUE, Long.MIN_VALUE);
      var transaction = new StorageTransaction<>(StorageTransactionStatus.PREPARED, invalidKey,
         "invalid-geometry", List.of("stone"), List.of("fixture"), Optional.empty());
      assertEquals(StorageBackendStatus.FAILED, backend().commit(transaction).status());
      assertTrue(directoryEmpty());
   }

   @Test
   void unpairedSurrogatesInEncodedWritesFailBeforePublication() throws Exception {
      for (char surrogate : new char[] { (char) 0xD800, (char) 0xDC00 }) {
         var transaction = transaction("invalid-write", List.of("stone-" + surrogate));
         assertEquals(StorageBackendStatus.FAILED, backend().commit(transaction).status());
         assertTrue(directoryEmpty());
      }
   }

   private boolean directoryEmpty() throws Exception {
      try (var paths = Files.list(directory)) { return paths.findAny().isEmpty(); }
   }

   private FileBackedAtomicStorageBackend<String> backend() {
      return new FileBackedAtomicStorageBackend<>(directory, value -> value, value -> value);
   }

   private Path record() throws Exception {
      try (var paths = Files.list(directory)) {
         return paths.filter(path -> path.getFileName().toString().endsWith(".txn")).findFirst().orElseThrow();
      }
   }

   private StorageTransaction<String> transaction(String operation, List<String> writes) {
      return new StorageTransaction<>(StorageTransactionStatus.PREPARED, key("stored"), operation,
         writes, List.of("fixture"), Optional.empty());
   }

   private CanonicalPersistenceKey key(String block) {
      return new CanonicalPersistenceKey("geometry-v1", GeneratorOwner.EARTH_PROJECTED,
         -1, block.hashCode(), 17, Long.MIN_VALUE, Long.MIN_VALUE);
   }
}
