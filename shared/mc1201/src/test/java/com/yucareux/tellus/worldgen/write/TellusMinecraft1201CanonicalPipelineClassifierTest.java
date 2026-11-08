package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.decision.TellusColumnDecisionEngine;
import com.yucareux.tellus.worldgen.decision.TellusColumnPlan;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusClimateSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvider;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/** No-registry contract for the mc1201 explicit Earth classifier factory. */
class TellusMinecraft1201CanonicalPipelineClassifierTest {
   private static final double SCALE = 1_000.0;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
      "tellus:mc1201-classifier", "v1", "projection", "noise", SCALE);
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
      21L, SCALE, 64, -64, 320, GEOMETRY);
   private static final SphereWorldModel.GeoPoint GEO = new SphereWorldModel.GeoPoint(47.6, -122.3, 0.0);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromGeo(
      MODEL, GEO, new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(GEO, SCALE)));

   @Test
   void defaultPipelineKeepsPlainsAndExplicitFactoryResolvesBWhCover60Desert() {
      TellusSampleProvider provider = provider();
      SemanticMapper<BlockState> mapper = (material, y, context) -> null;
      TellusMinecraft1201CanonicalPipeline legacy = new TellusMinecraft1201CanonicalPipeline(
         true, provider, new TellusColumnDecisionEngine(), TellusFeaturePlanner.empty(),
         new TellusColumnMaterializer(), mapper);
      TellusMinecraft1201CanonicalPipeline classified = TellusMinecraft1201CanonicalPipeline.withEarthBiomeClassifier(
         true, provider(), 
         (cover, koppen, x, z, scale) -> cover == 60 && "BWh".equals(koppen) ? "minecraft:desert" : null,
         TellusFeaturePlanner.empty(),
         new TellusColumnMaterializer(), mapper);

      TellusColumnPlan defaultPlan = legacy.plan(GENERATION, COORDINATE, request());
      TellusColumnPlan classifiedPlan = classified.plan(GENERATION, COORDINATE, request());

      assertEquals("minecraft:plains", defaultPlan.decision().orElseThrow().biome().id());
      assertTrue(defaultPlan.decision().orElseThrow().biome().known());
      assertEquals("minecraft:desert", classifiedPlan.decision().orElseThrow().biome().id());
      assertTrue(classifiedPlan.decision().orElseThrow().biome().known());
   }

   private static TellusSampleProvider provider() {
      return new TellusSampleProvider() {
         @Override public String providerId() { return "mc1201-classifier-fixture"; }
         @Override public TellusSample sample(TellusSampleRequest request) {
            return new TellusSample(request, SampleStatus.AVAILABLE,
               new TellusElevationSample(SampleStatus.AVAILABLE, 100.0),
               new TellusCoverSample(SampleStatus.AVAILABLE, 60, 60), TellusWaterSample.dry(),
               TellusSampleProvenance.snapshot("mc1201-classifier-fixture", "v1", true, "complete"),
                Optional.of(TellusClimateSample.available("BWh")));
         }
      };
   }

   private static TellusSampleRequest request() {
      return TellusSampleRequest.fromContext(COORDINATE, SamplingPurpose.BIOME,
         SamplingResolution.exact(SCALE), GEOMETRY, 7L);
   }
}
