package com.yucareux.tellus.worldgen.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.ChunkPos;

/** Durable prepared-batch journal; recovery is explicit and native-queue bound. */
final class TellusNativeBatchJournal {
    private static final String MAGIC = "tellus-native-batch-journal-v1";
    private static final String DIRECTORY = "tellus/persistence-journal";
    private static final String SUFFIX = ".prepared";
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    enum State { PREPARED, QUARANTINED, COMMITTED }
    record Entry(ChunkPos position, CompoundTag previous, CompoundTag proposed) {
        Entry {
            position = Objects.requireNonNull(position, "position");
            previous = Objects.requireNonNull(previous, "previous").copy();
            proposed = Objects.requireNonNull(proposed, "proposed").copy();
        }
        @Override public CompoundTag previous() { return previous.copy(); }
        @Override public CompoundTag proposed() { return proposed.copy(); }
    }
    record Record(Path path, State state, TellusNativeChunkPersistence.Binding binding, List<Entry> entries) {
        Record {
            path = Objects.requireNonNull(path, "path"); state = Objects.requireNonNull(state, "state");
            binding = Objects.requireNonNull(binding, "binding"); entries = List.copyOf(entries);
        }
    }

    private TellusNativeBatchJournal() { }

    static Path prepare(TellusNativeChunkPersistence.Binding binding, List<Entry> entries) throws IOException {
        Path target = path(binding, UUID.randomUUID().toString());
        write(target, State.PREPARED, binding, entries);
        return target;
    }

    static void markCommitted(Record record) throws IOException {
        write(record.path(), State.COMMITTED, record.binding(), record.entries());
    }

    static void markQuarantined(Record record) throws IOException {
        write(record.path(), State.QUARANTINED, record.binding(), record.entries());
    }

    static void delete(Record record) throws IOException {
        Files.deleteIfExists(record.path());
    }

