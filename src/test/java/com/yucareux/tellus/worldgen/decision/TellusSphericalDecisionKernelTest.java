package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.world.data.geology.Lithology;
import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy;
import com.yucareux.tellus.worldgen.TerrainSlopePolicy;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TellusSphericalDecisionKernelTest {
   private static final double SCALE = 10.0;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromSphericalBlock(
      MODEL, new SphereWorldModel.SphereBlockAddress(0, 32, 48));
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(42, SCALE, 64, -64, 320);

   @Test void exactNativeRoundingUsesIndependentPositiveAndNegativeScalesWithoutClamping() {
      assertEquals(65, TellusSphericalColumnRules.height(0.01, 10, 64, 2, 3));
      assertEquals(63, TellusSphericalColumnRules.height(-0.01, 10, 64, 2, 3));
      assertEquals(67, TellusSphericalColumnRules.height(10.01, 10, 64, 2, 3));
      assertEquals(60, TellusSphericalColumnRules.height(-10.01, 10, 64, 2, 3));
      assertEquals(64, TellusSphericalColumnRules.height(-0.0, 10, 64, 2, 3));
      assertThrows(IllegalArgumentException.class, () -> TellusSphericalColumnRules.height(Double.MAX_VALUE, 1, 0, 2, 1));
      assertThrows(IllegalArgumentException.class, () -> TellusSphericalColumnRules.height(Double.NaN, 10, 64, 1, 1));
   }

   @Test void providerRetainsFullSnapshotAndProductionDecision() {
      var neighborhood = new TerrainSlopePolicy.Neighbourhood(
         new TerrainSlopePolicy.Gradient(20.1, 50.0, 1.0, 0.5), 12.0);
      var geology = new SphereChunkSampler.SurfaceData.GeologyData(Lithology.SILICICLASTIC_SEDIMENTARY, null);
      var raw = new SphereChunkSampler.SurfaceData(20.1, 60, Double.NaN,
         SphereChunkSampler.SurfaceData.WaterKind.NONE, geology, neighborhood);
      AtomicInteger reads = new AtomicInteger();
      var sampler = new SphereChunkSampler(MODEL, (point, resolution) -> {reads.incrementAndGet(); return raw;});
      var provider = TellusSphericalSampleProvider.forSphereFaceProduction(sampler);
      TellusSample sample = provider.sample(request(COORDINATE, SamplingResolution.exact(SCALE), 42));
      assertSame(raw, sample.sphericalSurface().orElseThrow().surface());
      assertSame(geology, sample.sphericalSurface().orElseThrow().surface().geology());
      assertSame(neighborhood, sample.sphericalSurface().orElseThrow().surface().neighbourhood());
      var engine = TellusColumnDecisionEngine.withSphericalProductionRules(TellusSphericalDecisionKernel.Settings.DEFAULT);
      ColumnDecision first = engine.decide(GENERATION, COORDINATE, sample);
      assertEquals(first, engine.decide(GENERATION, COORDINATE, sample));
      assertEquals(1, reads.get());
      assertEquals(DecisionStatus.AVAILABLE, first.status());
      assertEquals(67, first.terrainY().orElseThrow());
      assertEquals(SemanticMaterial.BEDROCK, first.strata().layers().get(0).material());
      assertFalse(first.biome().known());
      assertEquals(CubeNetSurfaceDecisionAdapter.map(sample.sphericalSurface().orElseThrow()
         .surfaceDecision().orElseThrow()), first.surface());
   }

   @Test void missingMetadataAndNonblockingRemainExplicitAndDoNotRead() {
      AtomicInteger reads = new AtomicInteger();
      var sampler = new SphereChunkSampler(MODEL, (point, resolution) -> {reads.incrementAndGet();
         return new SphereChunkSampler.SurfaceData(20.1, 30);});
      var kernel = TellusColumnDecisionEngine.withSphericalProductionRules(TellusSphericalDecisionKernel.Settings.DEFAULT);
      TellusSample rawOnly = TellusSphericalSampleProvider.forSphereFace(sampler)
         .sample(request(COORDINATE, SamplingResolution.exact(SCALE), 42));
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(GENERATION, COORDINATE, rawOnly).status());
      TellusSample queued = TellusSphericalSampleProvider.forSphereFaceProduction(sampler)
         .sample(request(COORDINATE, SamplingResolution.nonBlocking(SCALE), 42));
      assertEquals(DecisionStatus.QUEUED, kernel.decide(GENERATION, COORDINATE, queued).status());
      assertTrue(queued.sphericalSurface().isEmpty());
      assertEquals(1, reads.get());
      assertEquals(SemanticMaterial.STONE, new TellusColumnDecisionEngine()
         .decide(GENERATION, COORDINATE, rawOnly).surface().top());
   }

   @Test void nativeVerticalGuardRejectsInsteadOfClampingAndSeedIdentityIsChecked() {
      var sampler = new SphereChunkSampler(MODEL, (point, resolution) -> new SphereChunkSampler.SurfaceData(-2000, 30));
      var sample = TellusSphericalSampleProvider.forSphereFaceProduction(sampler)
         .sample(request(COORDINATE, SamplingResolution.exact(SCALE), 42));
      var engine = TellusColumnDecisionEngine.withSphericalProductionRules(TellusSphericalDecisionKernel.Settings.DEFAULT);
      assertEquals(DecisionStatus.UNKNOWN, engine.decide(GENERATION, COORDINATE, sample).status());
      assertThrows(IllegalArgumentException.class, () -> engine.decide(
         new TellusGenerationContext(99, SCALE, 64, -64, 320, GENERATION.geometry()), COORDINATE, sample));
   }

   @Test void conflictingRawSnapshotIsRejectedAtSampleBoundary() {
      TellusSampleRequest request = request(COORDINATE, SamplingResolution.exact(SCALE), 42);
      assertThrows(IllegalArgumentException.class, () -> new TellusSample(request, SampleStatus.AVAILABLE,
         new TellusElevationSample(SampleStatus.AVAILABLE, 10),
         new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30), TellusWaterSample.dry(),
         TellusSampleProvenance.snapshot("fixture", "v1", false, "test"), Optional.empty(),
         Optional.of(TellusSphericalSurfaceSnapshot.raw(new SphereChunkSampler.SurfaceData(20, 30)))));
   }

   private static TellusSampleRequest request(TellusCoordinateContext coordinate, SamplingResolution resolution, long seed) {
      return TellusSampleRequest.fromContext(coordinate, SamplingPurpose.TERRAIN_COLUMN, resolution,
         GENERATION.geometry(), seed);
   }
}
