package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.worldgen.caves.CubeNetUndergroundStageAdapter.ReplayStatus;
import com.yucareux.tellus.worldgen.caves.UndergroundStagePlan.Delta;
import com.yucareux.tellus.worldgen.caves.UndergroundStagePlan.UndergroundMaterial;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.CubeNetAddressAdapter;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.write.TellusTargetWriteGuard;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubeNetUndergroundStageAdapterTest {
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1000.0);
   private static final TellusGenerationContext GENERATION =
      new TellusGenerationContext(42L, 1000.0, 64, -64, 320);
   private final CubeNetUndergroundStageAdapter adapter = new CubeNetUndergroundStageAdapter();

   @Test
   void publishesOrderedCellsAndExactlyOneAdditionalOccurrencePerFluidMarker() {
      var plan = plan();
      MemoryTarget target = new MemoryTarget();
      Pos water = pos(plan.deltas().get(0));
      target.metadata.markers.addAll(List.of(water, water, new Pos(9, 4, 8)));
      var originalMarkers = List.copyOf(target.metadata.markers);
      var result = adapter.replay(plan, target);
      assertEquals(ReplayStatus.WRITTEN, result.status());
      assertEquals(3, result.appliedDeltas());
      assertEquals(plan.deltas().stream().map(Delta::material).toList(), target.values(plan));
      assertEquals(plan.deltas().stream().map(CubeNetUndergroundStageAdapterTest::pos).toList(), target.writeOrder);
      List<Pos> expected = new ArrayList<>(originalMarkers);
      expected.add(water);
      expected.add(pos(plan.deltas().get(2)));
      assertEquals(expected, target.metadata.markers);
      assertEquals(3, target.metadata.occurrences(water.x(), water.y(), water.z()));
      assertTrue(target.dirty);
      assertTrue(target.allTargetCallbacksLeased);
   }

   @Test
   void emptyPlanPreservesDirtyAndMetadataAndDoesNotWrite() {
      MemoryTarget target = new MemoryTarget();
      target.metadata.markers.add(new Pos(4, 2, 1));
      var result = adapter.replay(UndergroundStagePlan.available(request(), List.of(), List.of()), target);
      assertEquals(ReplayStatus.WRITTEN, result.status());
      assertEquals(0, result.appliedDeltas());
      assertEquals(0, target.writes);
      assertFalse(target.dirty);
      assertEquals(List.of(new Pos(4, 2, 1)), target.metadata.markers);
   }

   @Test
   void unavailablePlansRemainTargetColdIncludingNullTarget() {
      var queued = UndergroundStagePlan.blocked(request(), DecisionStatus.QUEUED, "source queued");
      var unknown = UndergroundStagePlan.blocked(request(), DecisionStatus.UNKNOWN, "source unknown");
      assertEquals(ReplayStatus.QUEUED, adapter.replay(queued, null).status());
      assertEquals(ReplayStatus.UNKNOWN, adapter.replay(unknown, null).status());
       var r = request();
       var nonblocking = new UndergroundStagePlan.Request(r.generation(),r.model(),r.address(),r.chunkX(),r.chunkZ(),
          r.surfaceY(),r.floodGuardY(),r.generationFloorY(),r.structureExclusions(),r.surfaceHaloY(),r.flags(),r.sourceRevision(),SamplingResolution.nonBlocking(1));
       assertEquals(ReplayStatus.QUEUED,adapter.replay(UndergroundStagePlan.available(nonblocking,plan().deltas(),List.of()),null).status());
   }

   @Test
   void targetMismatchRejectsBeforeCellOrMetadataReads() {
      MemoryTarget target = new MemoryTarget();
      target.chunkX = 1;
      var result = adapter.replay(plan(), target);
      assertEquals(ReplayStatus.INVALID, result.status());
      assertEquals(0, result.appliedDeltas());
      assertEquals(0, target.reads);
      assertEquals(0, target.metadata.snapshots);
      assertEquals(0, target.writes);
   }

   @Test
   void bridgePreflightRunsUnderLeaseAndCanRejectStalePlanWithoutTargetReads() {
      MemoryTarget target = new MemoryTarget();
      target.preflightFailure = new HostileFailure();
      var result = adapter.replay(plan(), target);
      assertEquals(ReplayStatus.INVALID, result.status());
      assertTrue(target.allTargetCallbacksLeased);
      assertEquals(1, target.preflights);
      assertEquals(0, target.reads);
      assertEquals(0, target.metadata.snapshots);
      assertEquals(0, target.writes);
      assertLeaseReleased(target);
   }

   @Test
   void allCellReplaceabilityIsCheckedBeforeAnyWrite() {
      MemoryTarget target = new MemoryTarget();
      target.cells.put(pos(plan().deltas().get(2)), "BEDROCK");
      var before = target.values(plan());
      var result = adapter.replay(plan(), target);
      assertEquals(ReplayStatus.INVALID, result.status());
      assertEquals(3, target.reads);
      assertEquals(0, target.writes);
      assertEquals(0, target.metadata.snapshots);
      assertEquals(before, target.values(plan()));
      assertLeaseReleased(target);
   }

   @Test
   void runtimeAfterMutationRestoresAllCellsMarkersDirtyAndReverseOrder() {
      var plan = plan();
      MemoryTarget target = new MemoryTarget();
      target.metadata.markers.addAll(List.of(new Pos(0, -20, 0), new Pos(0, -20, 0)));
      var markers = List.copyOf(target.metadata.markers);
      var before = target.values(plan);
      target.failWrite = 2;
      target.publicationFailure = new HostileFailure();
      var result = adapter.replay(plan, target);
      assertEquals(ReplayStatus.INVALID, result.status());
      assertEquals(0, result.appliedDeltas());
      assertEquals(before, target.values(plan));
      assertEquals(markers, target.metadata.markers);
      assertFalse(target.dirty);
      assertEquals(List.of(pos(plan.deltas().get(2)), pos(plan.deltas().get(1)), pos(plan.deltas().get(0))), target.restoreOrder);
      assertTrue(result.diagnostics().get(0).contains(HostileFailure.class.getName()));
      assertLeaseReleased(target);
   }

   @Test
   void errorAfterMutationRestoresThenRethrowsSameError() {
      MemoryTarget target = new MemoryTarget();
      HostileError error = new HostileError();
      target.failWrite = 2;
      target.publicationFailure = error;
      var before = target.values(plan());
      assertSame(error, assertThrows(HostileError.class, () -> adapter.replay(plan(), target)));
      assertEquals(before, target.values(plan()));
      assertTrue(target.metadata.markers.isEmpty());
      assertFalse(target.dirty);
      assertLeaseReleased(target);
   }

   @Test
   void markerOccurrenceReadbackFailureRecoversDuplicateSensitiveSnapshot() {
      MemoryTarget target = new MemoryTarget();
      target.metadata.markers.addAll(List.of(new Pos(8, 1, 8), new Pos(8, 1, 8)));
      var markers = List.copyOf(target.metadata.markers);
      var before = target.values(plan());
      target.metadata.duplicateMark = true;
      var result = adapter.replay(plan(), target);
      assertEquals(ReplayStatus.INVALID, result.status());
      assertEquals(before, target.values(plan()));
      assertEquals(markers, target.metadata.markers);
      assertFalse(target.dirty);
      assertLeaseReleased(target);
   }

   @Test
   void silentlyDroppedCellWriteIsDetectedAndRecovered() {
      MemoryTarget target = new MemoryTarget();
      target.dropWrite = 2;
      var before = target.values(plan());
      var result = adapter.replay(plan(), target);
      assertEquals(ReplayStatus.INVALID, result.status());
      assertEquals(before, target.values(plan()));
      assertTrue(target.metadata.markers.isEmpty());
      assertFalse(target.dirty);
      assertLeaseReleased(target);
   }

   @Test
   void incompleteRecoveryRetainsOriginalCauseAndStillRestoresMetadataAndDirty() {
      MemoryTarget target = new MemoryTarget();
      HostileFailure original = new HostileFailure();
      target.failWrite = 2;
      target.publicationFailure = original;
      target.failRestore = true;
      var markers = List.copyOf(target.metadata.markers);
      IllegalStateException failure = assertThrows(IllegalStateException.class,
         () -> adapter.replay(plan(), target));
      assertSame(original, failure.getCause());
      assertTrue(failure.getSuppressed().length > 0);
      assertEquals(3, target.restoreOrder.size());
      assertEquals(markers, target.metadata.markers);
      assertFalse(target.dirty);
      assertLeaseReleased(target);
   }

   @Test
   void sameActualObjectQueuesBeforePreflightWhileDifferentIdentityCanWrite() {
      MemoryTarget blocked = new MemoryTarget();
      MemoryTarget different = new MemoryTarget();
      try (var held = TellusTargetWriteGuard.tryAcquire(blocked.identity).orElseThrow()) {
         var result = adapter.replay(plan(), blocked);
         assertEquals(ReplayStatus.QUEUED, result.status());
         assertEquals(0, blocked.preflights);
         assertEquals(0, blocked.reads);
         assertEquals(0, blocked.writes);
         assertEquals(ReplayStatus.WRITTEN, adapter.replay(plan(), different).status());
      }
      assertLeaseReleased(blocked);
      assertLeaseReleased(different);
   }

   @Test
   void duplicatePositionPlanRejectsBeforeAnyTargetExists() {
      Delta delta = plan().deltas().get(0);
      assertThrows(IllegalArgumentException.class,
         () -> UndergroundStagePlan.available(request(), List.of(delta, delta), List.of()));
      assertThrows(IllegalArgumentException.class,
         () -> new CubeNetUndergroundStageAdapter.ReplayResult(ReplayStatus.INVALID, 1, List.of()));
   }

   @Test
   void nullCellAndNullMarkerSnapshotFailBeforeMutation() {
      MemoryTarget nullCell = new MemoryTarget();
      nullCell.nullRead = true;
      assertEquals(ReplayStatus.INVALID, adapter.replay(plan(), nullCell).status());
      assertEquals(0, nullCell.writes);
      MemoryTarget nullMetadata = new MemoryTarget();
      nullMetadata.metadata.nullSnapshot = true;
      assertEquals(ReplayStatus.INVALID, adapter.replay(plan(), nullMetadata).status());
      assertEquals(0, nullMetadata.writes);
      assertLeaseReleased(nullCell);
      assertLeaseReleased(nullMetadata);
   }

   private static UndergroundStagePlan.Request request() {
      int[] surface = new int[256];
      Arrays.fill(surface, 80);
      return new UndergroundStagePlan.Request(GENERATION, MODEL,
         new CubeNetAddressAdapter(MODEL).chunkAddress(0, 0).orElseThrow(), 0, 0,
         surface, null, null, List.of(),
         new UndergroundStagePlan.Flags(true, false, false, false),
         "generic-replay-v1", SamplingResolution.exact(1));
   }

   private static UndergroundStagePlan plan() {
      return UndergroundStagePlan.available(request(), List.of(
         new Delta(0, -20, 0, UndergroundMaterial.WATER, true),
         new Delta(1, -21, 0, UndergroundMaterial.CAVE_AIR, false),
         new Delta(2, -22, 0, UndergroundMaterial.LAVA, true)), List.of());
   }

   private static Pos pos(Delta delta) { return new Pos(delta.x(), delta.y(), delta.z()); }

   private static void assertLeaseReleased(MemoryTarget target) {
      try (var lease = TellusTargetWriteGuard.tryAcquire(target.identity).orElseThrow()) {
         lease.requireOwned(target.identity);
      }
   }

   private record Pos(int x, int y, int z) { }

   private static final class HostileFailure extends RuntimeException {
      @Override public String getMessage() { throw new AssertionError("message callback entered"); }
      @Override public String toString() { throw new AssertionError("format callback entered"); }
   }

   private static final class HostileError extends Error {
      @Override public String getMessage() { throw new AssertionError("message callback entered"); }
      @Override public String toString() { throw new AssertionError("format callback entered"); }
   }

   private static final class MemoryTarget implements CubeNetUndergroundStageAdapter.Target {
      private final Object identity = new Object();
      private final Map<Pos, Object> cells = new HashMap<>();
      private final Markers metadata = new Markers();
      private final List<Pos> writeOrder = new ArrayList<>();
      private final List<Pos> restoreOrder = new ArrayList<>();
      private int chunkX;
      private int reads;
      private int writes;
      private int preflights;
      private int failWrite = -1;
      private int dropWrite = -1;
      private boolean failRestore;
      private boolean nullRead;
      private boolean dirty;
      private boolean allTargetCallbacksLeased = true;
      private RuntimeException preflightFailure;
      private Throwable publicationFailure;

      private void requireLeased() {
         var unexpected = TellusTargetWriteGuard.tryAcquire(identity);
         if (unexpected.isPresent()) {
            unexpected.orElseThrow().close();
            allTargetCallbacksLeased = false;
            throw new IllegalStateException("callback executed outside target lease");
         }
      }
      @Override public Object identity() { return identity; }
      @Override public void preflight() {
         requireLeased();
         preflights++;
         if (preflightFailure != null) throw preflightFailure;
      }
      @Override public int chunkX() { requireLeased(); return chunkX; }
      @Override public int chunkZ() { requireLeased(); return 0; }
      @Override public int minY() { requireLeased(); return -64; }
      @Override public int maxYExclusive() { requireLeased(); return 320; }
      @Override public Object read(int x, int y, int z) {
         requireLeased();
         reads++;
         return nullRead ? null : cells.getOrDefault(new Pos(x, y, z), "STONE");
      }
      @Override public boolean replaceable(Object current, UndergroundMaterial material) {
         requireLeased();
         return !"BEDROCK".equals(current);
      }
      @Override public void write(int x, int y, int z, UndergroundMaterial material) {
         requireLeased();
         writes++;
         Pos pos = new Pos(x, y, z);
         writeOrder.add(pos);
         if (writes != dropWrite) cells.put(pos, material);
         if (writes == failWrite) {
            dirty = true;
            metadata.markers.add(new Pos(5, -1, 5));
            if (publicationFailure instanceof Error error) throw error;
            throw (RuntimeException)publicationFailure;
         }
      }
      @Override public void writePrevious(int x, int y, int z, Object previous) {
         requireLeased();
         Pos pos = new Pos(x, y, z);
         restoreOrder.add(pos);
         if (failRestore) {
            failRestore = false;
            throw new IllegalStateException("injected restoration failure");
         }
         cells.put(pos, previous);
      }
      @Override public CubeNetUndergroundStageAdapter.PostProcessing postProcessing() {
         requireLeased();
         return metadata;
      }
      @Override public boolean dirty() { requireLeased(); return dirty; }
      @Override public void setDirty(boolean value) { requireLeased(); dirty = value; }
      private List<Object> values(UndergroundStagePlan plan) {
         return plan.deltas().stream().map(delta -> cells.getOrDefault(pos(delta), "STONE")).toList();
      }
   }

   private static final class Markers implements CubeNetUndergroundStageAdapter.PostProcessing {
      private final List<Pos> markers = new ArrayList<>();
      private int snapshots;
      private boolean duplicateMark;
      private boolean nullSnapshot;
      @Override public Object snapshot() { snapshots++; return nullSnapshot ? null : List.copyOf(markers); }
      @Override public int occurrences(int x, int y, int z) {
         return (int)markers.stream().filter(new Pos(x, y, z)::equals).count();
      }
      @Override public void mark(int x, int y, int z) {
         Pos pos = new Pos(x, y, z);
         markers.add(pos);
         if (duplicateMark) markers.add(pos);
      }
      @Override public void restore(Object snapshot) {
         markers.clear();
         for (Object value : (List<?>)snapshot) markers.add((Pos)value);
      }
   }
}
