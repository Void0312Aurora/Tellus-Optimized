package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Pure decision-kernel contracts: status, determinism, identity and semantics. */
class TellusColumnDecisionContractTest {
    private static final double SCALE = 1_000.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:decision", "decision-v1", "decision-projection-v1", "decision-noise-v1", SCALE);
    private static final TellusGenerationContext CONTEXT = new TellusGenerationContext(
        77L, SCALE, 64, -64, 320, GEOMETRY);
    private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromSphericalBlock(
        MODEL, new SphereWorldModel.SphereBlockAddress(0, 32L, 32L));

    @Test
    void decisionEngineProducesAvailablePartialUnknownAndQueuedStatuses() {
        TellusColumnDecisionEngine engine = new TellusColumnDecisionEngine();

        ColumnDecision available = engine.decide(CONTEXT, COORDINATE,
            availableSample(request(SamplingResolution.exact(SCALE), 1L)));
        assertEquals(DecisionStatus.AVAILABLE, available.status());
        assertTrue(available.terrainY().isPresent());
        assertEquals(SemanticMaterial.STONE, available.surface().top());
        assertEquals(WaterDecision.Kind.NONE, available.water().kind());
        assertTrue(available.biome().known());
        assertTrue(available.strata().known());

        ColumnDecision partial = engine.decide(CONTEXT, COORDINATE,
            partialSample(request(SamplingResolution.preview(SCALE), 2L)));
        assertEquals(DecisionStatus.PARTIAL, partial.status());
        assertTrue(partial.terrainY().isPresent());
        assertEquals(WaterDecision.Kind.UNKNOWN, partial.water().kind());

        TellusSampleRequest unknownRequest = request(SamplingResolution.exact(SCALE), 3L);
        ColumnDecision unknown = engine.decide(CONTEXT, COORDINATE,
            TellusSample.unknown(unknownRequest, "missing elevation"));
        assertEquals(DecisionStatus.UNKNOWN, unknown.status());
        assertTrue(unknown.terrainY().isEmpty());
        assertEquals(SemanticMaterial.UNKNOWN, unknown.surface().top());

        TellusSampleRequest queuedRequest = request(SamplingResolution.nonBlocking(SCALE), 4L);
        ColumnDecision queued = engine.decide(CONTEXT, COORDINATE,
            TellusSample.queued(queuedRequest, "synthetic", "r1", "pending"));
        assertEquals(DecisionStatus.QUEUED, queued.status());
        assertTrue(queued.terrainY().isEmpty());
        assertEquals("sample queued", queued.diagnostics().get(0));
    }

    @Test
    void sameInputsProduceDeterministicEqualDecision() {
        TellusColumnDecisionEngine engine = new TellusColumnDecisionEngine();
        TellusSampleRequest request = request(SamplingResolution.exact(SCALE), 5L);
        TellusSample sample = availableSample(request);

        ColumnDecision first = engine.decide(CONTEXT, COORDINATE, sample);
        ColumnDecision second = engine.decide(CONTEXT, COORDINATE, sample);

        assertEquals(first, second);
        assertEquals(first.coordinate(), COORDINATE);
        assertEquals("deterministic default decision-v1", first.diagnostics().get(0));
    }

    @Test
    void decisionRejectsCoordinateOrGeometryIdentityMismatch() {
        TellusColumnDecisionEngine engine = new TellusColumnDecisionEngine();
        TellusCoordinateContext otherCoordinate = TellusCoordinateContextFactory.fromSphericalBlock(
            MODEL, new SphereWorldModel.SphereBlockAddress(0, 33L, 32L));
        TellusSampleRequest otherRequest = requestFor(
            otherCoordinate, SamplingResolution.exact(SCALE), 6L, GEOMETRY);

        assertThrows(IllegalArgumentException.class, () -> engine.decide(
            CONTEXT, COORDINATE, availableSample(otherRequest)));

        GeometryIdentity otherGeometry = new GeometryIdentity(
            "tellus:decision", "decision-v2", "decision-projection-v2", "decision-noise-v2", SCALE);
        TellusSampleRequest geometryRequest = requestFor(
            COORDINATE, SamplingResolution.exact(SCALE), 7L, otherGeometry);
        assertThrows(IllegalArgumentException.class, () -> engine.decide(
            CONTEXT, COORDINATE, availableSample(geometryRequest)));
    }

    @Test
    void decisionContractsRemainSemanticAndMinecraftFree() {
        Class<?>[] types = {
            DecisionStatus.class,
            SemanticMaterial.class,
            BiomeDecision.class,
            SurfaceDecision.class,
            WaterDecision.class,
            StrataDecision.class,
            ColumnDecision.class,
            TellusGenerationContext.class
        };
        for (Class<?> type : types) {
            assertFalse(type.getName().contains("BlockState"), type.getName());
            for (Field field : type.getDeclaredFields()) {
                assertFalse(field.getType().getName().startsWith("net.minecraft."),
                    type.getName() + "." + field.getName());
            }
            if (type.isRecord()) {
                assertNotNull(type.getRecordComponents());
                assertTrue(Arrays.stream(type.getRecordComponents())
                    .noneMatch(component -> component.getType().getName().startsWith("net.minecraft.")), type.getName());
            }
        }
        assertInstanceOf(SemanticMaterial.class, SurfaceDecision.defaultStone().top());
        assertInstanceOf(SemanticMaterial.class, StrataDecision.stone(-64, 64).layers().get(0).material());
    }

    private static TellusSampleRequest request(SamplingResolution resolution, long seed) {
        return requestFor(COORDINATE, resolution, seed, GEOMETRY);
    }

    private static TellusSampleRequest requestFor(
        TellusCoordinateContext coordinate,
        SamplingResolution resolution,
        long seed,
        GeometryIdentity geometry
    ) {
        return TellusSampleRequest.fromContext(
            coordinate, SamplingPurpose.TERRAIN_COLUMN, resolution, geometry, seed);
    }

    private static TellusSample availableSample(TellusSampleRequest request) {
        return new TellusSample(
            request,
            SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 100.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30),
            TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("decision-fixture", "r1", true, "available"));
    }

    private static TellusSample partialSample(TellusSampleRequest request) {
        return new TellusSample(
            request,
            SampleStatus.PARTIAL,
            TellusElevationSample.partial(100.0),
            TellusCoverSample.partial(),
            TellusWaterSample.partial(),
            TellusSampleProvenance.snapshot("decision-fixture", "r1", true, "partial"));
    }
}
