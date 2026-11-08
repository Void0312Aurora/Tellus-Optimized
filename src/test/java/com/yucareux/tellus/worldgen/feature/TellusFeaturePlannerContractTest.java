package com.yucareux.tellus.worldgen.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.decision.BiomeDecision;
import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.StrataDecision;
import com.yucareux.tellus.worldgen.decision.SurfaceDecision;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.WaterDecision;
import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.ImmutableTellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Pure FeaturePlan/default-planner contracts, including canonical/apron ownership. */
class TellusFeaturePlannerContractTest {
    private static final double SCALE = 1.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:feature", "feature-v1", "feature-projection-v1", "feature-noise-v1", SCALE);
    private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
        9L, SCALE, 64, -64, 320, GEOMETRY);
    private static final DefaultTellusFeaturePlanner PLANNER = new DefaultTellusFeaturePlanner(
        FeatureKind.VEGETATION, "fixture-tree", SemanticMaterial.GRASS);

    @Test
    void plannerProducesAvailableCanonicalPlacementAndDeterministicRepeat() {
        CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
        TellusCoordinateContext coordinate = TellusCoordinateContextFactory.fromCubeNetCanonical(
            MODEL, layout, new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));
        ColumnDecision column = availableColumn(coordinate, 100);
        TellusSample sample = availableSample(coordinate);

        FeaturePlan first = PLANNER.plan(GENERATION, coordinate, column, sample);
        FeaturePlan second = PLANNER.plan(GENERATION, coordinate, column, sample);

        assertEquals(FeaturePlanStatus.AVAILABLE, first.status());
        assertEquals(first, second);
        FeaturePlacement placement = first.placements().get(0);
        assertEquals(GeneratorOwner.CUBE_NET_CANONICAL, placement.owner());
        assertEquals(GeneratorOwner.CUBE_NET_CANONICAL, placement.canonicalOwner());
        assertEquals(PlacementOperation.SET, placement.operation());
        assertEquals(PlacementConflictPolicy.OWNER_ONLY, placement.conflictPolicy());
        assertFalse(placement.apronMirror());
        assertEquals(100, placement.offsetY());
        assertEquals(Map.of(), placement.metadata());
    }

    @Test
    void plannerCreatesCanonicalOwnerApronMirror() {
        ImmutableTellusCoordinateContext apron = findApronContext().orElseThrow(
            () -> new AssertionError("expected apron context"));
        assertTrue(apron.owner() instanceof CubeNetApronOwnerView);
        ColumnDecision column = availableColumn(apron, 72);

        FeaturePlan plan = PLANNER.plan(GENERATION, apron, column, availableSample(apron));

        assertEquals(FeaturePlanStatus.AVAILABLE, plan.status());
        FeaturePlacement placement = plan.placements().get(0);
        assertEquals(GeneratorOwner.CUBE_NET_APRON, placement.owner());
        assertEquals(GeneratorOwner.CUBE_NET_CANONICAL, placement.canonicalOwner());
        assertEquals(PlacementOperation.MIRROR, placement.operation());
        assertEquals(PlacementConflictPolicy.MIRROR_OWNER, placement.conflictPolicy());
        assertTrue(placement.apronMirror());
        assertEquals(Map.of("source", "canonical-owner"), placement.metadata());
    }

    @Test
    void plannerMapsUnknownQueuedAndMissingTerrainToExplicitPlanStatuses() {
        CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
        TellusCoordinateContext coordinate = TellusCoordinateContextFactory.fromCubeNetCanonical(
            MODEL, layout, new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));
        TellusSample availableSample = availableSample(coordinate);

        assertEquals(FeaturePlanStatus.UNKNOWN, PLANNER.plan(
            GENERATION, coordinate,
            ColumnDecision.unavailable(coordinate, DecisionStatus.UNKNOWN, "unknown"), availableSample).status());
        assertEquals(FeaturePlanStatus.QUEUED, PLANNER.plan(
            GENERATION, coordinate,
            ColumnDecision.unavailable(coordinate, DecisionStatus.QUEUED, "queued"), availableSample).status());
        ColumnDecision noTerrain = new ColumnDecision(coordinate, DecisionStatus.PARTIAL,
            OptionalInt.empty(), SurfaceDecision.unknown(), WaterDecision.unknown(),
            BiomeDecision.unknown(), StrataDecision.unknown(), List.of("no terrain"));
        assertEquals(FeaturePlanStatus.EMPTY, PLANNER.plan(
            GENERATION, coordinate, noTerrain, availableSample).status());

        assertEquals(FeaturePlanStatus.EMPTY, FeaturePlan.empty("empty").status());
        assertEquals(FeaturePlanStatus.UNKNOWN, FeaturePlan.unknown("unknown").status());
        assertEquals(FeaturePlanStatus.QUEUED, FeaturePlan.queued("queued").status());
    }

    @Test
    void featurePlansAndPlacementsAreMinecraftApiFreeSemanticValues() {
        Class<?>[] types = {
            FeaturePlan.class, FeaturePlanStatus.class, FeaturePlacement.class,
            FeatureKind.class, PlacementOperation.class, PlacementConflictPolicy.class,
            DefaultTellusFeaturePlanner.class, TellusFeaturePlanner.class
        };
        for (Class<?> type : types) {
            assertFalse(type.getName().startsWith("net.minecraft."), type.getName());
            for (Field field : type.getDeclaredFields()) {
                assertFalse(field.getType().getName().startsWith("net.minecraft."),
                    type.getName() + "." + field.getName());
            }
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(method.getReturnType().getName().startsWith("net.minecraft."),
                    type.getName() + "." + method.getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertFalse(parameter.getName().startsWith("net.minecraft."),
                        type.getName() + "." + method.getName());
                }
            }
        }
        assertNotNull(FeaturePlacement.class.getRecordComponents());
        assertTrue(FeaturePlanStatus.AVAILABLE.hasPlacements());
        assertFalse(FeaturePlanStatus.UNKNOWN.hasPlacements());
    }

    private static ColumnDecision availableColumn(TellusCoordinateContext coordinate, int terrainY) {
        return new ColumnDecision(coordinate, DecisionStatus.AVAILABLE, OptionalInt.of(terrainY),
            SurfaceDecision.defaultStone(), WaterDecision.dry(), BiomeDecision.defaultBiome(),
            StrataDecision.stone(-64, terrainY), List.of("fixture"));
    }

    private static TellusSampleRequest sampleRequest(TellusCoordinateContext coordinate) {
        return TellusSampleRequest.fromContext(
            coordinate, SamplingPurpose.TERRAIN_COLUMN,
            SamplingResolution.exact(SCALE), GEOMETRY, 9L);
    }

    private static TellusSample availableSample(TellusCoordinateContext coordinate) {
        TellusSampleRequest request = sampleRequest(coordinate);
        return new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 36.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30),
            TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("feature-fixture", "r1", true, "available"));
    }

    private static Optional<ImmutableTellusCoordinateContext> findApronContext() {
        CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
        CubeNetApronResolver resolver = new CubeNetApronResolver(layout, 64);
        double edge = layout.faceSize() * 0.5;
        for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
            CubeNetLayout.FacePlacement placement = layout.placement(face);
            for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
                if (layout.arePlanarNeighbors(face, side)) continue;
                CubeNetLayout.FacePoint interior = switch (side) {
                    case WEST -> new CubeNetLayout.FacePoint(face, 0.5, edge);
                    case EAST -> new CubeNetLayout.FacePoint(face, layout.faceSize() - 0.5, edge);
                    case NORTH -> new CubeNetLayout.FacePoint(face, edge, 0.5);
                    case SOUTH -> new CubeNetLayout.FacePoint(face, edge, layout.faceSize() - 0.5);
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
                CubeNetLayout.PlanarPoint proxy = new CubeNetLayout.PlanarPoint(
                    center.x() + outwardX * 24.0, center.z() + outwardZ * 24.0);
                Optional<ImmutableTellusCoordinateContext> candidate =
                    TellusCoordinateContextFactory.fromCubeNetPlanar(
                        MODEL, layout, resolver, proxy.x(), proxy.z());
                if (candidate.isPresent() && candidate.orElseThrow().owner() instanceof CubeNetApronOwnerView) {
                    return candidate;
                }
            }
        }
        return Optional.empty();
    }
}
