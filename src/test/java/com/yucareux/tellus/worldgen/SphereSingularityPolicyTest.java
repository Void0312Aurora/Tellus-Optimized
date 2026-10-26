package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SphereSingularityPolicyTest {
   private static final SphereWorldModel MODEL = new SphereWorldModel(
      new SphereWorldModel.Config(1.0, 4096, 16, 3, 0.25));

   @Test
   void discoversEightUniqueConformalCornersAndQuarantinesThem() {
      var policy = new SphereSingularityPolicy(MODEL,
         new SphereSingularityPolicy.Config(300_000.0, 50_000.0));
      assertEquals(8, policy.corners().size());
      for (var corner : policy.corners()) {
         assertEquals(SphereSingularityPolicy.Status.EXCLUDED, policy.status(corner));
      }
   }

   @Test
   void statusesAreMonotonicAcrossTheConfiguredBand() {
      var policy = new SphereSingularityPolicy(MODEL,
         new SphereSingularityPolicy.Config(300_000.0, 50_000.0));
      var corner = policy.corners().get(0);
      var center = new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0);
      assertEquals(SphereSingularityPolicy.Status.EXCLUDED, policy.status(corner));
      assertTrue(policy.classify(corner).conservativeDistanceMeters() <= 300_000.0);
      assertEquals(SphereSingularityPolicy.Status.ORDINARY, policy.status(center));
      assertTrue(policy.classify(center).conservativeDistanceMeters() > 350_000.0);
      assertEquals(0.004434, policy.idealExcludedSurfaceFraction(), 0.00001);
      assertEquals(0.006031, policy.idealQuarantinedSurfaceFraction(), 0.00001);
   }

   @Test
   void chunkAdmissionIsConservativeAndFailsClosed() {
      var policy = new SphereSingularityPolicy(MODEL,
         new SphereSingularityPolicy.Config(300_000.0, 50_000.0));
      var cornerChunk = new SphereWorldModel.SphereChunkAddress(0, 0, 0);
      var centerChunk = new SphereWorldModel.SphereChunkAddress(0,
         MODEL.chunksPerFace() / 2, MODEL.chunksPerFace() / 2);
      assertFalse(policy.admitsOrdinaryMaterialization(cornerChunk));
      assertTrue(policy.admitsOrdinaryMaterialization(centerChunk));
      assertThrows(IllegalStateException.class, () -> policy.requireOrdinary(cornerChunk));
   }

   @Test
   void transitionBufferStillAdmitsTerrainButNotPlayerEntry() {
      var policy = new SphereSingularityPolicy(MODEL,
         new SphereSingularityPolicy.Config(0.0, 1_000_000.0));
      var cornerChunk = new SphereWorldModel.SphereChunkAddress(0, 0, 0);
      assertEquals(SphereSingularityPolicy.Status.TRANSITION, policy.status(cornerChunk));
      assertTrue(policy.admitsTerrainMaterialization(cornerChunk));
      assertFalse(policy.admitsOrdinaryMaterialization(cornerChunk));
      assertFalse(policy.admitsOrdinaryEnginePoint(0, -MODEL.config().faceBlocks() * 0.5,
         -MODEL.config().faceBlocks() * 0.5));
   }

   @Test
   void invalidConfigurationIsRejected() {
      assertThrows(IllegalArgumentException.class,
         () -> new SphereSingularityPolicy.Config(-1.0, 0.0));
      assertThrows(IllegalArgumentException.class,
         () -> new SphereSingularityPolicy.Config(0.0, Double.NaN));
   }
}
