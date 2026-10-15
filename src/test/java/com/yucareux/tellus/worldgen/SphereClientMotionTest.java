package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.client.SphereClientMotion;
import com.yucareux.tellus.client.SphereClientState;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SphereClientMotionTest {
   @AfterEach
   void resetState() {
      SphereClientState.reset();
   }

   @Test
   void previewsOrdinaryFaceMotionFromAuthoritativeFrame() {
      var frame = new SphereClientState.Snapshot(true, "tellus:earth", SphereWorldModel.MAPPING_ID, 0,
         0.0, 2000.0, 0.0, 1.0, 0.0F, 1L);

      var preview = SphereClientMotion.preview(frame, 3.0, -2.0, 0.25);

      assertEquals(SphereClientMotion.Status.SAME_FACE, preview.status());
      assertEquals(0, preview.result().destination().face());
      assertEquals(1L, frame.revision());
   }

   @Test
   void previewsConformalSeamWithoutChangingClientAuthority() {
      var model = SphereWorldModel.forWorldScale(1.0);
      double edge = new com.yucareux.tellus.worldgen.global.SphereFaceTopology(model).maxEngineBlock();
      var frame = new SphereClientState.Snapshot(true, "tellus:earth", SphereWorldModel.MAPPING_ID, 0,
         0.0, 2000.0, edge - 0.25, 1.0, 0.0F, 4L);

      var preview = SphereClientMotion.preview(frame, 0.0, 0.75, 1.0);

      assertEquals(SphereClientMotion.Status.CROSS_FACE, preview.status());
      assertTrue(preview.result().frame() != null);
      assertEquals(4L, frame.revision());
   }

   @Test
   void rejectsUnknownMappingBeforeGeometryEvaluation() {
      var frame = new SphereClientState.Snapshot(true, "tellus:earth", "old-equiangular-v1", 0,
         0.0, 2000.0, 0.0, 1.0, 0.0F, 7L);

      assertEquals(SphereClientMotion.Status.UNKNOWN_MAPPING,
         SphereClientMotion.preview(frame, 1.0, 0.0, 1.0).status());
   }

   @Test
   void blocksLocalPredictionOnlyInsideTheSingularityQuarantine() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var topology = new com.yucareux.tellus.worldgen.global.SphereFaceTopology(model);
      var frame = new SphereClientState.Snapshot(true, "tellus:earth", SphereWorldModel.MAPPING_ID, 0,
         topology.minEngineBlock() + 1_000.0, 2000.0, topology.minEngineBlock() + 1_000.0,
         1.0, 0.0F, 8L);

      assertTrue(SphereClientMotion.shouldBlockLocalMove(frame, 1.0, 0.0, 1.0));
   }

   @Test
   void acceptsAnExternalCollisionProbeForOrdinaryFaceMotion() {
      var frame = new SphereClientState.Snapshot(true, "tellus:earth", SphereWorldModel.MAPPING_ID, 0,
         0.0, 2000.0, 0.0, 1.0, 0.0F, 9L);

      assertEquals(SphereClientMotion.Status.COLLISION_BLOCKED,
         SphereClientMotion.preview(frame, 1.0, 0.0, 1.0, (face, x, z) -> true).status());
      assertTrue(!SphereClientMotion.shouldBlockLocalMove(frame, 1.0, 0.0, 1.0,
         (face, x, z) -> true));
   }
}
