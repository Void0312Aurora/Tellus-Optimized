package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Pure resolver contracts; these fixtures do not claim native biome-source parity. */
class TellusSphericalBiomeResolverTest {
   private static final double SCALE = 10.0;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromSphericalBlock(
      MODEL, new SphereWorldModel.SphereBlockAddress(0, 32, 48));
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(42, SCALE, 64, -64, 320);
   private static final TellusSphericalDecisionKernel.Settings SETTINGS =
      new TellusSphericalDecisionKernel.Settings(true, 1.7, 2.5);

   @Test void resolverReceivesOriginalInputsOnceAndProposedHeightThroughEngineComposition() {
      TellusSample sample = resolved(GENERATION, COORDINATE, new SphereChunkSampler.SurfaceData(20.1, 30));
      AtomicInteger calls = new AtomicInteger();
      BiomeDecision expected = new BiomeDecision("fixture:resolved-biome", true);
      var kernel = new TellusSphericalDecisionKernel(SETTINGS, (generation, coordinate, supplied, terrainY) -> {
         calls.incrementAndGet();
         assertSame(GENERATION, generation);
         assertSame(COORDINATE, coordinate);
         assertSame(sample, supplied);
         assertEquals(68, terrainY);
         return expected;
      });

      ColumnDecision result = TellusColumnDecisionEngine.withSphericalProductionRules(kernel)
         .decide(GENERATION, COORDINATE, sample);

      assertEquals(1, calls.get());
      assertEquals(DecisionStatus.AVAILABLE, result.status());
      assertEquals(68, result.terrainY().orElseThrow());
      assertSame(expected, result.biome());
      assertEquals(CubeNetSurfaceDecisionAdapter.map(CubeNetSurfacePolicy.Decision.DEFAULT), result.surface());
      assertEquals(SemanticMaterial.BEDROCK, result.strata().layers().get(0).material());
   }

   @Test void legacyConstructorAndExplicitUnknownKeepAnAvailableTerrainDecision() {
      TellusSample sample = resolved(GENERATION, COORDINATE, new SphereChunkSampler.SurfaceData(20.1, 30));
      ColumnDecision legacy = new TellusSphericalDecisionKernel(SETTINGS).decide(GENERATION, COORDINATE, sample);
      AtomicInteger calls = new AtomicInteger();
      ColumnDecision explicit = new TellusSphericalDecisionKernel(SETTINGS, (generation, coordinate, supplied, y) -> {
         calls.incrementAndGet();
         return BiomeDecision.unknown();
      }).decide(GENERATION, COORDINATE, sample);

      assertEquals(DecisionStatus.AVAILABLE, legacy.status());
      assertEquals(DecisionStatus.AVAILABLE, explicit.status());
      assertEquals(BiomeDecision.unknown(), legacy.biome());
      assertEquals(BiomeDecision.unknown(), explicit.biome());
      assertEquals(legacy.terrainY(), explicit.terrainY());
      assertEquals(legacy.surface(), explicit.surface());
      assertEquals(legacy.strata(), explicit.strata());
      assertEquals(1, calls.get());
   }

