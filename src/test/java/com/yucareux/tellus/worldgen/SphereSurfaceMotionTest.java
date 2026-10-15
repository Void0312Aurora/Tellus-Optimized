package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereSurfaceMotion;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SphereSurfaceMotionTest {
   @Test
   void ordinaryFaceStepKeepsSurfaceAndVelocityInOneFrame() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var policy = new SphereSingularityPolicy(model, SphereSingularityPolicy.DEFAULT_CONFIG);
      var motion = new SphereSurfaceMotion(model, policy);
      var result = motion.step(0, 0.0, 0.0, 3.0, -2.0, 0.25, (face, x, z) -> false);

      assertFalse(result.crossedFace());
      assertFalse(result.blocked());
      assertEquals(0, result.destination().face());
      assertEquals(3.0, result.velocity().x());
      assertEquals(-2.0, result.velocity().z());
      assertEquals(0.0, SphereWorldModel.greatCircleDistanceMeters(result.destinationSurface(),
         model.exactSurfacePoint(0, result.destination().x() - motion.topology().minEngineBlock(),
            result.destination().z() - motion.topology().minEngineBlock())), 1.0E-7);
   }

   @Test
   void seamStepUsesTheSameConformalPositionAsTheContinuedChart() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var policy = new SphereSingularityPolicy(model, SphereSingularityPolicy.DEFAULT_CONFIG);
      var motion = new SphereSurfaceMotion(model, policy);
      double max = motion.topology().maxEngineBlock();
      double min = motion.topology().minEngineBlock();
      double rawZ = max - 0.25 + 0.75;
      var result = motion.step(0, 0.0, max - 0.25, 0.0, 0.75, 1.0, (face, x, z) -> false);

      assertTrue(result.crossedFace());
      assertFalse(result.blocked());
      assertTrue(result.frame() != null);
      var expected = model.continuedSurfacePoint(0, 0.0 - min, rawZ - min);
      assertTrue(SphereWorldModel.greatCircleDistanceMeters(expected, result.destinationSurface()) < 1.0E-7);
      assertTrue(result.destination().face() != 0);
   }

   @Test
   void collisionProbeAndCornerQuarantineBothStopTheStep() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var policy = new SphereSingularityPolicy(model, SphereSingularityPolicy.DEFAULT_CONFIG);
      var motion = new SphereSurfaceMotion(model, policy);
      var blocked = motion.step(0, 0.0, 0.0, 1.0, 0.0, 1.0, (face, x, z) -> true);
      assertTrue(blocked.blocked());
      assertEquals(0.0, blocked.velocity().x());

      double nearCorner = motion.topology().minEngineBlock() + 1_000.0;
      var quarantined = motion.step(0, nearCorner, nearCorner, 1.0, 0.0, 1.0, (face, x, z) -> false);
      assertTrue(quarantined.blocked());
   }
}
