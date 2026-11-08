package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.decision.TellusColumnDecisionEngine;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvider;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Focused mc1211 mapper and opt-in pipeline guard contracts. */
class TellusMinecraft1211WriteAdapterTest {
   private static final double SCALE = 1.0;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
      "tellus:mc1211-write", "mc1211-write-v1", "write-projection-v1", "write-noise-v1", SCALE);
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
      42L, SCALE, 64, -64, 320, GEOMETRY);
   private static TellusCoordinateContext canonical;

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      canonical = TellusCoordinateContextFactory.fromCubeNetCanonical(
         MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
         new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));
   }

   @Test
   void mapperMapsKnownMaterial() {
      TellusMinecraft1211SemanticMapper mapper = new TellusMinecraft1211SemanticMapper();
      assertEquals(Blocks.STONE.defaultBlockState(), mapper.map(SemanticMaterial.STONE, 80, null));
   }

   @Test
   void disabledAndUnknownPipelineReturnBeforeTarget() {
      TellusSampleProvider unknownProvider = new TellusSampleProvider() {
         @Override public String providerId() { return "mc1211-fixture"; }
         @Override public com.yucareux.tellus.worldgen.sample.TellusSample sample(TellusSampleRequest request) {
            return com.yucareux.tellus.worldgen.sample.TellusSample.unknown(request, "fixture unknown");
         }
      };
      TellusMinecraft1211CanonicalPipeline disabled = new TellusMinecraft1211CanonicalPipeline(
         false, unknownProvider, new TellusColumnDecisionEngine(), TellusFeaturePlanner.empty(),
         new TellusColumnMaterializer(), new TellusMinecraft1211SemanticMapper());
      assertEquals(WriteStatus.NO_WRITE_INVALID,
         disabled.write(null, null, null, null, null).status());

      TellusMinecraft1211CanonicalPipeline enabled = new TellusMinecraft1211CanonicalPipeline(
         true, unknownProvider, new TellusColumnDecisionEngine(), TellusFeaturePlanner.empty(),
         new TellusColumnMaterializer(), new TellusMinecraft1211SemanticMapper());
      WriteContext context = WriteContext.canonical(canonical, GENERATION, "mc1211-gate");
      TellusSampleRequest request = TellusSampleRequest.fromContext(
         canonical, SamplingPurpose.TERRAIN_COLUMN, SamplingResolution.exact(SCALE), GEOMETRY, 1L);
      assertEquals(WriteStatus.NO_WRITE_UNKNOWN,
         enabled.write(null, context, GENERATION, canonical, request).status());
      assertTrue(enabled.enabled());
   }
}
