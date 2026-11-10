package com.yucareux.tellus.worldgen.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.ShortTag;
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
    @Test void preparedJournalRecoversPreviousNativeTagsBeforeNormalWork() throws Exception {
        var position = new ChunkPos(-3, 7);
        var store = new BatchStore(store(fullTag("unused")).binding, Map.of(position, fullTagAt(position, "old")));
        var previous = fullTagAt(position, "old");
        var proposed = fullTagAt(position, "new");
        var journal = TellusNativeBatchJournal.prepare(store.binding,
            List.of(new TellusNativeBatchJournal.Entry(position, previous, proposed)));
        store.disk.put(position, proposed.copy());
        assertTrue(Files.exists(journal));
        var diagnostics = api(store).recoverPendingBatches();
        assertFalse(diagnostics.isEmpty());
        assertEquals("old", store.disk.get(position).getString("sentinel"));
        assertFalse(Files.exists(journal));
    }
    @Test void competingNativeBatchIsRejectedByWorldRootLease() throws Exception {
        var store = new BatchStore(store(fullTag("unused")).binding, Map.of(pos, fullTag("old")));
        var api = api(store);
        try (TellusNativeWorldLease ignored = TellusNativeWorldLease.acquire(store.binding)) {
            var result = api.commitBatch(List.of(new TellusNativeChunkPersistence.BatchEntry(pos, fullTag("new"))),
                TellusNativeChunkPersistence.BatchFaultInjector.NONE);
            assertEquals(TellusNativeChunkPersistence.BatchStatus.REJECTED, result.status());
            assertTrue(result.diagnostics().get(0).contains("lease unavailable"));
        }
    }
    @Test void pendingJournalBlocksNormalReadAndBothPublicationEntrypointsUntilRecovery() throws Exception {
        var store = store(fullTag("partial")); var api = api(store);
        var journal = TellusNativeBatchJournal.prepare(store.binding,
            List.of(new TellusNativeBatchJournal.Entry(pos, fullTag("original"), fullTag("partial"))));
        assertThrows(IllegalStateException.class, () -> api.readExistingNBT(pos));
        assertEquals(TellusNativeChunkPersistence.Status.QUARANTINED,
            api.commit(pos, fullTag("later"), TellusNativeChunkPersistence.FaultInjector.NONE).status());
        assertEquals(TellusNativeChunkPersistence.BatchStatus.QUARANTINED,
            api.commitBatch(List.of(new TellusNativeChunkPersistence.BatchEntry(pos, fullTag("later"))), TellusNativeChunkPersistence.BatchFaultInjector.NONE).status());
        assertTrue(store.events.isEmpty());
        assertFalse(api.recoverPendingBatches().isEmpty());
        assertEquals("original", store.disk.getString("sentinel")); assertFalse(Files.exists(journal));
        assertEquals(TellusNativeChunkPersistence.Status.COMMITTED,
            api.commit(pos, fullTag("later"), TellusNativeChunkPersistence.FaultInjector.NONE).status());
    }
    @Test void incompleteSingleRecoveryLeavesDurableQuarantineAndCanBeRecoveredExplicitly() throws Exception {
        var store = store(fullTag("original")); var api = api(store);
        store.failSecondSubmission = new AssertionError("restore unavailable");
        assertThrows(IllegalStateException.class, () -> api.commit(pos, fullTag("partial"), point -> {
            if (point == TellusNativeChunkPersistence.Point.AFTER_SUBMIT) throw new IllegalStateException("fault");
        }));
        var records = TellusNativeBatchJournal.pending(store.binding); assertEquals(1, records.size());
        assertEquals(TellusNativeBatchJournal.State.QUARANTINED, records.get(0).state());
        assertEquals("original", records.get(0).entries().get(0).previous().getString("sentinel"));
        var repaired = store(fullTag("partial")); var successor = api(repaired);
        assertFalse(successor.recoverPendingBatches().isEmpty());
        assertEquals("original", repaired.disk.getString("sentinel"));
        assertFalse(successor.quarantined(pos)); assertTrue(TellusNativeBatchJournal.pending(repaired.binding).isEmpty());
    }
    @Test void batchErrorRestoresEarlierStagesAndRetainsOriginalErrorIdentity() throws Exception {
        var second = new ChunkPos(-2,7);
        var store = new BatchStore(store(fullTag("unused")).binding, Map.of(pos,fullTag("old-a"),second,fullTagAt(second,"old-b")));
        var fatal = new AssertionError("later native stage");
        assertSame(fatal, assertThrows(AssertionError.class, () -> api(store).commitBatch(List.of(
            new TellusNativeChunkPersistence.BatchEntry(pos,fullTag("new-a")),
            new TellusNativeChunkPersistence.BatchEntry(second,fullTagAt(second,"new-b"))), (ordinal,p,point) -> {
                if(ordinal==1 && point==TellusNativeChunkPersistence.Point.AFTER_READBACK) throw fatal;
            })));
        assertEquals("old-a",store.disk.get(pos).getString("sentinel"));
        assertEquals("old-b",store.disk.get(second).getString("sentinel"));
        assertTrue(TellusNativeBatchJournal.pending(store.binding).isEmpty());
    }
    @Test void recoveryValidatesAllNativePayloadsBeforeAnySubmissionAndKeepsOtherDimensions() throws Exception {
        var store = store(fullTag("original"));
        var broken = fullTag("bad"); broken.remove("structures");
        var path = TellusNativeBatchJournal.prepare(store.binding,
            List.of(new TellusNativeBatchJournal.Entry(pos,broken,fullTag("candidate"))));
        assertThrows(IllegalStateException.class, () -> api(store).recoverPendingBatches());
        assertTrue(store.events.isEmpty()); assertEquals(TellusNativeBatchJournal.State.QUARANTINED,TellusNativeBatchJournal.read(path).state());
        var b=store.binding;
        var other = new TellusNativeChunkPersistence.Binding(root,"other:dimension",b.seed(),b.generatorType(),b.generatorProfile(),b.minY(),b.height(),b.seaLevel());
        var otherStore=new FakeStore(other,fullTag("other"));
        assertTrue(api(otherStore).recoverPendingBatches().isEmpty());
        assertTrue(Files.exists(path));assertTrue(otherStore.events.isEmpty());
    }
    @Test void nonEmptyEntityTickFluidAndPostProcessingPayloadsRoundTripAsNativeTags() {
        var old = fullTag("old"); var candidate = fullTag("nonempty");
        var entity = new CompoundTag(); entity.putString("id", "minecraft:chest"); entity.putInt("x", pos.x * 16 + 3); entity.putInt("y", 64); entity.putInt("z", pos.z * 16 + 4);
        var entities = new ListTag(); entities.add(entity); candidate.put("block_entities", entities);
        var blockTick = new CompoundTag(); blockTick.putString("i", "minecraft:stone"); blockTick.putInt("x", pos.x * 16 + 3); blockTick.putInt("y", 63); blockTick.putInt("z", pos.z * 16 + 4); blockTick.putInt("t", 20); blockTick.putInt("p", 0);
        var blockTicks = new ListTag(); blockTicks.add(blockTick); candidate.put("block_ticks", blockTicks);
        var fluidTick = new CompoundTag(); fluidTick.putString("i", "minecraft:water"); fluidTick.putInt("x", pos.x * 16 + 5); fluidTick.putInt("y", 62); fluidTick.putInt("z", pos.z * 16 + 6); fluidTick.putInt("t", 40); fluidTick.putInt("p", 1);
        var fluidTicks = new ListTag(); fluidTicks.add(fluidTick); candidate.put("fluid_ticks", fluidTicks);
        var markerColumn = new ListTag(); markerColumn.add(ShortTag.valueOf((short) 7)); markerColumn.add(ShortTag.valueOf((short) 11));
        var postProcessing = new ListTag(); postProcessing.add(markerColumn); candidate.put("PostProcessing", postProcessing);
        var store = store(old); var result = api(store).commit(pos, candidate, TellusNativeChunkPersistence.FaultInjector.NONE);
        assertEquals(TellusNativeChunkPersistence.Status.COMMITTED, result.status());
        assertTrue(TellusNativeChunkPersistence.sameNBT(candidate, store.disk, Set.of()));
        assertEquals(1, store.disk.getList("block_entities", 10).size());
        assertEquals(1, store.disk.getList("block_ticks", 10).size());
        assertEquals(1, store.disk.getList("fluid_ticks", 10).size());
        assertEquals(2, store.disk.getList("PostProcessing", 9).getList(0).size());
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
