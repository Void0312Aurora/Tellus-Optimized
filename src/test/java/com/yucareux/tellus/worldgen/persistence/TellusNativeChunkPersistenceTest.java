package com.yucareux.tellus.worldgen.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Injectable queue + actual NBT contracts only; these are not live region/restart tests. */
class TellusNativeChunkPersistenceTest {
    @TempDir Path root;
    private final ChunkPos pos = new ChunkPos(-3, 7);

    @Test void missingNativeDiskDataIsObservedWithoutDemandLoadingOrRegeneration() {
        var store = store(null); var api = api(store);
        assertTrue(api.readExistingNBT(pos).isEmpty());
        assertEquals(List.of("flush", "read"), store.events);
        assertEquals(0, store.submissions);
    }
    @Test void queuedBaselineIsFlushedBeforeReadAndReturnsDefensiveNativeTag() {
        var store = store(fullTag("old")); store.pending = fullTag("queued"); var api = api(store);
        var observed = api.readExistingNBT(pos).orElseThrow();
        assertEquals("queued", observed.getString("sentinel"));
        assertEquals(List.of("flush", "read"), store.events);
        observed.putString("sentinel", "caller mutation");
        assertEquals("queued", store.disk.getString("sentinel"));
    }
    @Test void completeNativeCommitUsesExistingQueueAndExactSemanticReadback() {
        var old = fullTag("old"); var candidate = fullTag("new");
        candidate.getCompound("structures").putString("unknown_future_key", "kept");
        var store = store(old); var api = api(store);
        var result = api.commit(pos, candidate, TellusNativeChunkPersistence.FaultInjector.NONE);
        assertEquals(TellusNativeChunkPersistence.Status.COMMITTED, result.status());
        assertEquals(List.of("flush", "read", "submit", "flush", "read"), store.events);
        assertTrue(TellusNativeChunkPersistence.sameNBT(candidate, store.disk, Set.of()));
        result.previous().orElseThrow().putString("sentinel", "caller mutation");
        result.persisted().orElseThrow().remove("structures");
        assertEquals("old", result.previous().orElseThrow().getString("sentinel"));
        assertTrue(result.persisted().orElseThrow().contains("structures", 10));
    }
    @Test void everyRuntimeFaultPointRestoresTheEntirePriorNativeTagExactly() {
        for (var point : TellusNativeChunkPersistence.Point.values()) {
            var old = fullTag("old"); var store = store(old); var api = api(store);
            var result = api.commit(pos, fullTag("new"), at -> { if (at == point) throw new IllegalStateException(point.name()); });
            assertEquals(TellusNativeChunkPersistence.Status.ROLLED_BACK, result.status(), point.name());
            assertEquals(TellusNativeChunkPersistence.digest(old), TellusNativeChunkPersistence.digest(store.disk));
            assertFalse(api.quarantined(pos));
            assertEquals(point == TellusNativeChunkPersistence.Point.BEFORE_SUBMIT ? 1 : 2, store.submissions);
        }
    }
    @Test void everyErrorFaultPointRetainsExactErrorIdentityAfterCompleteRecovery() {
        for (var point : TellusNativeChunkPersistence.Point.values()) {
            var old = fullTag("old"); var store = store(old); var api = api(store); var fatal = new AssertionError(point.name());
            assertSame(fatal, assertThrows(AssertionError.class, () -> api.commit(pos, fullTag("new"), at -> { if (at == point) throw fatal; })));
            assertEquals(TellusNativeChunkPersistence.digest(old), TellusNativeChunkPersistence.digest(store.disk));
            assertFalse(api.quarantined(pos));
        }
    }
    @Test void metadataOnlyReadbackMismatchIsFailureAndRestoresCompletePriorTag() {
        var old = fullTag("old"); var store = store(old); store.corruptFirstSubmission = true; var api = api(store);
        var result = api.commit(pos, fullTag("new"), TellusNativeChunkPersistence.FaultInjector.NONE);
        assertEquals(TellusNativeChunkPersistence.Status.ROLLED_BACK, result.status());
        assertEquals(TellusNativeChunkPersistence.digest(old), TellusNativeChunkPersistence.digest(store.disk));
        assertEquals(2, store.submissions);
    }
    @Test void incompleteRecoveryQuarantinesPhysicalWorldChunkAcrossNewSessions() {
        var old = fullTag("old"); var store = store(old); var api = api(store);
        var original = new AssertionError("after submit"); var recovery = new AssertionError("restore failed");
        store.failSecondSubmission = recovery;
        var failure = assertThrows(IllegalStateException.class, () -> api.commit(pos, fullTag("new"), point -> {
            if (point == TellusNativeChunkPersistence.Point.AFTER_SUBMIT) throw original;
        }));
        assertSame(original, failure.getCause()); assertSame(recovery, failure.getSuppressed()[0]);
        assertTrue(api.quarantined(pos));
        var successorStore = store(fullTag("untrusted")); var successor = api(successorStore);
        assertEquals(TellusNativeChunkPersistence.Status.QUARANTINED,
            successor.commit(pos, fullTag("new"), TellusNativeChunkPersistence.FaultInjector.NONE).status());
        assertThrows(IllegalStateException.class, () -> successor.readExistingNBT(pos));
        assertTrue(successorStore.events.isEmpty());
    }
    @Test void wrongThreadSeedDimensionRootOrProfileIsColdBeforeWorkerAccess() {
        var store = store(fullTag("old")); var api = api(store); var bound = store.binding;
        store.main = false;
        assertRejectedCold(api, store); store.main = true;
        for (var changed : List.of(
            new TellusNativeChunkPersistence.Binding(root, "other:dimension", 19, "native.Generator", "profile", -64, 384, 63),
            new TellusNativeChunkPersistence.Binding(root, "minecraft:overworld", 20, "native.Generator", "profile", -64, 384, 63),
            new TellusNativeChunkPersistence.Binding(root, "minecraft:overworld", 19, "native.Generator", "other-profile", -64, 384, 63),
            new TellusNativeChunkPersistence.Binding(root.resolve("wrong"), "minecraft:overworld", 19, "native.Generator", "profile", -64, 384, 63))) {
            store.binding = changed; assertRejectedCold(api, store);
        }
        store.binding = bound;
    }
    @Test void apronAndOverflowingCoordinatesAreColdBeforeNativeWorkerAccess() {
        var store = store(fullTag("old")); var api = api(store); store.canonical = false;
        assertRejectedCold(api, store); store.canonical = true;
        assertEquals(TellusNativeChunkPersistence.Status.REJECTED,
            api.commit(new ChunkPos(Integer.MAX_VALUE, 0), fullTag("new"), TellusNativeChunkPersistence.FaultInjector.NONE).status());
        assertTrue(store.events.isEmpty());
    }
    @Test void missingBaselineAndIncompleteOrWrongCoordinateOrStatusPayloadNeverPublish() {
        var store = store(null); var api = api(store);
        assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, fullTag("new"), TellusNativeChunkPersistence.FaultInjector.NONE).status());
        assertEquals(0, store.submissions);
        store.disk = fullTag("old");
        for (String field : List.of("structures", "Heightmaps", "sections", "block_entities", "fluid_ticks", "PostProcessing", "DataVersion", "InhabitedTime", "yPos")) {
            var bad = fullTag("bad"); bad.remove(field);
            assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, bad, TellusNativeChunkPersistence.FaultInjector.NONE).status(), field);
        }
        var bad = fullTag("bad"); bad.putInt("xPos", pos.x + 1);
        assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, bad, TellusNativeChunkPersistence.FaultInjector.NONE).status());
        bad = fullTag("bad"); bad.putString("Status", "minecraft:noise");
        assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, bad, TellusNativeChunkPersistence.FaultInjector.NONE).status());
        assertEquals(0, store.submissions);
    }
    @Test void exactStatusSpellingAndNativeDataVersionAreBoundNotSilentlyNormalized() {
        var store = store(fullTag("old")); var api = api(store);
        var candidate = fullTag("new"); candidate.putString("Status", "full");
        assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, candidate, TellusNativeChunkPersistence.FaultInjector.NONE).status());
        candidate = fullTag("new"); candidate.putInt("DataVersion", 9999);
        assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, candidate, TellusNativeChunkPersistence.FaultInjector.NONE).status());
        assertEquals(0, store.submissions);
        store.disk.putString("Status", "full"); candidate = fullTag("new"); candidate.putString("Status", "full");
        assertEquals(TellusNativeChunkPersistence.Status.COMMITTED, api.commit(pos, candidate, TellusNativeChunkPersistence.FaultInjector.NONE).status());
    }
    @Test void duplicateForeignOrWrongTypedSectionsAreRejectedBeforeQueueWork() {
        var store = store(fullTag("old")); var api = api(store);
        var duplicate = fullTag("bad"); var sections = duplicate.getList("sections", 10); sections.add(sections.getCompound(0).copy());
        var outside = fullTag("bad"); outside.getList("sections", 10).getCompound(0).putInt("Y", 22);
        var boundaryBody = fullTag("bad"); boundaryBody.getList("sections", 10).getCompound(0).putInt("Y", -5);
        var wrongType = fullTag("bad"); var strings = new ListTag(); strings.add(StringTag.valueOf("bad")); wrongType.put("sections", strings);
        for (var bad : List.of(duplicate, outside, boundaryBody, wrongType))
            assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, bad, TellusNativeChunkPersistence.FaultInjector.NONE).status());
        assertTrue(store.events.isEmpty());
    }
    @Test void malformedNativeListPaletteAndLightShapesAreColdRejected() throws Exception {
        var store = store(fullTag("old")); var api = api(store);
        var malformed = new ArrayList<CompoundTag>();
        var typedConstructor = ListTag.class.getDeclaredConstructor(java.util.List.class, byte.class);
        typedConstructor.setAccessible(true);
        var wrongEmpty = fullTag("bad"); wrongEmpty.put("sections", typedConstructor.newInstance(new ArrayList<>(), (byte)8)); malformed.add(wrongEmpty);
        var wrongOffsets = fullTag("bad"); var outer = new ListTag(); var offsets = new ListTag(); offsets.add(net.minecraft.nbt.IntTag.valueOf(1)); outer.add(offsets); wrongOffsets.put("PostProcessing", outer); malformed.add(wrongOffsets);
        var wrongEmptyOffsets = fullTag("bad"); outer = new ListTag(); outer.add(typedConstructor.newInstance(new ArrayList<>(), (byte)8)); wrongEmptyOffsets.put("PostProcessing", outer); malformed.add(wrongEmptyOffsets);
        var scalarPayload = fullTag("bad"); scalarPayload.getList("sections", 10).getCompound(0).putString("block_states", "wrong"); malformed.add(scalarPayload);
        var emptyPalette = fullTag("bad"); emptyPalette.getList("sections", 10).getCompound(0).getCompound("block_states").put("palette", new ListTag()); malformed.add(emptyPalette);
        var missingName = fullTag("bad"); missingName.getList("sections", 10).getCompound(0).getCompound("block_states").getList("palette", 10).getCompound(0).remove("Name"); malformed.add(missingName);
        var wrongProperties = fullTag("bad"); wrongProperties.getList("sections", 10).getCompound(0).getCompound("block_states").getList("palette", 10).getCompound(0).putString("Properties", "wrong"); malformed.add(wrongProperties);
        var wrongData = fullTag("bad"); wrongData.getList("sections", 10).getCompound(0).getCompound("block_states").putIntArray("data", new int[]{1}); malformed.add(wrongData);
        var wrongBiome = fullTag("bad"); var names = new ListTag(); names.add(new CompoundTag()); wrongBiome.getList("sections", 10).getCompound(0).getCompound("biomes").put("palette", names); malformed.add(wrongBiome);
        var wrongLightType = fullTag("bad"); wrongLightType.getList("sections", 10).getCompound(0).putLongArray("BlockLight", new long[256]); malformed.add(wrongLightType);
        var wrongLightLength = fullTag("bad"); wrongLightLength.getList("sections", 10).getCompound(0).putByteArray("SkyLight", new byte[2047]); malformed.add(wrongLightLength);
        for (var bad : malformed)
            assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, bad, TellusNativeChunkPersistence.FaultInjector.NONE).status());
        assertTrue(store.events.isEmpty());
        assertEquals(0, store.submissions);
        var validEmptyLists = fullTag("valid"); validEmptyLists.put("block_ticks", typedConstructor.newInstance(new ArrayList<>(), (byte)10));
        outer = new ListTag(); outer.add(typedConstructor.newInstance(new ArrayList<>(), (byte)2)); validEmptyLists.put("PostProcessing", outer);
        assertEquals(TellusNativeChunkPersistence.Status.COMMITTED,
            api.commit(pos, validEmptyLists, TellusNativeChunkPersistence.FaultInjector.NONE).status());
    }
    @Test void reentrantSamePhysicalChunkIsAdmittedOnlyOnceAndLeaseIsReleased() {
        var store = store(fullTag("old")); var api = api(store);
        var result = api.commit(pos, fullTag("new"), point -> {
            if (point == TellusNativeChunkPersistence.Point.BEFORE_SUBMIT) {
                assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, fullTag("nested"), TellusNativeChunkPersistence.FaultInjector.NONE).status());
                assertThrows(IllegalStateException.class, () -> api.readExistingNBT(pos));
            }
        });
        assertEquals(TellusNativeChunkPersistence.Status.COMMITTED, result.status());
        assertEquals(TellusNativeChunkPersistence.Status.COMMITTED, api.commit(pos, fullTag("next"), TellusNativeChunkPersistence.FaultInjector.NONE).status());
    }
    @Test void multiChunkBatchCommitsInCanonicalOrderAndRestoresEarlierStages() {
        ChunkPos second = new ChunkPos(-2, 7);
        var store = new BatchStore(store(fullTag("unused")).binding, Map.of(
            pos, fullTagAt(pos, "old-a"), second, fullTagAt(second, "old-b")));
        var api = api(store);
        var failed = api.commitBatch(List.of(
            new TellusNativeChunkPersistence.BatchEntry(second, fullTagAt(second, "new-b")),
            new TellusNativeChunkPersistence.BatchEntry(pos, fullTagAt(pos, "new-a"))),
            (ordinal, ignored, point) -> { if (ordinal == 1 && point == TellusNativeChunkPersistence.Point.BEFORE_SUBMIT)
                throw new IllegalStateException("second stage"); });
        assertEquals(TellusNativeChunkPersistence.BatchStatus.ROLLED_BACK, failed.status());
        assertEquals("old-a", store.disk.get(pos).getString("sentinel"));
        assertEquals("old-b", store.disk.get(second).getString("sentinel"));

        var committed = api.commitBatch(List.of(
            new TellusNativeChunkPersistence.BatchEntry(pos, fullTagAt(pos, "final-a")),
            new TellusNativeChunkPersistence.BatchEntry(second, fullTagAt(second, "final-b"))),
            TellusNativeChunkPersistence.BatchFaultInjector.NONE);
        assertEquals(TellusNativeChunkPersistence.BatchStatus.COMMITTED, committed.status());
        assertEquals("final-a", store.disk.get(pos).getString("sentinel"));
        assertEquals("final-b", store.disk.get(second).getString("sentinel"));
    }
    @Test void nativeHeightBudgetAndAlignmentAreExplicit() {
        assertThrows(IllegalArgumentException.class, () -> new TellusNativeChunkPersistence.Binding(root, "dim", 1, "gen", "profile", -64, 8192, 63));
        assertThrows(IllegalArgumentException.class, () -> new TellusNativeChunkPersistence.Binding(root, "dim", 1, "gen", "profile", -63, 384, 63));
    }
    private void assertRejectedCold(TellusNativeChunkPersistence api, FakeStore store) {
        assertEquals(TellusNativeChunkPersistence.Status.REJECTED, api.commit(pos, fullTag("new"), TellusNativeChunkPersistence.FaultInjector.NONE).status());
        assertThrows(RuntimeException.class, () -> api.readExistingNBT(pos)); assertTrue(store.events.isEmpty());
    }
    private TellusNativeChunkPersistence api(FakeStore store) { return TellusNativeChunkPersistence.forStore(store.binding, store); }
    private TellusNativeChunkPersistence api(BatchStore store) { return TellusNativeChunkPersistence.forStore(store.binding, store); }
    private FakeStore store(CompoundTag disk) {
        return new FakeStore(new TellusNativeChunkPersistence.Binding(root, "minecraft:overworld", 19, "native.Generator", "profile", -64, 384, 63), disk);
    }
    private CompoundTag fullTag(String sentinel) {
        return fullTagAt(pos, sentinel);
    }
    private CompoundTag fullTagAt(ChunkPos position, String sentinel) {
        var tag = new CompoundTag(); tag.putInt("DataVersion", 3465); tag.putInt("xPos", position.x); tag.putInt("zPos", position.z); tag.putInt("yPos", -4);
        tag.putString("Status", "minecraft:full"); tag.putLong("LastUpdate", 120); tag.putLong("InhabitedTime", 150);
        tag.putString("sentinel", sentinel); tag.putBoolean("isLightOn", true);
        var section = new CompoundTag(); section.putInt("Y", -4);
        var palette = new CompoundTag(); var states = new ListTag(); var stone = new CompoundTag(); stone.putString("Name", "minecraft:stone"); states.add(stone); palette.put("palette", states); section.put("block_states", palette);
        var biomes = new CompoundTag(); var biomeNames = new ListTag(); biomeNames.add(StringTag.valueOf("minecraft:plains")); biomes.put("palette", biomeNames); section.put("biomes", biomes);
        section.putByteArray("BlockLight", new byte[2048]); var sections = new ListTag(); sections.add(section); tag.put("sections", sections);
        var heightmaps = new CompoundTag(); heightmaps.putLongArray("WORLD_SURFACE", new long[]{4,5,6}); tag.put("Heightmaps", heightmaps);
        var structures = new CompoundTag(); structures.putLongArray("References", new long[]{7,8}); tag.put("structures", structures);
        for (String list : List.of("PostProcessing", "block_ticks", "fluid_ticks", "block_entities")) tag.put(list, new ListTag());
        return tag;
    }
    private static final class FakeStore implements TellusNativeChunkPersistence.Store {
        TellusNativeChunkPersistence.Binding binding; CompoundTag disk, pending; boolean main = true, canonical = true, corruptFirstSubmission;
        Error failSecondSubmission; int submissions; final List<String> events = new ArrayList<>();
        FakeStore(TellusNativeChunkPersistence.Binding binding, CompoundTag disk) { this.binding = binding; this.disk = disk == null ? null : disk.copy(); }
        @Override public TellusNativeChunkPersistence.Binding currentBinding() { return binding; }
        @Override public boolean mainThread() { return main; }
        @Override public boolean canonical(ChunkPos pos) { return canonical; }
        @Override public Optional<CompoundTag> read(ChunkPos pos) { events.add("read"); return Optional.ofNullable(disk).map(CompoundTag::copy); }
        @Override public void submit(ChunkPos pos, CompoundTag tag) {
            events.add("submit"); submissions++;
            if (submissions == 2 && failSecondSubmission != null) throw failSecondSubmission;
            pending = tag.copy();
            if (submissions == 1 && corruptFirstSubmission) pending.getCompound("structures").putString("unexpected", "corrupted metadata");
        }
        @Override public void flush() { events.add("flush"); if (pending != null) { disk = pending.copy(); pending = null; } }
    }
    private static final class BatchStore implements TellusNativeChunkPersistence.Store {
        final TellusNativeChunkPersistence.Binding binding; final Map<ChunkPos, CompoundTag> disk = new HashMap<>();
        boolean main = true;
        BatchStore(TellusNativeChunkPersistence.Binding binding, Map<ChunkPos, CompoundTag> initial) {
            this.binding = binding; initial.forEach((position, tag) -> disk.put(position, tag.copy()));
        }
        @Override public TellusNativeChunkPersistence.Binding currentBinding() { return binding; }
        @Override public boolean mainThread() { return main; }
        @Override public boolean canonical(ChunkPos position) { return true; }
        @Override public Optional<CompoundTag> read(ChunkPos position) { return Optional.ofNullable(disk.get(position)).map(CompoundTag::copy); }
        @Override public void submit(ChunkPos position, CompoundTag tag) { disk.put(position, tag.copy()); }
        @Override public void flush() { }
    }
}