    static List<Record> pending(TellusNativeChunkPersistence.Binding binding) throws IOException {
        Path directory = binding.worldRoot().resolve(DIRECTORY);
        if (!Files.isDirectory(directory)) return List.of();
        List<Record> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path path : stream) {
                Record record = read(path);
                if (binding.worldRoot().equals(record.binding().worldRoot())
                    && !binding.dimension().equals(record.binding().dimension())) continue;
                if (!sameBinding(binding, record.binding()))
                    throw new IOException("native batch journal binding differs: " + path.getFileName());
                result.add(record);
            }
        }
        result.sort(java.util.Comparator.comparing(record -> record.path().getFileName().toString()));
        return List.copyOf(result);
    }

    static Record read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("native batch journal size outside budget");
        String text = new String(bytes, StandardCharsets.UTF_8);
        String normalized = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        String[] lines = normalized.split("\\n", -1);
        if (lines.length < 2 || !MAGIC.equals(lines[0])) throw new IOException("native batch journal magic differs");
        String checksumLine = lines[lines.length - 1];
        if (!checksumLine.startsWith("sha256=")) throw new IOException("native batch journal checksum missing");
        String body = normalized.substring(0, normalized.length() - checksumLine.length());
        String expectedChecksum = checksumLine.substring("sha256=".length());
        String actualChecksum = sha256(body.getBytes(StandardCharsets.UTF_8));
        if (!actualChecksum.equals(expectedChecksum))
            throw new IOException("native batch journal checksum differs: expected=" + expectedChecksum + ", actual=" + actualChecksum);
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i]; int split = line.indexOf('=');
            if (split <= 0 || fields.put(line.substring(0, split), line.substring(split + 1)) != null)
                throw new IOException("duplicate/malformed native batch journal field");
        }
        State state;
        try { state = State.valueOf(require(fields, "state")); }
        catch (IllegalArgumentException error) { throw new IOException("native batch journal state differs", error); }
        int count = integer(fields, "count");
        if (count <= 0 || count > MAX_ENTRIES) throw new IOException("native batch journal entry count outside budget");
        TellusNativeChunkPersistence.Binding binding = new TellusNativeChunkPersistence.Binding(
            Path.of(decode(require(fields, "worldRoot"))), decode(require(fields, "dimension")),
            Long.parseLong(require(fields, "seed")), decode(require(fields, "generatorType")),
            decode(require(fields, "generatorProfile")), integer(fields, "minY"), integer(fields, "height"), integer(fields, "seaLevel"));
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int x = integer(fields, "entry." + i + ".x"); int z = integer(fields, "entry." + i + ".z");
            try {
                entries.add(new Entry(new ChunkPos(x, z), TagParser.parseTag(decode(require(fields, "entry." + i + ".previous"))),
                    TagParser.parseTag(decode(require(fields, "entry." + i + ".proposed")))));
            } catch (Exception error) { throw new IOException("native batch journal NBT differs", error); }
        }
        return new Record(path, state, binding, entries);
    }

    private static void write(Path path, State state, TellusNativeChunkPersistence.Binding binding, List<Entry> entries) throws IOException {
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) throw new IOException("native batch journal entry count outside budget");
        Path directory = path.getParent(); Files.createDirectories(directory);
        StringBuilder body = new StringBuilder();
        body.append(MAGIC).append('\n').append("state=").append(state).append('\n');
        body.append("worldRoot=").append(encode(binding.worldRoot().toString())).append('\n');
        body.append("dimension=").append(encode(binding.dimension())).append('\n');
        body.append("seed=").append(binding.seed()).append('\n');
        body.append("generatorType=").append(encode(binding.generatorType())).append('\n');
        body.append("generatorProfile=").append(encode(binding.generatorProfile())).append('\n');
        body.append("minY=").append(binding.minY()).append('\n').append("height=").append(binding.height()).append('\n');
        body.append("seaLevel=").append(binding.seaLevel()).append('\n').append("count=").append(entries.size()).append('\n');
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            body.append("entry.").append(i).append(".x=").append(entry.position().x).append('\n');
            body.append("entry.").append(i).append(".z=").append(entry.position().z).append('\n');
            body.append("entry.").append(i).append(".previous=").append(encode(entry.previous().toString())).append('\n');
            body.append("entry.").append(i).append(".proposed=").append(encode(entry.proposed().toString())).append('\n');
        }
        String bodyText = body.toString();
        body.append("sha256=").append(sha256(bodyText.getBytes(StandardCharsets.UTF_8))).append('\n');
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("native batch journal exceeds byte budget");
        Path temporary = directory.resolve(path.getFileName() + ".tmp." + UUID.randomUUID());
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.deleteIfExists(temporary); throw new IOException("atomic journal publication is required", unsupported);
        }
        try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) { directoryChannel.force(true); }
        catch (IOException ignored) { /* file force remains the durability boundary */ }
    }

    private static Path path(TellusNativeChunkPersistence.Binding binding, String id) {
        return binding.worldRoot().resolve(DIRECTORY).resolve(id + SUFFIX);
    }
    private static boolean sameBinding(TellusNativeChunkPersistence.Binding left, TellusNativeChunkPersistence.Binding right) {
        return left.worldRoot().equals(right.worldRoot()) && left.dimension().equals(right.dimension()) && left.seed() == right.seed()
            && left.generatorType().equals(right.generatorType()) && left.generatorProfile().equals(right.generatorProfile())
            && left.minY() == right.minY() && left.height() == right.height() && left.seaLevel() == right.seaLevel();
    }
    private static String encode(String value) { return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) throws IOException {
        try { return new String(DECODER.decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException error) { throw new IOException("invalid journal base64", error); }
    }
    private static String require(java.util.Map<String, String> fields, String key) throws IOException {
        String value = fields.get(key); if (value == null || value.isBlank()) throw new IOException("missing journal field " + key); return value;
    }
    private static int integer(java.util.Map<String, String> fields, String key) throws IOException {
        try { return Integer.parseInt(require(fields, key)); } catch (NumberFormatException error) { throw new IOException("invalid journal integer " + key, error); }
    }
    private static String sha256(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 unavailable", error); }
    }
}
