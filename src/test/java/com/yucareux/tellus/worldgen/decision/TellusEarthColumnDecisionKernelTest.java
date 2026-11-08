package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TellusEarthColumnDecisionKernelTest {
   private static final double SCALE = 1000.0;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   private static final GeometryIdentity GEOMETRY = GeometryIdentity.forProvider(
      "tellus:earth", "earth-v2", "earth-projection-v1", "earth-noise-v1", SCALE);
   private static final SphereWorldModel.GeoPoint GEO = new SphereWorldModel.GeoPoint(46.0, 12.0, 0.0);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromGeo(
      MODEL, GEO, new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(GEO, SCALE)));
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(42L, SCALE, 64, -64, 320, GEOMETRY);

   @Test void resolverPaletteIsConvertedToSemanticFillerCountAndUnderwaterTop() {
      EarthSurfacePalette palette = new EarthSurfacePalette(SemanticMaterial.GRASS, SemanticMaterial.SAND,
         SemanticMaterial.DIRT, 4, SurfaceForm.NONE, false, EarthSurfacePalette.Flags.none());
      TellusEarthColumnDecisionKernel kernel = new TellusEarthColumnDecisionKernel((sample, coordinate, terrainY, underwater) -> {
         assertEquals(74, terrainY);
         assertTrue(underwater);
         return palette;
      });
      ColumnDecision decision = TellusColumnDecisionEngine.withEarthProductionRules(kernel)
         .decide(GENERATION, COORDINATE, sample(true), "Cfb");
      assertEquals(74, decision.terrainY().orElseThrow());
      assertEquals(SemanticMaterial.SAND, decision.surface().top());
      assertEquals(SemanticMaterial.DIRT, decision.surface().filler());
      assertEquals(3, decision.surface().depth());
      assertEquals(WaterDecision.Kind.INLAND, decision.water().kind());
   }

   @Test void unknownAndQueuedRemainTerminal() {
      var kernel = new TellusEarthColumnDecisionKernel((sample, coordinate, terrainY, underwater) -> {
         fail("surface resolver must not run"); return null;
      });
      var request = TellusSampleRequest.fromContext(COORDINATE, SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.nonBlocking(SCALE), GEOMETRY, 42L);
      TellusSample queued = TellusSample.queued(request, "fixture", "r1", "pending");
      assertEquals(DecisionStatus.QUEUED, kernel.decide(GENERATION, COORDINATE, queued, null).status());
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(GENERATION, COORDINATE,
         TellusSample.unknown(request, "missing"), null).status());
   }

   @Test void defaultLegacyEngineRemainsStoneAndEarthKernelRequiresEarthOwner() {
      TellusSample sample = sample(false);
      assertEquals(SemanticMaterial.STONE, new TellusColumnDecisionEngine()
         .decide(GENERATION, COORDINATE, sample, "Cfb").surface().top());
      TellusCoordinateContext sphere = TellusCoordinateContextFactory.fromSphericalBlock(MODEL,
         new SphereWorldModel.SphereBlockAddress(0, 1, 1));
      TellusSampleRequest request = TellusSampleRequest.fromContext(sphere, SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.exact(SCALE), GEOMETRY, 42L);
      TellusSample spherical = new TellusSample(request, SampleStatus.AVAILABLE,
         new TellusElevationSample(SampleStatus.AVAILABLE, 10000.0),
         new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30), TellusWaterSample.dry(),
         TellusSampleProvenance.snapshot("test", "r1", true, "available"));
      assertThrows(IllegalArgumentException.class, () -> new TellusEarthColumnDecisionKernel(
         (a, b, c, d) -> new EarthSurfacePalette(SemanticMaterial.GRASS, SemanticMaterial.GRASS,
            SemanticMaterial.DIRT, 1, SurfaceForm.NONE, false, EarthSurfacePalette.Flags.none()))
         .decide(GENERATION, sphere, spherical, null));
   }

   private static TellusSample sample(boolean water) {
      TellusSampleRequest request = TellusSampleRequest.fromContext(COORDINATE, SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.exact(SCALE), GEOMETRY, 42L);
      return new TellusSample(request, SampleStatus.AVAILABLE,
         new TellusElevationSample(SampleStatus.AVAILABLE, 10000.0),
         new TellusCoverSample(SampleStatus.AVAILABLE, 10, 10),
         water ? new TellusWaterSample(SampleStatus.AVAILABLE, true, false, 20000.0) : TellusWaterSample.dry(),
         TellusSampleProvenance.snapshot("test", "r1", true, "available"));
   }
}
