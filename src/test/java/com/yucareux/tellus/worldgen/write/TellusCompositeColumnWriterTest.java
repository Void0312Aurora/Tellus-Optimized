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
import com.yucareux.tellus.worldgen.feature.FeaturePlan;
import com.yucareux.tellus.worldgen.feature.FeaturePlanStatus;
import com.yucareux.tellus.worldgen.feature.PlacementConflictPolicy;
import com.yucareux.tellus.worldgen.feature.PlacementOperation;
import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
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
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure terrain/feature transaction tests; no Minecraft storage, timers or source IO. */
class TellusCompositeColumnWriterTest {
    private static final String AIR = "AIR";
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1.0);
    private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromSphericalBlock(
        MODEL, new SphereWorldModel.SphereBlockAddress(0, 32, 48));
    private static final TellusGenerationContext GENERATION = new TellusGenerationContext(19L, 1.0, 0, -2, 10);
    private static final WriteContext CONTEXT = WriteContext.canonical(COORDINATE, GENERATION, "composite-test");
    private static final CanonicalColumnPosition BASE = CanonicalColumnPosition.resolve(CONTEXT, MODEL);
    private static final SemanticMapper<String> MAPPER = (material, y, context) -> state(material, y);
    private final TellusColumnMaterializer materializer = new TellusColumnMaterializer();
    private final TellusCompositeColumnWriter<String> writer = new TellusCompositeColumnWriter<>(materializer, MODEL);

    @Test
    void terrainAndFeaturesAreMappedTogetherBeforeTargetAccessAndCommittedOnce() {
        MemoryTarget target = new MemoryTarget();
        FeaturePlan features = plan(
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 1, 3, 0, 1),
            feature(PlacementOperation.FILL, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.SAND, 0, 4, 1, 2));
        List<String> mapped = new ArrayList<>();

        WriteResult<String> result = writer.write(target, CONTEXT, available(), features, (material, y, context) -> {
            assertSame(CONTEXT, context);
            assertEquals(0, target.reads);
            assertUntouched(target, Map.of());
            String value = state(material, y);
            mapped.add(value);
            return value;
        });

        assertEquals(WriteStatus.WRITTEN, result.status());
        assertIdentity(CONTEXT, result);
        assertEquals(List.of("STONE@-2", "STONE@-1", "STONE@0", "GRASS@1",
            "MOSS@3", "SAND@4", "SAND@5"), mapped);
        assertEquals(mapped, result.mappedWrites());
        assertEquals(List.of(cell(0, -2, 0), cell(0, -1, 0), cell(0, 0, 0), cell(0, 1, 0),
            cell(1, 3, 0), cell(0, 4, 1), cell(0, 5, 1)), target.writes.stream().map(Attempt::cell).toList());
        assertEquals(mapped, target.writes.stream().map(Attempt::state).toList());
        assertEquals(7, target.states.size());
        assertEquals(1, target.dirtyCalls);
    }

    @Test
    void lateFeatureMapperFailureLeavesTerrainAndFeaturesCompletelyUntouched() {
        MemoryTarget target = seededTarget();
        Map<Cell, String> before = Map.copyOf(target.states);
        List<String> mapped = new ArrayList<>();
        FeaturePlan features = plan(
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 1, 3, 0, 1),
            feature(PlacementOperation.FILL, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.SAND, 0, 4, 1, 2));

        WriteResult<String> result = writer.write(target, CONTEXT, available(), features, (material, y, context) -> {
            mapped.add(state(material, y));
            if (material == SemanticMaterial.SAND && y == 5) throw new IllegalStateException("late feature mapping");
            return state(material, y);
        });

        assertInvalid(result, "late feature mapping");
        assertEquals(7, mapped.size());
        assertIdentity(CONTEXT, result);
        assertEquals(0, target.reads);
        assertUntouched(target, before);
    }

    @Test
    void featureWriteThrowingAfterMutationRestoresFailedCellEarlierFeaturesAndTerrain() {
        MemoryTarget target = seededTarget();
        Map<Cell, String> before = Map.copyOf(target.states);
        Cell firstFeature = cell(1, 3, 0);
        Cell failedFeature = cell(1, 4, 0);
        target.failAfterMutation = failedFeature;
        FeaturePlan features = twoFeatures();

        WriteResult<String> result = writer.write(target, CONTEXT, available(), features, MAPPER);

        assertInvalid(result, "restored");
        assertIdentity(CONTEXT, result);
        assertEquals(before, target.states);
        assertEquals(0, target.dirtyCalls);
        List<Cell> forward = new ArrayList<>(terrainCells());
        forward.add(firstFeature);
        forward.add(failedFeature);
        assertEquals(forwardAndReverse(forward), target.writes.stream().map(Attempt::cell).toList());
        assertEquals("SAND@4", target.writes.get(5).state());
        assertEquals(before.get(failedFeature), target.writes.get(6).state());
        assertEquals(before.get(firstFeature), target.writes.get(7).state());
    }

    @Test
    void repeatedSameRollbackExceptionStillAttemptsAllEarlierCellsAndReportsIncompleteRollback() {
        MemoryTarget target = seededTarget();
        Map<Cell, String> before = Map.copyOf(target.states);
        Cell firstFeature = cell(1, 3, 0);
        Cell failedFeature = cell(1, 4, 0);
        target.failAfterMutation = failedFeature;
        target.rollbackFailures = Set.of(failedFeature, firstFeature);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> writer.write(target, CONTEXT, available(), twoFeatures(), MAPPER));

        assertTrue(error.getMessage().contains("rollback incomplete"));
        assertSame(target.failure, error.getCause());
        assertEquals(1, error.getSuppressed().length);
        assertSame(target.failure, error.getSuppressed()[0]);
        assertEquals(0, target.failure.getSuppressed().length, "same exception must not suppress itself");
        List<Cell> forward = new ArrayList<>(terrainCells());
        forward.add(firstFeature);
        forward.add(failedFeature);
        assertEquals(forwardAndReverse(forward), target.writes.stream().map(Attempt::cell).toList());
        for (Cell cell : terrainCells()) assertEquals(before.get(cell), target.states.get(cell));
        assertEquals("MOSS@3", target.states.get(firstFeature));
        assertEquals("SAND@4", target.states.get(failedFeature));
        assertEquals(0, target.dirtyCalls);
    }

    @Test
    void replaceAirAndPreserveExistingUseTerrainAndPreviousFeatureEffectiveStates() {
        for (boolean replaceAir : List.of(true, false)) {
            MemoryTarget target = new MemoryTarget();
            target.states.put(cell(0, 0, 0), "protected terrain original");
            target.states.put(cell(1, 4, 0), "protected cleared by prior feature");
            target.states.put(cell(1, 5, 0), "protected outside terrain");
            PlacementOperation operation = replaceAir ? PlacementOperation.REPLACE_AIR : PlacementOperation.SET;
            PlacementConflictPolicy policy = replaceAir
                ? PlacementConflictPolicy.OWNER_ONLY : PlacementConflictPolicy.PRESERVE_EXISTING;
            FeaturePlan features = plan(
                feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.AIR, 0, 0, 0, 1),
                feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.STONE, 1, 3, 0, 1),
                feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.AIR, 1, 4, 0, 1),
                feature(operation, policy, SemanticMaterial.MOSS, 0, 1, 0, 1),
                feature(operation, policy, SemanticMaterial.MOSS, 0, 0, 0, 1),
                feature(operation, policy, SemanticMaterial.MOSS, 1, 3, 0, 1),
                feature(operation, policy, SemanticMaterial.MOSS, 1, 4, 0, 1),
                feature(operation, policy, SemanticMaterial.MOSS, 1, 5, 0, 1),
                feature(operation, policy, SemanticMaterial.MOSS, 1, 6, 0, 1));

            WriteResult<String> result = writer.write(target, CONTEXT, available(), features, MAPPER);

            assertEquals(WriteStatus.WRITTEN, result.status(), operation + "/" + policy);
            assertIdentity(CONTEXT, result);
            assertEquals("GRASS@1", target.states.get(cell(0, 1, 0)), "planned terrain protects original air");
            assertEquals("MOSS@0", target.states.get(cell(0, 0, 0)), "prior feature can clear planned terrain");
            assertEquals("STONE@3", target.states.get(cell(1, 3, 0)), "prior feature protects original air");
            assertEquals("MOSS@4", target.states.get(cell(1, 4, 0)), "prior air permits replacement of original nonair");
            assertEquals("protected outside terrain", target.states.get(cell(1, 5, 0)));
            assertEquals("MOSS@6", target.states.get(cell(1, 6, 0)));
            assertTrue(target.writes.stream().noneMatch(write -> write.cell().equals(cell(1, 5, 0))));
            assertEquals(7, result.mappedWrites().size());
            assertEquals(1, target.dirtyCalls);
        }
    }

    @Test
    void replaceAirAndPreserveExistingAlsoObserveAirWrittenByTerrain() {
        for (boolean replaceAir : List.of(true, false)) {
            MemoryTarget target = new MemoryTarget();
            target.states.put(cell(0, 1, 0), "protected original top");
            ColumnDecision airTop = decision(COORDINATE, DecisionStatus.AVAILABLE,
                new SurfaceDecision(SemanticMaterial.AIR, SemanticMaterial.STONE, 0),
                WaterDecision.dry(), StrataDecision.stone(-2, 1));
            FeaturePlacement replacement = feature(replaceAir ? PlacementOperation.REPLACE_AIR : PlacementOperation.SET,
                replaceAir ? PlacementConflictPolicy.OWNER_ONLY : PlacementConflictPolicy.PRESERVE_EXISTING,
                SemanticMaterial.MOSS, 0, 1, 0, 1);

            WriteResult<String> result = writer.write(target, CONTEXT, airTop, plan(replacement), MAPPER);

            assertEquals(WriteStatus.WRITTEN, result.status());
            assertIdentity(CONTEXT, result);
            assertEquals("MOSS@1", target.states.get(cell(0, 1, 0)));
        }
    }

    @Test
    void replaceTerrainAllowsPlannedTerrainAndOriginalAirOutsideTerrain() {
        MemoryTarget target = seededTarget();
        WriteResult<String> result = writer.write(target, CONTEXT, available(), plan(
            feature(PlacementOperation.SET, PlacementConflictPolicy.REPLACE_TERRAIN, SemanticMaterial.MOSS, 0, 1, 0, 1),
            feature(PlacementOperation.SET, PlacementConflictPolicy.REPLACE_TERRAIN, SemanticMaterial.SAND, 1, 6, 0, 1)), MAPPER);

        assertEquals(WriteStatus.WRITTEN, result.status());
        assertIdentity(CONTEXT, result);
        assertEquals("MOSS@1", target.states.get(cell(0, 1, 0)));
        assertEquals("SAND@6", target.states.get(cell(1, 6, 0)));
        assertEquals(5, target.writes.size());
        assertEquals(1, target.dirtyCalls);
    }

    @Test
    void replaceTerrainRejectsProtectedOriginalAndEarlierFeatureNonairBeforeAnyWrite() {
        for (int scenario = 0; scenario < 3; scenario++) {
            MemoryTarget target = seededTarget();
            int x = scenario == 1 ? 0 : 1;
            int y = scenario == 1 ? 1 : 3;
            List<FeaturePlacement> placements = new ArrayList<>();
            if (scenario > 0) placements.add(feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY,
                SemanticMaterial.MOSS, x, y, 0, 1));
            placements.add(feature(PlacementOperation.SET, PlacementConflictPolicy.REPLACE_TERRAIN,
                SemanticMaterial.SAND, x, y, 0, 1));
            Map<Cell, String> before = Map.copyOf(target.states);

            WriteResult<String> result = writer.write(target, CONTEXT, available(),
                new FeaturePlan(FeaturePlanStatus.AVAILABLE, placements, List.of()), MAPPER);

            assertInvalid(result, "REPLACE_TERRAIN");
            assertIdentity(CONTEXT, result);
            assertUntouched(target, before);
        }
    }

    @Test
    void overlappingSetAndFillHaveDeterministicLastStateAndRestoreEachOriginalOnce() {
        FeaturePlan features = plan(
            feature(PlacementOperation.FILL, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.SAND, 0, 0, 0, 4),
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.WATER, 0, 2, 0, 1),
            feature(PlacementOperation.FILL, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRAVEL, 0, 1, 0, 3));
        List<Cell> positions = List.of(cell(0, -2, 0), cell(0, -1, 0), cell(0, 0, 0),
            cell(0, 1, 0), cell(0, 2, 0), cell(0, 3, 0));
        List<String> expected = List.of("STONE@-2", "STONE@-1", "SAND@0",
            "GRAVEL@1", "GRAVEL@2", "GRAVEL@3");
        WriteResult<String> first = null;
        for (int run = 0; run < 3; run++) {
            MemoryTarget target = seededTarget();
            Map<Cell, String> before = Map.copyOf(target.states);
            if (run == 2) target.failAfterMutation = cell(0, 3, 0);

            WriteResult<String> result = writer.write(target, CONTEXT, available(), features, MAPPER);

            assertIdentity(CONTEXT, result);
            if (run < 2) {
                assertEquals(WriteStatus.WRITTEN, result.status());
                assertEquals(expected, result.mappedWrites());
                assertEquals(positions, target.writes.stream().map(Attempt::cell).toList());
                assertEquals(expected, positions.stream().map(target.states::get).toList());
                assertEquals(1, target.dirtyCalls);
                if (first == null) first = result;
                else assertEquals(first, result);
            } else {
                assertInvalid(result, "restored");
                assertEquals(before, target.states);
                assertEquals(forwardAndReverse(positions), target.writes.stream().map(Attempt::cell).toList());
                assertEquals(expected, target.writes.subList(0, positions.size()).stream().map(Attempt::state).toList());
                assertEquals(0, target.dirtyCalls);
            }
        }
    }

    @Test
    void unknownAndQueuedDecisionsAndFeaturePlansKeepStatusIdentityAndTargetUntouched() {
        for (boolean unavailableDecision : List.of(true, false)) {
            for (DecisionStatus status : List.of(DecisionStatus.UNKNOWN, DecisionStatus.QUEUED)) {
                MemoryTarget target = seededTarget();
                Map<Cell, String> before = Map.copyOf(target.states);
                ColumnDecision decision = unavailableDecision
                    ? ColumnDecision.unavailable(COORDINATE, status, "fixture unavailable") : available();
                FeaturePlan features = unavailableDecision ? twoFeatures() : status == DecisionStatus.UNKNOWN
                    ? FeaturePlan.unknown("fixture unknown") : FeaturePlan.queued("fixture queued");

                WriteResult<String> result = writer.write(target, CONTEXT, decision, features, unexpectedMapper());

                assertEquals(status == DecisionStatus.UNKNOWN ? WriteStatus.NO_WRITE_UNKNOWN : WriteStatus.NO_WRITE_QUEUED,
                    result.status());
                assertTrue(result.mappedWrites().isEmpty());
                assertEquals(0, target.reads);
                assertUntouched(target, before);
                assertIdentity(CONTEXT, result);
            }
        }
    }

    @Test
    void partialKnownLayersAndNoWritePolicyPreserveMaterializerStatusAndIdentity() {
        ColumnDecision partial = decision(COORDINATE, DecisionStatus.PARTIAL,
            new SurfaceDecision(SemanticMaterial.GRASS, SemanticMaterial.UNKNOWN, 0),
            WaterDecision.unknown(), StrataDecision.unknown());
        for (PartialWritePolicy policy : List.of(PartialWritePolicy.KNOWN_LAYERS_ONLY, PartialWritePolicy.NO_WRITE)) {
            WriteContext context = WriteContext.canonical(COORDINATE, GENERATION, "composite-partial", policy);
            MemoryTarget target = new MemoryTarget();
            WriteResult<SemanticColumnCell> terrain = materializer.materializeCells(context, partial);

            WriteResult<String> result = writer.write(target, context, partial, twoFeatures(), MAPPER);

            assertEquals(terrain.status(), result.status());
            assertEquals(terrain.persistenceIdentity(), result.persistenceIdentity());
            assertIdentity(context, result);
            if (policy == PartialWritePolicy.NO_WRITE) {
                assertEquals(WriteStatus.NO_WRITE_PARTIAL, result.status());
                assertTrue(result.mappedWrites().isEmpty());
                assertEquals(0, target.reads);
                assertUntouched(target, Map.of());
            } else {
                assertEquals(WriteStatus.PARTIAL, result.status());
                assertEquals(List.of("GRASS@1", "MOSS@3", "SAND@4"), result.mappedWrites());
                assertEquals(1, target.dirtyCalls);
            }
        }
    }

    @Test
    void emptyFeaturePlanStillWritesTerrainWithItsStatusAndIdentity() {
        MemoryTarget target = new MemoryTarget();
        WriteResult<String> result = writer.write(target, CONTEXT, available(), FeaturePlan.empty("no features"), MAPPER);

        assertEquals(WriteStatus.WRITTEN, result.status());
        assertIdentity(CONTEXT, result);
        assertEquals(List.of("STONE@-2", "STONE@-1", "STONE@0", "GRASS@1"), result.mappedWrites());
        assertEquals(terrainCells(), target.writes.stream().map(Attempt::cell).toList());
        assertEquals(1, target.dirtyCalls);
    }

    @Test
    void unknownMaterialMirrorOperationAndWrongOwnerAreRejectedBeforeMappingOrTargetReads() {
        FeaturePlacement wrongOwner = new FeaturePlacement(FeatureKind.VEGETATION, "wrong-owner",
            GeneratorOwner.CUBE_NET_CANONICAL, GeneratorOwner.CUBE_NET_CANONICAL, PlacementOperation.SET,
            PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 0, 3, 0, 1, false, Map.of());
        for (FeaturePlacement placement : List.of(wrongOwner,
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.UNKNOWN, 0, 3, 0, 1),
            feature(PlacementOperation.MIRROR, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 0, 3, 0, 1),
            feature(PlacementOperation.SET, PlacementConflictPolicy.MIRROR_OWNER, SemanticMaterial.MOSS, 0, 3, 0, 1))) {
            MemoryTarget target = seededTarget();
            Map<Cell, String> before = Map.copyOf(target.states);

            WriteResult<String> result = writer.write(target, CONTEXT, available(), plan(placement), unexpectedMapper());

            assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
            assertTrue(result.mappedWrites().isEmpty());
            assertIdentity(CONTEXT, result);
            assertEquals(0, target.reads);
            assertUntouched(target, before);
        }
    }

    @Test
    void contextCoordinateAndExplicitModelMismatchLeaveTargetUntouched() {
        TellusCoordinateContext other = TellusCoordinateContextFactory.fromSphericalBlock(
            MODEL, new SphereWorldModel.SphereBlockAddress(0, 33, 48));
        for (boolean wrongDecision : List.of(true, false)) {
            MemoryTarget target = seededTarget();
            Map<Cell, String> before = Map.copyOf(target.states);
            TellusCompositeColumnWriter<String> selected = wrongDecision ? writer
                : new TellusCompositeColumnWriter<>(materializer, SphereWorldModel.forWorldScale(2.0));
            ColumnDecision decision = wrongDecision ? decision(other, DecisionStatus.AVAILABLE,
                new SurfaceDecision(SemanticMaterial.GRASS, SemanticMaterial.STONE, 0),
                WaterDecision.dry(), StrataDecision.stone(-2, 1)) : available();

            WriteResult<String> result = selected.write(target, CONTEXT, decision, twoFeatures(), unexpectedMapper());

            assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
            assertIdentity(CONTEXT, result);
            assertEquals(0, target.reads);
            assertUntouched(target, before);
        }
    }

    @Test
    void oversizedFeatureIsRejectedByBudgetWithoutExpandingOrTouchingTarget() {
        MemoryTarget target = seededTarget();
        Map<Cell, String> before = Map.copyOf(target.states);
        FeaturePlacement oversized = feature(PlacementOperation.FILL, PlacementConflictPolicy.OWNER_ONLY,
            SemanticMaterial.MOSS, 0, 3, 0, 1_048_576);

        WriteResult<String> result = writer.write(target, CONTEXT, available(), plan(oversized), unexpectedMapper());

        assertInvalid(result, "budget");
        assertIdentity(CONTEXT, result);
        assertEquals(0, target.reads);
        assertUntouched(target, before);
    }

    @Test
    void generationChunkAndTargetBuildBoundsAreCheckedBeforeMappingOrReadingAnyCell() {
        for (FeaturePlacement placement : List.of(
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 0, -3, 0, 1),
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 0, 10, 0, 1),
            feature(PlacementOperation.FILL, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 0, 9, 0, 2),
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 16, 3, 0, 1),
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 0, 3, 16, 1))) {
            MemoryTarget target = seededTarget();
            Map<Cell, String> before = Map.copyOf(target.states);

            WriteResult<String> result = writer.write(target, CONTEXT, available(), plan(placement), unexpectedMapper());

            assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
            assertIdentity(CONTEXT, result);
            assertEquals(0, target.reads);
            assertUntouched(target, before);
        }
        for (MemoryTarget target : List.of(
            new MemoryTarget(Math.floorDiv(BASE.x(), 16) + 1, Math.floorDiv(BASE.z(), 16), -2, 10),
            new MemoryTarget(Math.floorDiv(BASE.x(), 16), Math.floorDiv(BASE.z(), 16) + 1, -2, 10),
            new MemoryTarget(Math.floorDiv(BASE.x(), 16), Math.floorDiv(BASE.z(), 16), -1, 10),
            new MemoryTarget(Math.floorDiv(BASE.x(), 16), Math.floorDiv(BASE.z(), 16), -2, 4))) {
            WriteResult<String> result = writer.write(target, CONTEXT, available(), twoFeatures(), unexpectedMapper());

            assertInvalid(result, "bounds");
            assertIdentity(CONTEXT, result);
            assertEquals(0, target.reads);
            assertUntouched(target, Map.of());
        }
    }

    @Test
    void realApronContextIsRejectedWithoutMappingOrTargetAccess() {
        TellusCoordinateContext apron = apronCoordinate();
        CubeNetApronOwnerView owner = (CubeNetApronOwnerView) apron.owner();
        TellusCoordinateContext canonical = TellusCoordinateContextFactory.fromCubeNetCanonical(MODEL,
            CubeNetLayout.standard(MODEL.config().faceBlocks()), owner.canonicalOwner().facePoint());
        WriteContext context = WriteContext.apronMirror(apron, canonical, GENERATION, "composite-apron");
        MemoryTarget target = seededTarget();
        Map<Cell, String> before = Map.copyOf(target.states);
        ColumnDecision decision = decision(canonical, DecisionStatus.AVAILABLE,
            new SurfaceDecision(SemanticMaterial.GRASS, SemanticMaterial.STONE, 0),
            WaterDecision.dry(), StrataDecision.stone(-2, 1));

        WriteResult<String> result = writer.write(target, context, decision, FeaturePlan.empty("none"), unexpectedMapper());

        assertInvalid(result, "canonical ownership");
        assertEquals(0, target.reads);
        assertUntouched(target, before);
        assertIdentity(context, result);
    }

    private static ColumnDecision available() {
        return decision(COORDINATE, DecisionStatus.AVAILABLE,
            new SurfaceDecision(SemanticMaterial.GRASS, SemanticMaterial.STONE, 0),
            WaterDecision.dry(), StrataDecision.stone(-2, 1));
    }

    private static ColumnDecision decision(TellusCoordinateContext coordinate, DecisionStatus status,
                                           SurfaceDecision surface, WaterDecision water, StrataDecision strata) {
        return new ColumnDecision(coordinate, status, OptionalInt.of(1), surface, water,
            BiomeDecision.defaultBiome(), strata, List.of("composite fixture"));
    }

    private static FeaturePlacement feature(PlacementOperation operation, PlacementConflictPolicy policy,
                                            SemanticMaterial material, int x, int y, int z, int height) {
        return FeaturePlacement.canonical(FeatureKind.VEGETATION, "composite-fixture", COORDINATE,
            operation, policy, material, x, y, z, height, Map.of());
    }

    private static FeaturePlan plan(FeaturePlacement... placements) {
        return new FeaturePlan(FeaturePlanStatus.AVAILABLE, List.of(placements), List.of());
    }

    private static FeaturePlan twoFeatures() {
        return plan(
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.MOSS, 1, 3, 0, 1),
            feature(PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.SAND, 1, 4, 0, 1));
    }

    private static SemanticMapper<String> unexpectedMapper() {
        return (material, y, context) -> {
            fail("guarded batch must not invoke mapper");
            return AIR;
        };
    }

    private static String state(SemanticMaterial material, int y) {
        return material == SemanticMaterial.AIR ? AIR : material.name() + "@" + y;
    }

    private static Cell cell(int x, int y, int z) {
        return new Cell(Math.addExact(BASE.x(), x), y, Math.addExact(BASE.z(), z));
    }

    private static List<Cell> terrainCells() {
        return List.of(cell(0, -2, 0), cell(0, -1, 0), cell(0, 0, 0), cell(0, 1, 0));
    }

    private static MemoryTarget seededTarget() {
        MemoryTarget target = new MemoryTarget();
        for (Cell cell : terrainCells()) target.states.put(cell, "original terrain@" + cell.y());
        target.states.put(cell(1, 3, 0), "original first feature");
        target.states.put(cell(1, 4, 0), "original second feature");
        target.states.put(cell(7, 8, 7), "unrelated protected cell");
        return target;
    }

    private static List<Cell> forwardAndReverse(List<Cell> forward) {
        List<Cell> result = new ArrayList<>(forward);
        List<Cell> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        result.addAll(reverse);
        return result;
    }

    private static void assertInvalid(WriteResult<String> result, String diagnostic) {
        assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
        assertTrue(result.mappedWrites().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(text -> text.contains(diagnostic)), result.diagnostics().toString());
    }

    private static void assertIdentity(WriteContext context, WriteResult<String> result) {
        assertEquals(java.util.Optional.of(TellusPersistenceIdentity.from(
            context.generation().geometry(), context.targetCoordinate())), result.persistenceIdentity());
    }

    private static void assertUntouched(MemoryTarget target, Map<Cell, String> before) {
        assertEquals(before, target.states);
        assertTrue(target.writes.isEmpty(), "no target write calls, including writes later restored");
        assertEquals(0, target.dirtyCalls);
    }

    private static TellusCoordinateContext apronCoordinate() {
        CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
        CubeNetApronResolver resolver = new CubeNetApronResolver(layout, 64);
        double middle = layout.faceSize() * 0.5;
        for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
            CubeNetLayout.FacePlacement placement = layout.placement(face);
            for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
                if (layout.arePlanarNeighbors(face, side)) continue;
                CubeNetLayout.FacePoint interior = switch (side) {
                    case WEST -> new CubeNetLayout.FacePoint(face, 0.5, middle);
                    case EAST -> new CubeNetLayout.FacePoint(face, layout.faceSize() - 0.5, middle);
                    case NORTH -> new CubeNetLayout.FacePoint(face, middle, 0.5);
                    case SOUTH -> new CubeNetLayout.FacePoint(face, middle, layout.faceSize() - 0.5);
                };
                CubeNetLayout.PlanarPoint center = layout.toPlanar(interior);
                double outwardX = switch (side) {
                    case WEST -> -placement.uAxisX();
                    case EAST -> placement.uAxisX();
                    case NORTH -> -placement.vAxisX();
                    case SOUTH -> placement.vAxisX();
                };
                double outwardZ = switch (side) {
                    case WEST -> -placement.uAxisZ();
                    case EAST -> placement.uAxisZ();
                    case NORTH -> -placement.vAxisZ();
                    case SOUTH -> placement.vAxisZ();
                };
                var candidate = TellusCoordinateContextFactory.fromCubeNetPlanar(MODEL, layout, resolver,
                    center.x() + outwardX * 24.0, center.z() + outwardZ * 24.0);
                if (candidate.isPresent() && candidate.orElseThrow().owner() instanceof CubeNetApronOwnerView) {
                    return candidate.orElseThrow();
                }
            }
        }
        throw new AssertionError("bounded face/side fixture must contain an apron");
    }

    private record Attempt(Cell cell, String state) { }

    private static final class MemoryTarget implements TellusCompositeColumnWriter.Target<String> {
        private final int chunkX;
        private final int chunkZ;
        private final int minY;
        private final int maxY;
        private final Map<Cell, String> states = new LinkedHashMap<>();
        private final List<Attempt> writes = new ArrayList<>();
        private final RuntimeException failure = new IllegalStateException("injected target failure");
        private int reads;
        private int dirtyCalls;
        private Cell failAfterMutation;
        private boolean failed;
        private Set<Cell> rollbackFailures = Set.of();

        private MemoryTarget() {
            this(Math.floorDiv(BASE.x(), 16), Math.floorDiv(BASE.z(), 16), GENERATION.minY(), GENERATION.maxYExclusive());
        }

        private MemoryTarget(int chunkX, int chunkZ, int minY, int maxY) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.minY = minY;
            this.maxY = maxY;
        }

        @Override public int chunkX() { return chunkX; }
        @Override public int chunkZ() { return chunkZ; }
        @Override public int minY() { return minY; }
        @Override public int maxYExclusive() { return maxY; }

        @Override public String read(Cell cell) {
            reads++;
            return states.getOrDefault(cell, AIR);
        }

        @Override public void write(Cell cell, String state) {
            writes.add(new Attempt(cell, state));
            if (failed && rollbackFailures.contains(cell)) throw failure;
            if (AIR.equals(state)) states.remove(cell);
            else states.put(cell, state);
            if (!failed && cell.equals(failAfterMutation)) {
                failed = true;
                throw failure;
            }
        }

        @Override public boolean isAir(String state) { return AIR.equals(state); }
        @Override public void markDirty() { dirtyCalls++; }
    }
}
