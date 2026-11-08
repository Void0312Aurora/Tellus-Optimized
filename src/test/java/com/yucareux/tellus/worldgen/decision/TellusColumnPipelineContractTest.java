package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.feature.FeaturePlan;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
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
import com.yucareux.tellus.worldgen.sample.TellusSampleProvider;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Focused contracts for shared plan-only pipeline ordering and terminal status. */
class TellusColumnPipelineContractTest {
    private static final double SCALE = 1_000.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:pipeline-contract", "pipeline-v1", "pipeline-projection-v1", "pipeline-noise-v1", SCALE);
    private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
        91L, SCALE, 64, -64, 320, GEOMETRY);
    private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromSphericalBlock(
        MODEL, new SphereWorldModel.SphereBlockAddress(0, 32L, 32L));

    @Test
    void availableAndPartialPlansCarryOnlyResolvedStages() {
        AtomicInteger featureCalls = new AtomicInteger();
        TellusFeaturePlanner planner = (generation, coordinate, decision, sample) -> {
            featureCalls.incrementAndGet();
            return FeaturePlan.empty("no feature");
        };
        TellusColumnPipeline pipeline = TellusColumnPipeline.compose(
            provider(request -> availableSample(request)), new TellusColumnDecisionEngine(), planner);

        TellusColumnPlan available = pipeline.plan(GENERATION, COORDINATE,
            request(SamplingResolution.exact(SCALE), 1L));
        assertEquals(TellusColumnPlan.Status.AVAILABLE, available.status());
        assertTrue(available.decision().isPresent());
        assertTrue(available.featurePlan().isPresent());

        TellusColumnPipeline partialPipeline = TellusColumnPipeline.compose(
            provider(request -> partialSample(request)), new TellusColumnDecisionEngine(), planner);
        TellusColumnPlan partial = partialPipeline.plan(GENERATION, COORDINATE,
            request(SamplingResolution.preview(SCALE), 2L));
        assertEquals(TellusColumnPlan.Status.PARTIAL, partial.status());
        assertEquals(DecisionStatus.PARTIAL, partial.decision().orElseThrow().status());
        assertEquals(2, featureCalls.get());
    }

    @Test
    void unknownAndQueuedSamplesStopBeforeDecisionOrFeatureValues() {
        AtomicInteger featureCalls = new AtomicInteger();
        TellusFeaturePlanner planner = (generation, coordinate, decision, sample) -> {
            featureCalls.incrementAndGet();
            return FeaturePlan.empty("unexpected");
        };
        TellusColumnPipeline unknownPipeline = TellusColumnPipeline.compose(
            provider(request -> TellusSample.unknown(request, "missing")),
            new TellusColumnDecisionEngine(), planner);
        TellusColumnPlan unknown = unknownPipeline.plan(GENERATION, COORDINATE,
            request(SamplingResolution.exact(SCALE), 3L));
        assertEquals(TellusColumnPlan.Status.UNKNOWN, unknown.status());
        assertTrue(unknown.decision().isEmpty());
        assertTrue(unknown.featurePlan().isEmpty());

        TellusColumnPipeline queuedPipeline = TellusColumnPipeline.compose(
            provider(request -> TellusSample.queued(request, "fixture", "r1", "pending")),
            new TellusColumnDecisionEngine(), planner);
        TellusColumnPlan queued = queuedPipeline.plan(GENERATION, COORDINATE,
            request(SamplingResolution.nonBlocking(SCALE), 4L));
        assertEquals(TellusColumnPlan.Status.QUEUED, queued.status());
        assertTrue(queued.decision().isEmpty());
        assertTrue(queued.featurePlan().isEmpty());
        assertEquals(0, featureCalls.get());
    }

    @Test
    void planContractIsMinecraftFreeAndRejectsCoordinateMismatch() {
        TellusColumnPipeline pipeline = TellusColumnPipeline.compose(
            provider(request -> availableSample(request)), new TellusColumnDecisionEngine(),
            TellusFeaturePlanner.empty());
        TellusCoordinateContext other = TellusCoordinateContextFactory.fromSphericalBlock(
            MODEL, new SphereWorldModel.SphereBlockAddress(0, 33L, 32L));
        assertFalse(TellusColumnPlan.class.getName().startsWith("net.minecraft."));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> pipeline.plan(GENERATION, other, request(SamplingResolution.exact(SCALE), 5L)));
    }

    private static TellusSampleProvider provider(java.util.function.Function<TellusSampleRequest, TellusSample> sampler) {
        return new TellusSampleProvider() {
            @Override public String providerId() { return "pipeline-contract-fixture"; }
            @Override public TellusSample sample(TellusSampleRequest request) { return sampler.apply(request); }
        };
    }

    private static TellusSampleRequest request(SamplingResolution resolution, long seed) {
        return TellusSampleRequest.fromContext(
            COORDINATE, SamplingPurpose.TERRAIN_COLUMN, resolution, GEOMETRY, seed);
    }

    private static TellusSample availableSample(TellusSampleRequest request) {
        return new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 100.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30), TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("pipeline-contract-fixture", "r1", true, "available"));
    }

    private static TellusSample partialSample(TellusSampleRequest request) {
        return new TellusSample(request, SampleStatus.PARTIAL,
            TellusElevationSample.partial(100.0), TellusCoverSample.partial(), TellusWaterSample.partial(),
            TellusSampleProvenance.snapshot("pipeline-contract-fixture", "r1", true, "partial"));
    }
}
