package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.worldgen.decision.BiomeDecision;
import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.StrataDecision;
import com.yucareux.tellus.worldgen.decision.SurfaceDecision;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.WaterDecision;
import com.yucareux.tellus.worldgen.feature.FeatureKind;
import com.yucareux.tellus.worldgen.feature.FeaturePlacement;
import com.yucareux.tellus.worldgen.feature.FeaturePlacementSinkGuard;
import com.yucareux.tellus.worldgen.feature.FeaturePlan;
import com.yucareux.tellus.worldgen.feature.FeaturePlanStatus;
import com.yucareux.tellus.worldgen.feature.PlacementConflictPolicy;
import com.yucareux.tellus.worldgen.feature.PlacementOperation;
import com.yucareux.tellus.worldgen.feature.WaterFlowState;
import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.write.TellusCompositeColumnWriter.Cell;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Pure in-memory block/marker transaction contracts, not live chunk or durable-storage acceptance. */
class TellusFlowCompositeColumnWriterTest {
   private static final String AIR = "AIR";
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1.0);
   private static final TellusCoordinateContext COORDINATE = earthCoordinate();
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(41L, 1.0, 0, -2, 16);
   private static final WriteContext CONTEXT = WriteContext.canonical(COORDINATE, GENERATION, "flow-composite-test");
   private static final CanonicalColumnPosition BASE = CanonicalColumnPosition.resolve(CONTEXT);
   private final TellusCompositeColumnWriter<String> writer = new TellusCompositeColumnWriter<>(new TellusColumnMaterializer());

   @Test void everyExactFlowLevelCommitsBlocksThenAppendsMarkersWithoutCollapsingExistingDuplicates() {
      for (int level = 1; level <= 8; level++) {
         MemoryTarget target = seeded();
         Before before = before(target);
         FlowMapper mapper = new FlowMapper(target);
         WriteResult<String> result = writer.write(target, CONTEXT, available(), plan(flow("flow", 3, 2, level)), mapper);

         assertEquals(WriteStatus.WRITTEN, result.status());
         assertTrue(result.transientPostProcessing());
         assertEquals(Optional.of(identity()), result.persistenceIdentity());
         assertEquals(List.of("STONE@-2", "STONE@-1", "STONE@0", "STONE@1",
            flowState(level, 3), flowState(level, 4)), result.mappedWrites());
         assertEquals(flowState(level, 3), target.states.get(cell(3)));
         assertEquals(flowState(level, 4), target.states.get(cell(4)));
         List<Cell> expected = new ArrayList<>(before.markers());
         expected.add(cell(3)); expected.add(cell(4));
         assertEquals(expected, target.markers, "existing duplicate markers retain their order and multiplicity");
         assertEquals(3, Collections.frequency(target.markers, cell(3)));
         assertEquals(1, Collections.frequency(target.markers, cell(4)));
         assertEquals(1, target.capabilityCalls); assertEquals(1, target.snapshots);
         assertEquals(2, target.marks); assertEquals(0, target.restores); assertEquals(1, target.dirtyCalls);
         assertEquals(6, target.writes.size()); assertEquals(4, mapper.materialCalls); assertEquals(2, mapper.featureCalls);
         assertTrue(target.dirty);
         assertTrue(target.events.indexOf("capability") < target.events.indexOf("map"));
         assertTrue(target.events.indexOf("snapshot") < target.events.indexOf("write"));
         assertTrue(target.events.lastIndexOf("write") < target.events.indexOf("mark"));
         assertTrue(target.events.lastIndexOf("mark") < target.events.indexOf("dirty"));
      }
   }

   @Test void unsupportedTargetIsRejectedBeforeMappingAndDefaultMapperRejectsPropertiesBeforeReads() {
      MemoryTarget unsupported = seeded();
      Before unsupportedBefore = before(unsupported);
      AtomicInteger mapped = new AtomicInteger();
      TellusCompositeColumnWriter.Target<String> legacy = new TellusCompositeColumnWriter.Target<>() {
         public int chunkX() { return unsupported.chunkX(); }
         public int chunkZ() { return unsupported.chunkZ(); }
         public int minY() { return unsupported.minY(); }
         public int maxYExclusive() { return unsupported.maxYExclusive(); }
         public String read(Cell cell) { fail("missing capability must precede all block reads"); return AIR; }
         public void write(Cell cell, String state) { fail("missing capability must precede all writes"); }
         public boolean isAir(String state) { fail("missing capability must precede conflict evaluation"); return false; }
         public void markDirty() { fail("missing capability must not mark dirty"); }
      };
      assertNull(legacy.postProcessing(), "old adapters default to no metadata transaction capability");
      WriteResult<String> noCapability = writer.write(legacy, CONTEXT, available(), plan(flow("legacy", 3, 2, 4)),
         (material, y, context) -> { mapped.incrementAndGet(); return state(material, y); });
      assertInvalid(noCapability, "post-processing transaction support"); assertEquals(0, mapped.get());
      assertRestored(unsupportedBefore, unsupported); assertTrue(unsupported.writes.isEmpty());

      MemoryTarget target = seeded(); Before initial = before(target);
      SemanticMapper<String> defaultsOnly = (material, y, context) -> { mapped.incrementAndGet(); return state(material, y); };
      WriteResult<String> noMapper = writer.write(target, CONTEXT, available(), plan(flow("default-mapper", 3, 2, 4)), defaultsOnly);
      assertInvalid(noMapper, "exact flow-state support");
      assertEquals(4, mapped.get(), "terrain maps first, then default mapFeature rejects the first exact-flow placement");
      assertEquals(1, target.capabilityCalls); assertEquals(0, target.reads); assertEquals(0, target.snapshots);
      assertRestored(initial, target); assertTrue(target.writes.isEmpty());

      MemoryTarget conflict = seeded(); Before conflictBefore = before(conflict);
      FeaturePlacement protectedCell = FeaturePlacement.canonical(FeatureKind.CUSTOM, "protected-cell", COORDINATE,
         PlacementOperation.SET, PlacementConflictPolicy.REPLACE_TERRAIN, SemanticMaterial.MOSS, 1, 12, 1, 1, Map.of());
      WriteResult<String> conflicted = writer.write(conflict, CONTEXT, available(),
         plan(flow("flow-before-conflict", 3, 2, 4), protectedCell), new FlowMapper(conflict));
      assertInvalid(conflicted, "REPLACE_TERRAIN"); assertRestored(conflictBefore, conflict);
      assertEquals(7, conflict.reads, "all original cells are read before effective-state conflict evaluation");
      assertEquals(0, conflict.snapshots); assertTrue(conflict.writes.isEmpty()); assertEquals(0, conflict.marks);
   }

   @Test void blockWriteThatMutatesThenThrowsRestoresTheThrowingCellAndAllPriorBlocksBeforeMetadata() {
      MemoryTarget target = seeded(); Before initial = before(target);
      target.failAfterMutation = cell(4);
      WriteResult<String> result = writer.write(target, CONTEXT, available(), plan(flow("flow", 3, 2, 8)), new FlowMapper(target));

      assertInvalid(result, "restored"); assertRestored(initial, target);
      assertEquals(forwardAndReverse(allCells()), target.writes.stream().map(Attempt::cell).toList());
      assertEquals(flowState(8, 4), target.writes.get(5).state());
      assertEquals(initial.states().get(cell(4)), target.writes.get(6).state(), "throwing mutated cell is restored first");
      assertEquals(0, target.marks); assertEquals(1, target.snapshots); assertEquals(1, target.restores);
      assertEquals("restore", target.events.get(target.events.size() - 1)); assertEquals(0, target.dirtyCalls);
   }

   @Test void markerFailureAfterAppendRestoresEveryBlockAndTheExactOriginalMarkerList() {
      MemoryTarget target = seeded(); Before initial = before(target);
      target.failMarkerAfterAppend = true;
      WriteResult<String> result = writer.write(target, CONTEXT, available(), plan(flow("flow", 3, 2, 5)), new FlowMapper(target));

      assertInvalid(result, "marker failure"); assertRestored(initial, target);
      assertEquals(1, target.marks); assertEquals(1, target.restores); assertEquals(0, target.dirtyCalls);
      assertEquals(forwardAndReverse(allCells()), target.writes.stream().map(Attempt::cell).toList());
      assertEquals("restore", target.events.get(target.events.size() - 1));
      assertTrue(target.events.indexOf("mark") < target.events.lastIndexOf("write"));
   }

   @Test void markerNoOpFailsEvenWhenTheCellAlreadyHasDuplicateMarkers() {
      MemoryTarget target = seeded(); Before initial = before(target);
      target.noOpMarker = true;
      assertEquals(2, Collections.frequency(target.markers, cell(3)));
      WriteResult<String> result = writer.write(target, CONTEXT, available(), plan(flow("flow", 3, 2, 3)), new FlowMapper(target));

      assertInvalid(result, "append exactly one"); assertRestored(initial, target);
      assertEquals(1, target.marks); assertEquals(1, target.restores); assertEquals(0, target.dirtyCalls);
      assertEquals(forwardAndReverse(allCells()), target.writes.stream().map(Attempt::cell).toList());
   }

   @Test void dirtySetterFailureAfterMutationRestoresBothInitiallyCleanAndInitiallyDirtyMetadata() {
      for (boolean initiallyDirty : List.of(false, true)) {
         MemoryTarget target = seeded(); target.dirty = initiallyDirty; target.failDirtyAfterMutation = true;
         Before initial = before(target);
         WriteResult<String> result = writer.write(target, CONTEXT, available(), plan(flow("flow", 3, 2, 6)), new FlowMapper(target));

         assertInvalid(result, "dirty failure"); assertRestored(initial, target);
         assertEquals(2, target.marks); assertEquals(1, target.dirtyCalls); assertEquals(1, target.restores);
         assertEquals(forwardAndReverse(allCells()), target.writes.stream().map(Attempt::cell).toList());
         assertEquals("restore", target.events.get(target.events.size() - 1));
      }
   }

   @Test void acceptedFinalPlacementsDetermineFlowFlagsMarkersAndDuplicateCollapse() {
      MemoryTarget ordinary = seeded(); List<Cell> originalMarkers = List.copyOf(ordinary.markers);
      WriteResult<String> cleared = writer.write(ordinary, CONTEXT, available(), plan(flow("first-flow", 3, 1, 2), ordinaryStone(3)), new FlowMapper(ordinary));
      assertEquals(WriteStatus.WRITTEN, cleared.status()); assertFalse(cleared.transientPostProcessing());
      assertEquals("STONE@3", ordinary.states.get(cell(3))); assertEquals(originalMarkers, ordinary.markers);
      assertEquals(1, ordinary.capabilityCalls, "declared flow still requires capability before any mapping");
      assertEquals(0, ordinary.snapshots); assertEquals(0, ordinary.marks); assertEquals(5, ordinary.writes.size());
      assertEquals(1, ordinary.writes.stream().filter(attempt -> attempt.cell().equals(cell(3))).count());

      MemoryTarget duplicate = seeded();
      WriteResult<String> winner = writer.write(duplicate, CONTEXT, available(),
         plan(flow("earlier-level", 3, 2, 2), flow("winning-level", 3, 1, 8)), new FlowMapper(duplicate));
      assertEquals(WriteStatus.WRITTEN, winner.status()); assertTrue(winner.transientPostProcessing());
      assertEquals(flowState(8, 3), duplicate.states.get(cell(3))); assertEquals(flowState(2, 4), duplicate.states.get(cell(4)));
      assertEquals(6, duplicate.writes.size()); assertEquals(2, duplicate.marks);
      assertEquals(3, Collections.frequency(duplicate.markers, cell(3)), "two planned flow writes add only one final marker");
      assertEquals(1, Collections.frequency(duplicate.markers, cell(4)));
      assertFalse(duplicate.writes.stream().anyMatch(attempt -> attempt.state().equals(flowState(2, 3))));
   }

   @Test void unknownAndQueuedColumnsOrPlansDoNotTouchAnyTargetOrMapper() {
      AtomicInteger mappings = new AtomicInteger();
      SemanticMapper<String> bomb = (material, y, context) -> { mappings.incrementAndGet(); throw new AssertionError("terminal path mapped"); };
      for (DecisionStatus status : List.of(DecisionStatus.UNKNOWN, DecisionStatus.QUEUED)) {
         WriteStatus expected = status == DecisionStatus.UNKNOWN ? WriteStatus.NO_WRITE_UNKNOWN : WriteStatus.NO_WRITE_QUEUED;
         WriteResult<String> columnResult = writer.write(null, CONTEXT, ColumnDecision.unavailable(COORDINATE, status, "terminal"),
            plan(flow("flow", 3, 2, 4)), bomb);
         FeaturePlan terminal = status == DecisionStatus.UNKNOWN ? FeaturePlan.unknown("terminal") : FeaturePlan.queued("terminal");
         WriteResult<String> planResult = writer.write(null, CONTEXT, available(), terminal, bomb);
         for (WriteResult<String> result : List.of(columnResult, planResult)) {
            assertEquals(expected, result.status()); assertTrue(result.mappedWrites().isEmpty());
            assertEquals(Optional.of(identity()), result.persistenceIdentity()); assertFalse(result.transientPostProcessing());
         }
      }
      assertEquals(0, mappings.get());
   }

   @Test void exactFlowConstructorsEnforceLevelsCanonicalWaterAndLegacySinkRejection() {
      for (int level : new int[]{Integer.MIN_VALUE, -1, 0, 9, Integer.MAX_VALUE}) {
         assertThrows(IllegalArgumentException.class, () -> new WaterFlowState(level));
         assertThrows(IllegalArgumentException.class, () -> flow("invalid-level", 3, 1, level));
      }
      for (int level = 1; level <= 8; level++) assertEquals(level, new WaterFlowState(level).level());
      assertThrows(IllegalArgumentException.class, () -> flow("empty-height", 3, 0, 1));
      assertThrows(IllegalArgumentException.class, () -> rawFlow(SemanticMaterial.STONE, GeneratorOwner.EARTH_PROJECTED,
         GeneratorOwner.EARTH_PROJECTED, PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, false));
      assertThrows(IllegalArgumentException.class, () -> rawFlow(SemanticMaterial.WATER, GeneratorOwner.EARTH_PROJECTED,
         GeneratorOwner.SPHERE_FACE, PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, false));
      assertThrows(IllegalArgumentException.class, () -> rawFlow(SemanticMaterial.WATER, GeneratorOwner.CUBE_NET_APRON,
         GeneratorOwner.CUBE_NET_APRON, PlacementOperation.FILL, PlacementConflictPolicy.OWNER_ONLY, false));
      assertThrows(IllegalArgumentException.class, () -> rawFlow(SemanticMaterial.WATER, GeneratorOwner.CUBE_NET_APRON,
         GeneratorOwner.CUBE_NET_CANONICAL, PlacementOperation.MIRROR, PlacementConflictPolicy.OWNER_ONLY, true));
      assertThrows(IllegalArgumentException.class, () -> rawFlow(SemanticMaterial.WATER, GeneratorOwner.EARTH_PROJECTED,
         GeneratorOwner.EARTH_PROJECTED, PlacementOperation.REPLACE_AIR, PlacementConflictPolicy.OWNER_ONLY, false));
      for (PlacementConflictPolicy policy : List.of(PlacementConflictPolicy.PRESERVE_EXISTING, PlacementConflictPolicy.REPLACE_TERRAIN)) {
         assertThrows(IllegalArgumentException.class, () -> rawFlow(SemanticMaterial.WATER, GeneratorOwner.EARTH_PROJECTED,
            GeneratorOwner.EARTH_PROJECTED, PlacementOperation.SET, policy, false));
      }
      assertThrows(IllegalArgumentException.class, () -> FeaturePlacement.canonicalFlow(apronCoordinate(), "apron", 3, 1, 1));
      assertThrows(NullPointerException.class, () -> new FeaturePlacement(FeatureKind.CUSTOM, "null-property", GeneratorOwner.EARTH_PROJECTED,
         GeneratorOwner.EARTH_PROJECTED, PlacementOperation.FILL, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.WATER,
         0, 3, 0, 1, false, Map.of(), null));
      FeaturePlacement placement = flow("strict-flow", 3, 2, 7);
      assertTrue(FeaturePlacementSinkGuard.validatePlacement(CONTEXT, placement).contains("post-processing"));
      assertNull(FeaturePlacementSinkGuard.validatePlacement(CONTEXT, placement, true));
      FeaturePlacement compatibility = new FeaturePlacement(FeatureKind.CUSTOM, "old-constructor", GeneratorOwner.EARTH_PROJECTED,
         GeneratorOwner.EARTH_PROJECTED, PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.STONE,
         0, 3, 0, 1, false, Map.of());
      assertTrue(compatibility.flowState().isEmpty()); assertNull(FeaturePlacementSinkGuard.validatePlacement(CONTEXT, compatibility));
   }

   @Test void transientFlowFlagSurvivesIdentityAndIsRejectedByBlockOnlyStoragePreparation() {
      MemoryTarget flowing = seeded();
      WriteResult<String> result = writer.write(flowing, CONTEXT, available(), plan(flow("flow", 3, 2, 4)), new FlowMapper(flowing));
      WriteResult<String> identified = result.withPersistenceIdentity(identity());
      assertTrue(identified.transientPostProcessing()); assertEquals(result.mappedWrites(), identified.mappedWrites());
      assertEquals(Optional.of(identity()), identified.persistenceIdentity());
      StorageTransaction<String> rejected = new StorageTransactionBoundary().prepare(CONTEXT, identified, StorageCommitPolicy.canonicalDefault(), "flow-op");
      assertEquals(StorageTransactionStatus.NO_COMMIT_FALLBACK, rejected.status()); assertFalse(rejected.commitEligible());
      assertTrue(rejected.writes().isEmpty()); assertTrue(rejected.diagnostics().get(0).contains("post-processing"));

      MemoryTarget ordinary = seeded();
      WriteResult<String> plain = writer.write(ordinary, CONTEXT, available(), FeaturePlan.empty("no properties"), new FlowMapper(ordinary));
      assertFalse(plain.withPersistenceIdentity(identity()).transientPostProcessing());
      StorageTransaction<String> prepared = new StorageTransactionBoundary().prepare(CONTEXT, plain, StorageCommitPolicy.canonicalDefault(), "ordinary-op");
      assertEquals(StorageTransactionStatus.PREPARED, prepared.status()); assertTrue(prepared.commitEligible()); assertEquals(plain.mappedWrites(), prepared.writes());
   }

   @Test void metadataRestoreFailureStillLeavesEveryBlockRestoreAttemptedAndReportsItsCause() {
      MemoryTarget target = seeded(); Before initial = before(target);
      target.failMarkerAfterAppend = true; target.failMetadataRestore = true;
      IllegalStateException error = assertThrows(IllegalStateException.class,
         () -> writer.write(target, CONTEXT, available(), plan(flow("flow", 3, 2, 4)), new FlowMapper(target)));
      assertTrue(error.getMessage().contains("rollback incomplete")); assertSame(target.markerFailure, error.getCause());
      assertEquals(1, error.getSuppressed().length); assertSame(target.metadataFailure, error.getSuppressed()[0]);
      assertEquals(initial.states(), target.states, "metadata restore failure must not strand any restorable block");
      assertEquals(forwardAndReverse(allCells()), target.writes.stream().map(Attempt::cell).toList());
      assertEquals(1, target.restores); assertEquals("restore", target.events.get(target.events.size() - 1));
      assertEquals(initial.markers().size() + 1, target.markers.size(), "injected metadata restore really failed after append");

      MemoryTarget combined = seeded(); Before combinedInitial = before(combined);
      combined.failMarkerAfterAppend = true; combined.failMetadataRestore = true;
      combined.rollbackFailures = Set.of(cell(4), cell(3));
      IllegalStateException aggregated = assertThrows(IllegalStateException.class,
         () -> writer.write(combined, CONTEXT, available(), plan(flow("combined-failures", 3, 2, 4)), new FlowMapper(combined)));
      assertSame(combined.markerFailure, aggregated.getCause());
      assertEquals(1, aggregated.getSuppressed().length); assertSame(combined.blockRestoreFailure, aggregated.getSuppressed()[0]);
      assertEquals(1, combined.blockRestoreFailure.getSuppressed().length);
      assertSame(combined.metadataFailure, combined.blockRestoreFailure.getSuppressed()[0], "distinct restore errors aggregate without self-suppression");
      assertEquals(forwardAndReverse(allCells()), combined.writes.stream().map(Attempt::cell).toList());
      for (int y = -2; y <= 1; y++) assertEquals(combinedInitial.states().get(cell(y)), combined.states.get(cell(y)));
      assertEquals(1, combined.restores); assertEquals("restore", combined.events.get(combined.events.size() - 1));
   }

   @Test void blockRestoreFailureStillRestoresMetadataDirtyStateAndAllOtherBlocks() {
      MemoryTarget target = seeded(); Before initial = before(target);
      target.failMarkerAfterAppend = true; target.rollbackFailures = Set.of(cell(3));
      IllegalStateException error = assertThrows(IllegalStateException.class,
         () -> writer.write(target, CONTEXT, available(), plan(flow("flow", 3, 2, 4)), new FlowMapper(target)));
      assertTrue(error.getMessage().contains("rollback incomplete")); assertSame(target.markerFailure, error.getCause());
      assertEquals(1, error.getSuppressed().length); assertSame(target.blockRestoreFailure, error.getSuppressed()[0]);
      assertEquals(forwardAndReverse(allCells()), target.writes.stream().map(Attempt::cell).toList());
      for (Cell cell : initial.states().keySet()) if (!cell.equals(cell(3))) assertEquals(initial.states().get(cell), target.states.get(cell));
      assertEquals(flowState(4, 3), target.states.get(cell(3)), "the failed block restore remains observable");
      assertEquals(initial.markers(), target.markers); assertEquals(initial.dirty(), target.dirty);
      assertEquals(1, target.restores); assertEquals("restore", target.events.get(target.events.size() - 1));
   }

   private static TellusCoordinateContext earthCoordinate() {
      SphereWorldModel.GeoPoint geo = new SphereWorldModel.GeoPoint(0, 0, 0);
      return TellusCoordinateContextFactory.fromGeo(MODEL, geo, new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo, 1.0)));
   }
   private static ColumnDecision available() {
      return new ColumnDecision(COORDINATE, DecisionStatus.AVAILABLE, OptionalInt.of(1),
         new SurfaceDecision(SemanticMaterial.STONE, SemanticMaterial.STONE, 0), WaterDecision.dry(),
         BiomeDecision.defaultBiome(), StrataDecision.stone(-2, 1), List.of("pure flow fixture"));
   }
   private static TellusPersistenceIdentity identity() { return TellusPersistenceIdentity.from(GENERATION.geometry(), COORDINATE); }
   private static Cell cell(int y) { return new Cell(BASE.x(), y, BASE.z()); }
   private static Cell unrelated() { return new Cell(BASE.x() + 1, 12, BASE.z() + 1); }
   private static List<Cell> allCells() { return List.of(cell(-2), cell(-1), cell(0), cell(1), cell(3), cell(4)); }
   private static FeaturePlacement flow(String id, int bottom, int height, int level) { return FeaturePlacement.canonicalFlow(COORDINATE, id, bottom, height, level); }
   private static FeaturePlacement ordinaryStone(int y) { return FeaturePlacement.canonical(FeatureKind.CUSTOM, "ordinary-stone", COORDINATE,
      PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.STONE, 0, y, 0, 1, Map.of()); }
   private static FeaturePlacement rawFlow(SemanticMaterial material, GeneratorOwner owner, GeneratorOwner canonical,
                                           PlacementOperation operation, PlacementConflictPolicy policy, boolean apron) {
      return new FeaturePlacement(FeatureKind.CUSTOM, "direct-property", owner, canonical, operation, policy, material,
         0, 3, 0, 1, apron, Map.of(), Optional.of(new WaterFlowState(1)));
   }
   private static FeaturePlan plan(FeaturePlacement... placements) { return new FeaturePlan(FeaturePlanStatus.AVAILABLE, List.of(placements), List.of()); }
   private static String state(SemanticMaterial material, int y) { return material == SemanticMaterial.AIR ? AIR : material.name() + "@" + y; }
   private static String flowState(int level, int y) { return "WATER[level=" + level + "]@" + y; }
   private static List<Cell> forwardAndReverse(List<Cell> forward) {
      List<Cell> result = new ArrayList<>(forward), reverse = new ArrayList<>(forward); Collections.reverse(reverse); result.addAll(reverse); return result;
   }
   private static void assertInvalid(WriteResult<String> result, String detail) {
      assertEquals(WriteStatus.NO_WRITE_INVALID, result.status()); assertTrue(result.mappedWrites().isEmpty());
      assertEquals(Optional.of(identity()), result.persistenceIdentity()); assertFalse(result.transientPostProcessing());
      assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains(detail)), result.diagnostics().toString());
   }
   private static MemoryTarget seeded() {
      MemoryTarget target = new MemoryTarget();
      for (Cell cell : allCells()) target.states.put(cell, "original@" + cell.y());
      target.states.put(unrelated(), "protected unrelated");
      target.markers.addAll(List.of(unrelated(), cell(3), cell(3))); return target;
   }
   private record Before(Map<Cell, String> states, List<Cell> markers, boolean dirty) { }
   private static Before before(MemoryTarget target) { return new Before(Map.copyOf(target.states), List.copyOf(target.markers), target.dirty); }
   private static void assertRestored(Before before, MemoryTarget target) {
      assertEquals(before.states(), target.states); assertEquals(before.markers(), target.markers); assertEquals(before.dirty(), target.dirty);
   }
   private static TellusCoordinateContext apronCoordinate() {
      CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
      CubeNetApronResolver resolver = new CubeNetApronResolver(layout, 64); double middle = layout.faceSize() * 0.5;
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(face, side)) continue;
         var placement = layout.placement(face);
         var interior = switch (side) {
            case WEST -> new CubeNetLayout.FacePoint(face, 0.5, middle); case EAST -> new CubeNetLayout.FacePoint(face, layout.faceSize() - 0.5, middle);
            case NORTH -> new CubeNetLayout.FacePoint(face, middle, 0.5); case SOUTH -> new CubeNetLayout.FacePoint(face, middle, layout.faceSize() - 0.5);
         };
         var point = layout.toPlanar(interior);
         double dx = switch (side) { case WEST -> -placement.uAxisX(); case EAST -> placement.uAxisX(); case NORTH -> -placement.vAxisX(); case SOUTH -> placement.vAxisX(); };
         double dz = switch (side) { case WEST -> -placement.uAxisZ(); case EAST -> placement.uAxisZ(); case NORTH -> -placement.vAxisZ(); case SOUTH -> placement.vAxisZ(); };
         var candidate = TellusCoordinateContextFactory.fromCubeNetPlanar(MODEL, layout, resolver, point.x() + dx * 24, point.z() + dz * 24);
         if (candidate.isPresent() && candidate.orElseThrow().owner() instanceof CubeNetApronOwnerView) return candidate.orElseThrow();
      }
      throw new AssertionError("bounded native face/side search must find an apron fixture");
   }
   private record Attempt(Cell cell, String state) { }
   private record Metadata(List<Cell> markers, boolean dirty) { }
   private static final class FlowMapper implements SemanticMapper<String> {
      final MemoryTarget target; int materialCalls, featureCalls;
      FlowMapper(MemoryTarget target) { this.target = target; }
      public String map(SemanticMaterial material, int y, WriteContext context) {
         mappingPhase(); materialCalls++; return state(material, y);
      }
      public String mapFeature(FeaturePlacement feature, int y, WriteContext context) {
         mappingPhase(); featureCalls++;
         return feature.flowState().map(flow -> flowState(flow.level(), y)).orElseGet(() -> map(feature.material(), y, context));
      }
      private void mappingPhase() {
         assertEquals(0, target.reads); assertTrue(target.writes.isEmpty()); assertEquals(0, target.snapshots); target.events.add("map");
      }
   }
   private static final class MemoryTarget implements TellusCompositeColumnWriter.Target<String>, TellusCompositeColumnWriter.PostProcessing {
      final Map<Cell, String> states = new LinkedHashMap<>(); final List<Cell> markers = new ArrayList<>();
      final List<Attempt> writes = new ArrayList<>(); final List<String> events = new ArrayList<>();
      final RuntimeException blockFailure = new IllegalStateException("mutated block failure");
      final RuntimeException markerFailure = new IllegalStateException("appended marker failure");
      final RuntimeException dirtyFailure = new IllegalStateException("mutated dirty failure");
      final RuntimeException metadataFailure = new IllegalStateException("metadata restore sentinel");
      final RuntimeException blockRestoreFailure = new IllegalStateException("block restore sentinel");
      int capabilityCalls, snapshots, marks, restores, reads, dirtyCalls;
      boolean dirty, primaryFailed, failMarkerAfterAppend, noOpMarker, failDirtyAfterMutation, failMetadataRestore;
      Cell failAfterMutation; Set<Cell> rollbackFailures = Set.of();
      public int chunkX() { return Math.floorDiv(BASE.x(), 16); }
      public int chunkZ() { return Math.floorDiv(BASE.z(), 16); }
      public int minY() { return GENERATION.minY(); }
      public int maxYExclusive() { return GENERATION.maxYExclusive(); }
      public String read(Cell cell) { reads++; events.add("read"); return states.getOrDefault(cell, AIR); }
      public void write(Cell cell, String state) {
         writes.add(new Attempt(cell, state)); events.add("write");
         if (primaryFailed && rollbackFailures.contains(cell)) throw blockRestoreFailure;
         if (AIR.equals(state)) states.remove(cell); else states.put(cell, state); dirty = true;
         if (!primaryFailed && cell.equals(failAfterMutation)) { primaryFailed = true; throw blockFailure; }
      }
      public boolean isAir(String state) { return AIR.equals(state); }
      public void markDirty() {
         dirtyCalls++; dirty = true; events.add("dirty");
         if (failDirtyAfterMutation) { primaryFailed = true; throw dirtyFailure; }
      }
      public TellusCompositeColumnWriter.PostProcessing postProcessing() { capabilityCalls++; events.add("capability"); return this; }
      public Object snapshot() {
         snapshots++; events.add("snapshot");
         assertTrue(writes.isEmpty()); assertEquals(6, reads, "all unique originals must be read before metadata snapshot");
         assertEquals(0, marks); return new Metadata(List.copyOf(markers), dirty);
      }
      public void mark(Cell cell) {
         marks++; events.add("mark");
         if (!noOpMarker) { markers.add(cell); dirty = true; }
         if (failMarkerAfterAppend) { primaryFailed = true; throw markerFailure; }
      }
      public int occurrences(Cell cell) { return Collections.frequency(markers, cell); }
      public void restore(Object snapshot) {
         restores++; events.add("restore");
         if (failMetadataRestore) throw metadataFailure;
         Metadata metadata = (Metadata)snapshot; markers.clear(); markers.addAll(metadata.markers()); dirty = metadata.dirty();
      }
   }
}
