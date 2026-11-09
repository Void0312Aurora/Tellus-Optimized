package com.yucareux.tellus.worldgen.persistence;

import com.yucareux.tellus.worldgen.CubeNetChunkGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Explicit native save-queue seam for an admitted, isolated world. It uses the
 * existing ChunkMap worker only. It neither requests/generates chunks nor claims
 * loaded-chunk mutation, tick/light lifecycle or crash-atomic region recovery.
 * Caller must quiesce competing engine saves and restore its loaded mutations
 * separately after a rolled-back disk operation.
 */
public final class TellusNativeChunkPersistence {
    public static final String PROBE_ROOT_PROPERTY = "tellus.persistence.probeRoot";
    public static final int MAX_HEIGHT = 4096;
    public enum Status { COMMITTED, ROLLED_BACK, REJECTED, QUARANTINED }
    public enum Point { BEFORE_SUBMIT, AFTER_SUBMIT, AFTER_READBACK }
    @FunctionalInterface public interface FaultInjector {
        FaultInjector NONE = point -> { };
        void at(Point point);
    }

    public record Binding(Path worldRoot, String dimension, long seed, String generatorType,
                          String generatorProfile, int minY, int height, int seaLevel) {
        public Binding {
            worldRoot = Objects.requireNonNull(worldRoot, "worldRoot").toAbsolutePath().normalize();
            dimension = text(dimension, "dimension"); generatorType = text(generatorType, "generatorType");
            generatorProfile = text(generatorProfile, "generatorProfile");
            if (height < 16 || height > MAX_HEIGHT || Math.floorMod(height, 16) != 0 || Math.floorMod(minY, 16) != 0)
                throw new IllegalArgumentException("native persistence height outside aligned budget");
            Math.addExact(minY, height);
        }
    }

    public record CommitResult(Status status, Optional<CompoundTag> previous,
                               Optional<CompoundTag> persisted, List<String> diagnostics) {
        public CommitResult {
            Objects.requireNonNull(status, "status"); previous = copy(previous); persisted = copy(persisted);
            diagnostics = List.copyOf(diagnostics);
        }
        @Override public Optional<CompoundTag> previous() { return copy(previous); }
        @Override public Optional<CompoundTag> persisted() { return copy(persisted); }
    }

    /** Narrow injection boundary used by contract tests, not evidence of a live world. */
    interface Store {
        Binding currentBinding();
        boolean mainThread();
        boolean canonical(ChunkPos pos);
        Optional<CompoundTag> read(ChunkPos pos);
        void submit(ChunkPos pos, CompoundTag tag);
        void flush();
        default CompoundTag snapshot(ChunkAccess chunk) { throw new UnsupportedOperationException("no native target"); }
    }

    private record Key(Path root, String dimension, int x, int z) { }
    private static final ConcurrentHashMap<Key, Boolean> ACTIVE = new ConcurrentHashMap<>();
    // Runtime quarantine deliberately has no public reset. Restart/recovery is an external gate.
    private static final ConcurrentHashMap<Key, String> QUARANTINE = new ConcurrentHashMap<>();
    private final Binding binding;
    private final Store store;

    private TellusNativeChunkPersistence(Binding binding, Store store) {
        this.binding = Objects.requireNonNull(binding); this.store = Objects.requireNonNull(store);
        current();
    }

    public static TellusNativeChunkPersistence open(boolean enabled, ServerLevel level) {
        if (!enabled) throw new IllegalStateException("native persistence opt-in is disabled");
        String root = System.getProperty(PROBE_ROOT_PROPERTY);
        if (root == null || root.isBlank()) throw new IllegalStateException("exact isolated world root property is required");
        return open(true, level, Path.of(root));
    }