   @Test void unavailableMissingRawAndNoncanonicalSamplesSkipResolver() {
      AtomicInteger calls = new AtomicInteger();
      var kernel = countingKernel(calls);
      TellusSampleRequest request = request(GENERATION, COORDINATE);
      TellusSampleRequest queuedRequest = TellusSampleRequest.fromContext(COORDINATE,
         SamplingPurpose.TERRAIN_COLUMN, SamplingResolution.nonBlocking(SCALE),
         GENERATION.geometry(), GENERATION.worldSeed());
      assertEquals(DecisionStatus.QUEUED, kernel.decide(GENERATION, COORDINATE,
         TellusSample.queued(queuedRequest, "fixture", "v1", "queued")).status());
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(GENERATION, COORDINATE,
         TellusSample.unknown(request, "unknown")).status());
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(GENERATION, COORDINATE,
         TellusSample.partial(request, "partial")).status());
      var data = new SphereChunkSampler.SurfaceData(20.1, 30);
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(GENERATION, COORDINATE,
         sample(GENERATION, COORDINATE, data, Optional.empty())).status());
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(GENERATION, COORDINATE,
         sample(GENERATION, COORDINATE, data, Optional.of(TellusSphericalSurfaceSnapshot.raw(data)))).status());
      TellusCoordinateContext apron = apronCoordinate();
      assertInstanceOf(CubeNetApronOwnerView.class, apron.owner());
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(GENERATION, apron,
         resolved(GENERATION, apron, data)).status());
      assertEquals(0, calls.get());
   }

   @Test void verticalLimitsOverflowAndColumnBudgetSkipResolver() {
      AtomicInteger calls = new AtomicInteger();
      var kernel = countingKernel(calls);
      for (double metres : new double[]{-2000.0, -1280.0, 2560.0, Double.MAX_VALUE}) {
         assertEquals(DecisionStatus.UNKNOWN, kernel.decide(GENERATION, COORDINATE,
            resolved(GENERATION, COORDINATE, new SphereChunkSampler.SurfaceData(metres, 30))).status(),
            "rejected native height: " + metres);
      }
      var flooded = new SphereChunkSampler.SurfaceData(20.1, 30, 3000.0,
         SphereChunkSampler.SurfaceData.WaterKind.INLAND);
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(GENERATION, COORDINATE,
         resolved(GENERATION, COORDINATE, flooded)).status());
      var overBudget = new TellusGenerationContext(42, SCALE, 64, -64, 1_048_513, GENERATION.geometry());
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(overBudget, COORDINATE,
         resolved(overBudget, COORDINATE, new SphereChunkSampler.SurfaceData(20.1, 30))).status());
      assertEquals(0, calls.get());
   }

   @Test void coordinateGeometryAndSeedIdentityFailuresSkipResolver() {
      AtomicInteger calls = new AtomicInteger();
      var kernel = countingKernel(calls);
      TellusSample sample = resolved(GENERATION, COORDINATE, new SphereChunkSampler.SurfaceData(20.1, 30));
      TellusCoordinateContext other = TellusCoordinateContextFactory.fromSphericalBlock(
         MODEL, new SphereWorldModel.SphereBlockAddress(0, 33, 48));
      assertThrows(IllegalArgumentException.class, () -> kernel.decide(GENERATION, other, sample));
      var otherSeed = new TellusGenerationContext(99, SCALE, 64, -64, 320, GENERATION.geometry());
      assertThrows(IllegalArgumentException.class, () -> kernel.decide(otherSeed, COORDINATE, sample));
      var otherGeometry = new GeometryIdentity("fixture:other", "v2", "other-projection", "other-noise", SCALE);
      var otherProfile = new TellusGenerationContext(42, SCALE, 64, -64, 320, otherGeometry);
      assertThrows(IllegalArgumentException.class, () -> kernel.decide(otherProfile, COORDINATE, sample));
      assertEquals(0, calls.get());
   }

   @Test void nullDependenciesNullResultsAndCallbackFailuresFailFast() {
      TellusSphericalDecisionKernel.BiomeResolver resolver = (generation, coordinate, sample, y) -> BiomeDecision.unknown();
      assertThrows(NullPointerException.class, () -> new TellusSphericalDecisionKernel(null, resolver));
      assertThrows(NullPointerException.class, () -> new TellusSphericalDecisionKernel(SETTINGS, null));
      TellusSample sample = resolved(GENERATION, COORDINATE, new SphereChunkSampler.SurfaceData(20.1, 30));
      AtomicInteger nullCalls = new AtomicInteger();
      var nullKernel = new TellusSphericalDecisionKernel(SETTINGS, (generation, coordinate, supplied, y) -> {
         nullCalls.incrementAndGet();
         return null;
      });
      assertThrows(NullPointerException.class, () -> nullKernel.decide(GENERATION, COORDINATE, sample));
      assertEquals(1, nullCalls.get());
      RuntimeException failure = new IllegalStateException("fixture classifier failure");
      AtomicInteger failureCalls = new AtomicInteger();
      var failingKernel = new TellusSphericalDecisionKernel(SETTINGS, (generation, coordinate, supplied, y) -> {
         failureCalls.incrementAndGet();
         throw failure;
      });
      assertSame(failure, assertThrows(IllegalStateException.class,
         () -> failingKernel.decide(GENERATION, COORDINATE, sample)));
      assertEquals(1, failureCalls.get());
   }

   private static TellusSphericalDecisionKernel countingKernel(AtomicInteger calls) {
      return new TellusSphericalDecisionKernel(TellusSphericalDecisionKernel.Settings.DEFAULT,
         (generation, coordinate, sample, y) -> {
            calls.incrementAndGet();
            return BiomeDecision.defaultBiome();
         });
   }

   private static TellusSample resolved(TellusGenerationContext generation, TellusCoordinateContext coordinate,
                                       SphereChunkSampler.SurfaceData data) {
      return sample(generation, coordinate, data, Optional.of(TellusSphericalSurfaceSnapshot.resolved(
         data, CubeNetSurfacePolicy.Decision.DEFAULT)));
   }

   private static TellusSample sample(TellusGenerationContext generation, TellusCoordinateContext coordinate,
                                     SphereChunkSampler.SurfaceData data,
                                     Optional<TellusSphericalSurfaceSnapshot> snapshot) {
      TellusWaterSample water = data.waterAvailable()
         ? new TellusWaterSample(SampleStatus.AVAILABLE, true, data.oceanWater(), data.waterSurfaceMeters())
         : TellusWaterSample.dry();
      return new TellusSample(request(generation, coordinate), SampleStatus.AVAILABLE,
         new TellusElevationSample(SampleStatus.AVAILABLE, data.elevationMeters()),
         new TellusCoverSample(SampleStatus.AVAILABLE, data.coverClass(), data.coverClass()), water,
         TellusSampleProvenance.snapshot("fixture:spherical-resolver", "v1", false, "pure resolver contract"),
         Optional.empty(), snapshot);
   }

   private static TellusSampleRequest request(TellusGenerationContext generation, TellusCoordinateContext coordinate) {
      return TellusSampleRequest.fromContext(coordinate, SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.exact(SCALE), generation.geometry(), generation.worldSeed());
   }

   private static TellusCoordinateContext apronCoordinate() {
      CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
      CubeNetApronResolver resolver = new CubeNetApronResolver(layout, 80);
      double edge = layout.faceSize() * 0.5;
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         var placement = layout.placement(face);
         for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
            if (layout.arePlanarNeighbors(face, side)) continue;
            var interior = switch (side) {
               case WEST -> new CubeNetLayout.FacePoint(face, 0.5, edge);
               case EAST -> new CubeNetLayout.FacePoint(face, layout.faceSize() - 0.5, edge);
               case NORTH -> new CubeNetLayout.FacePoint(face, edge, 0.5);
               case SOUTH -> new CubeNetLayout.FacePoint(face, edge, layout.faceSize() - 0.5);
            };
            var center = layout.toPlanar(interior);
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
      throw new AssertionError("expected a canonical cut-edge apron fixture");
   }
}