    /** The admitted root must equal the actual world root, not merely be its parent. */
    public static TellusNativeChunkPersistence open(boolean enabled, ServerLevel level, Path admittedWorldRoot) {
        if (!enabled) throw new IllegalStateException("native persistence opt-in is disabled");
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) throw new IllegalStateException("native persistence requires server main thread");
        var nativeStore = new LevelStore(level, realPath(admittedWorldRoot));
        return new TellusNativeChunkPersistence(nativeStore.currentBinding(), nativeStore);
    }

    static TellusNativeChunkPersistence forStore(Binding binding, Store store) {
        return new TellusNativeChunkPersistence(binding, store);
    }

    public Binding binding() { return binding; }
    public boolean quarantined(ChunkPos pos) { return QUARANTINE.containsKey(key(pos)); }
    public static String digest(CompoundTag tag) { return TellusCanonicalNbt.digest(tag); }
    public static String digest(CompoundTag tag, Set<String> excludedRootKeys) { return TellusCanonicalNbt.digest(tag, excludedRootKeys); }
    public static boolean sameNBT(CompoundTag left, CompoundTag right, Set<String> excludedRootKeys) {
        return TellusCanonicalNbt.same(left, right, excludedRootKeys);
    }

    /** Reads the existing native disk/worker queue before any demand getChunk; never generates. */
    public Optional<CompoundTag> readExistingNBT(ChunkPos pos) {
        admit(pos);
        if (ACTIVE.containsKey(key(pos))) throw new IllegalStateException("native persistence operation is already active");
        store.flush();
        Optional<CompoundTag> value = Objects.requireNonNull(store.read(pos), "native read result");
        current();
        return value.map(tag -> checkedCopy(tag, pos));
    }

    /** Uses actual native ChunkSerializer.write on an exact loaded FULL chunk of the bound level. */
    public CompoundTag snapshot(ChunkAccess chunk) {
        Objects.requireNonNull(chunk, "chunk");
        admit(chunk.getPos());
        if (ACTIVE.containsKey(key(chunk.getPos()))) throw new IllegalStateException("native persistence operation is already active");
        if (chunk.getMinBuildHeight() != binding.minY() || chunk.getHeight() != binding.height())
            throw new IllegalArgumentException("chunk build bounds differ from bound level");
        CompoundTag result = checkedCopy(store.snapshot(chunk), chunk.getPos());
        current();
        return result;
    }

    /**
     * Publishes one complete native tag and proves exact queue readback. A durable
     * existing FULL tag is mandatory. Rollback writes that complete original tag
     * through the same worker and verifies it without volatile-key exclusions.
     * BEFORE/AFTER faults are contract seams, not a process-crash transaction.
     */
    public CommitResult commit(ChunkPos pos, CompoundTag proposed, FaultInjector faults) {
        Objects.requireNonNull(faults, "faults");
        try { admit(pos); }
        catch (RuntimeException failure) { return rejected(pos, failure); }
        Key key = key(pos);
        if (ACTIVE.putIfAbsent(key, Boolean.TRUE) != null)
            return result(Status.REJECTED, null, null, "native persistence operation is already active");
        try {
            final CompoundTag previous;
            final CompoundTag candidate;
            try {
                candidate = checkedCopy(proposed, pos);
                store.flush();
                Optional<CompoundTag> existing = Objects.requireNonNull(store.read(pos), "native read result");
                current();
                if (existing.isEmpty()) return result(Status.REJECTED, null, null, "existing native FULL disk baseline is required");
                previous = checkedCopy(existing.orElseThrow(), pos);
                if (previous.getInt("DataVersion") != candidate.getInt("DataVersion"))
                    return result(Status.REJECTED, previous, previous, "native DataVersion differs from durable baseline");
                if (!previous.getString("Status").equals(candidate.getString("Status")))
                    return result(Status.REJECTED, previous, previous, "exact native status differs from durable baseline");
            } catch (RuntimeException failure) {
                return result(Status.REJECTED, null, null, "native persistence preflight: " + failure.getClass().getName());
            }
            try {
                faults.at(Point.BEFORE_SUBMIT);
                current();
                store.submit(pos, candidate.copy());
                faults.at(Point.AFTER_SUBMIT);
                current();
                store.flush();
                CompoundTag readback = store.read(pos).orElseThrow(() -> new IllegalStateException("native submitted tag is absent"));
                current();
                checkedCopy(readback, pos);
                if (!sameNBT(candidate, readback, Set.of())) throw new IllegalStateException("native full NBT readback differs");
                faults.at(Point.AFTER_READBACK);
                current();
                return result(Status.COMMITTED, previous, readback, "native full NBT queue readback verified; loaded/crash lifecycle is external");
            } catch (RuntimeException | Error failure) {
                Throwable recoveryFailure = null;
                try {
                    current();
                    // Even BEFORE_SUBMIT faults verify a durable complete baseline, not an assumed no-op.
                    store.submit(pos, previous.copy());
                    store.flush();
                    CompoundTag restored = store.read(pos).orElseThrow(() -> new IllegalStateException("native rollback tag is absent"));
                    current();
                    checkedCopy(restored, pos);
                    if (!sameNBT(previous, restored, Set.of())) throw new IllegalStateException("native rollback full NBT differs");
                } catch (RuntimeException | Error incomplete) { recoveryFailure = incomplete; }
                if (recoveryFailure != null) {
                    QUARANTINE.put(key, recoveryFailure.getClass().getName());
                    var incomplete = new IllegalStateException("native persistence rollback incomplete; chunk quarantined", failure);
                    if (recoveryFailure != failure) incomplete.addSuppressed(recoveryFailure);
                    throw incomplete;
                }
                if (failure instanceof Error fatal) throw fatal;
                return result(Status.ROLLED_BACK, previous, previous,
                    "native complete previous NBT restored: " + failure.getClass().getName());
            }
        } finally { ACTIVE.remove(key); }
    }

    private CommitResult rejected(ChunkPos pos, RuntimeException failure) {
        boolean quarantined = pos != null && quarantined(pos);
        return result(quarantined ? Status.QUARANTINED : Status.REJECTED, null, null,
            "native persistence admission: " + failure.getClass().getName());
    }
    private static CommitResult result(Status status, CompoundTag before, CompoundTag after, String diagnostic) {
        return new CommitResult(status, Optional.ofNullable(before), Optional.ofNullable(after), List.of(diagnostic));
    }
    private static Optional<CompoundTag> copy(Optional<CompoundTag> tag) {
        return Objects.requireNonNull(tag).map(CompoundTag::copy);
    }
    private void current() {
        if (!store.mainThread()) throw new IllegalStateException("native persistence requires server main thread");
        if (!binding.equals(store.currentBinding())) throw new IllegalStateException("world/dimension/seed/generator binding is stale");
    }
    private void admit(ChunkPos pos) {
        Objects.requireNonNull(pos, "pos"); current();
        Math.multiplyExact(pos.x, 16); Math.multiplyExact(pos.z, 16);
        Math.addExact(Math.multiplyExact(pos.x, 16), 15); Math.addExact(Math.multiplyExact(pos.z, 16), 15);
        if (QUARANTINE.containsKey(key(pos))) throw new IllegalStateException("native chunk is quarantined after incomplete recovery");
        if (!store.canonical(pos)) throw new IllegalArgumentException("derived apron/noncanonical chunk is not a persistence owner");
    }
    private Key key(ChunkPos pos) {
        Objects.requireNonNull(pos, "pos");
        return new Key(binding.worldRoot(), binding.dimension(), pos.x, pos.z);
    }
    private CompoundTag checkedCopy(CompoundTag tag, ChunkPos pos) {
        Objects.requireNonNull(tag, "native tag");
        // Bound traversal before recursive native copy; also rejects cycles and malformed oversized inputs.
        digest(tag);
        if (!tag.contains("xPos", 3) || !tag.contains("zPos", 3) || tag.getInt("xPos") != pos.x || tag.getInt("zPos") != pos.z)
            throw new IllegalArgumentException("native NBT chunk coordinates differ");
        if (!tag.contains("Status", 8) || !isFull(tag.getString("Status"))) throw new IllegalArgumentException("native FULL status required");
        for (String compound : List.of("Heightmaps", "structures"))
            if (!tag.contains(compound, 10)) throw new IllegalArgumentException("missing full native compound: " + compound);
        for (String list : List.of("sections", "PostProcessing", "block_ticks", "fluid_ticks", "block_entities"))
            if (!tag.contains(list, 9)) throw new IllegalArgumentException("missing full native list: " + list);
        if (!tag.contains("DataVersion", 3) || !tag.contains("LastUpdate", 4) || !tag.contains("InhabitedTime", 4))
            throw new IllegalArgumentException("missing full native version/time metadata");
        if (!tag.contains("yPos", 3) || tag.getInt("yPos") != Math.floorDiv(binding.minY(), 16))
            throw new IllegalArgumentException("native minimum section coordinate differs from bound world");
        for (String list : List.of("sections", "block_ticks", "fluid_ticks", "block_entities"))
            requireList(tag, list, 10);
        requireList(tag, "PostProcessing", 9);
        for (net.minecraft.nbt.Tag offsets : (net.minecraft.nbt.ListTag)tag.get("PostProcessing"))
            requireElements((net.minecraft.nbt.ListTag)offsets, 2, "PostProcessing offsets");
        var sections = tag.getList("sections", 10);
        if (sections.size() > binding.height() / 16 + 2)
            throw new IllegalArgumentException("native section count exceeds bound world height");
        var seen = new java.util.HashSet<Integer>();
        int first = Math.floorDiv(binding.minY(), 16);
        int lastExclusive = Math.floorDiv(Math.addExact(binding.minY(), binding.height()), 16);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (!section.contains("Y", 1) && !section.contains("Y", 3))
                throw new IllegalArgumentException("native section needs exact byte/int Y");
            int y = section.getInt("Y");
            if (y < first - 1 || y > lastExclusive || !seen.add(y))
                throw new IllegalArgumentException("native section Y is duplicate or outside body/light bounds");
            if ((y < first || y >= lastExclusive) && (section.contains("block_states") || section.contains("biomes")))
                throw new IllegalArgumentException("light boundary section contains body palettes");
            for (String light : List.of("BlockLight", "SkyLight"))
                if (section.contains(light) && (!section.contains(light, 7) || section.getByteArray(light).length != 2048))
                    throw new IllegalArgumentException("native light array must contain 2048 bytes: " + light);
            requirePalette(section, "block_states", 10);
            requirePalette(section, "biomes", 8);
        }
        return tag.copy();
    }
    private static void requireList(CompoundTag root, String key, int elementType) {
        requireElements((net.minecraft.nbt.ListTag)root.get(key), elementType, key);
    }
    private static void requireElements(net.minecraft.nbt.ListTag list, int elementType, String key) {
        int actual = list.getElementType();
        if (actual != elementType && !(actual == 0 && list.isEmpty()))
            throw new IllegalArgumentException("wrong native list element type: " + key);
    }
    private static void requirePalette(CompoundTag section, String key, int elementType) {
        if (!section.contains(key)) return;
        if (!section.contains(key, 10)) throw new IllegalArgumentException("native palette payload must be compound: " + key);
        var payload = section.getCompound(key);
        if (!payload.contains("palette", 9)) throw new IllegalArgumentException("native palette list is missing: " + key);
        var palette = (net.minecraft.nbt.ListTag)payload.get("palette");
        requireElements(palette, elementType, key + " palette");
        if (palette.isEmpty()) throw new IllegalArgumentException("native palette is empty: " + key);
        if (payload.contains("data") && !payload.contains("data", 12))
            throw new IllegalArgumentException("native packed palette data must be long array: " + key);
        for (net.minecraft.nbt.Tag value : palette) {
            if (elementType == 8) {
                if (value.getAsString().isBlank()) throw new IllegalArgumentException("native biome name is blank");
            } else {
                var state = (CompoundTag)value;
                if (!state.contains("Name", 8) || state.getString("Name").isBlank())
                    throw new IllegalArgumentException("native block state requires a string Name");
                if (state.contains("Properties") && !state.contains("Properties", 10))
                    throw new IllegalArgumentException("native block state Properties must be compound");
            }
        }
    }
    private static boolean isFull(String status) { return status.equals("minecraft:full") || status.equals("full"); }
    private static String text(String value, String name) {
        Objects.requireNonNull(value, name); if (value.isBlank()) throw new IllegalArgumentException(name); return value;
    }
    private static Path realPath(Path path) {
        try { return Objects.requireNonNull(path, "admittedWorldRoot").toRealPath(); }
        catch (IOException failure) { throw new IllegalArgumentException("world root must already exist", failure); }
    }

    private static final class LevelStore implements Store {
        private final ServerLevel level;
        private final ChunkGenerator generator;
        private final Path admittedRoot;
        LevelStore(ServerLevel level, Path admittedRoot) {
            this.level = level; this.generator = level.getChunkSource().getGenerator(); this.admittedRoot = admittedRoot;
        }
        @Override public Binding currentBinding() {
            if (!mainThread()) throw new IllegalStateException("native binding requires server main thread");
            if (level.getServer().getLevel(level.dimension()) != level) throw new IllegalStateException("exact active ServerLevel instance changed");
            Path actualRoot = realPath(level.getServer().getWorldPath(LevelResource.ROOT));
            if (!admittedRoot.equals(actualRoot)) throw new IllegalStateException("actual world root differs from admitted isolated root");
            if (generator != level.getChunkSource().getGenerator()) throw new IllegalStateException("exact native generator instance changed");
            var encoded = ChunkGenerator.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, level.registryAccess()), generator)
                .result().orElseThrow(() -> new IllegalStateException("native generator Codec profile unavailable"));
            if (!(encoded instanceof CompoundTag profile)) throw new IllegalStateException("native generator profile must be compound NBT");
            return new Binding(actualRoot, level.dimension().location().toString(), level.getSeed(), generator.getClass().getName(),
                digest(profile), level.getMinBuildHeight(), level.getHeight(), generator.getSeaLevel());
        }
        @Override public boolean mainThread() { return level.getServer().isSameThread(); }
        @Override public boolean canonical(ChunkPos pos) {
            return !(generator instanceof CubeNetChunkGenerator cube)
                || cube.apronProjection(pos).isEmpty() && cube.address(pos).isPresent();
        }
        @Override public Optional<CompoundTag> read(ChunkPos pos) {
            return level.getChunkSource().chunkMap.read(pos).join();
        }
        @Override public void submit(ChunkPos pos, CompoundTag tag) {
            // Statement is compatible with void mc1201 and future-returning mc1211.
            // Native flush plus exact readback detects missing/failed queue publication.
            level.getChunkSource().chunkMap.write(pos, tag);
        }
        @Override public void flush() { level.getChunkSource().chunkMap.flushWorker(); }
        @Override public CompoundTag snapshot(ChunkAccess chunk) {
            if (!(chunk instanceof LevelChunk full) || full.getLevel() != level
                || level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) != full)
                throw new IllegalArgumentException("snapshot requires exact loaded FULL chunk from bound ServerLevel");
            return ChunkSerializer.write(level, chunk);
        }
    }
}
