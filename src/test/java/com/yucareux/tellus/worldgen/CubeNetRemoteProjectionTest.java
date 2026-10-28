package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetRemoteProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Seam-aware DH projection must agree with the canonical one-step seam map. */
public class CubeNetRemoteProjectionTest {
   @Test
   void foldsEveryCutSeamWithinTheNativeApron() {
      CubeNetLayout layout = CubeNetLayout.standard(64);
      CubeNetRemoteProjection projection = new CubeNetRemoteProjection(layout, 8);
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
            if (layout.arePlanarNeighbors(face, side)) continue;
            for (double penetration : new double[]{1.0, 7.0}) {
               double edge = 32.5;
               double u = switch (side) {
                  case WEST -> -penetration;
                  case EAST -> layout.faceSize() + penetration;
                  case NORTH, SOUTH -> edge;
               };
               double v = switch (side) {
                  case NORTH -> -penetration;
                  case SOUTH -> layout.faceSize() + penetration;
                  case WEST, EAST -> edge;
               };
               var planar = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(face, u, v));
               var expected = layout.mapAcross(face, side, edge, penetration);
               var actual = projection.resolve(planar.x(), planar.z());
               assertTrue(actual.isPresent(), () -> "unresolved " + face + " " + side + " p=" + penetration);
               assertEquals(expected.face(), actual.get().point().face());
               assertEquals(expected.u(), actual.get().point().u(), 1.0E-7);
               assertEquals(expected.v(), actual.get().point().v(), 1.0E-7);
            }
         }
      }
   }

   @Test
   void preservesNativeApronAddressesAndQuarantine() {
      var layout = new CubeNetLayout(256);
      var nativeApron = new CubeNetApronResolver(layout, 16);
      var remote = new CubeNetRemoteProjection(layout, 16);
      int compared = 0;
      for (int z = -2 * 256 - 16; z < 2 * 256 + 16; z += 8) {
         for (int x = -256 - 16; x < 2 * 256 + 16; x += 8) {
            var expected = nativeApron.resolve(x + 0.5, z + 0.5).orElse(null);
            if (expected == null) continue;
            var actual = remote.resolve(x + 0.5, z + 0.5).orElseThrow();
            assertEquals(expected.targetFace(), actual.point().face());
            assertEquals(expected.blockX(), (int)Math.floor(actual.point().u()));
            assertEquals(expected.blockZ(), (int)Math.floor(actual.point().v()));
            compared++;
         }
      }
      assertTrue(compared > 300);
      // The concave net corner is inside two native cut strips.
      assertTrue(nativeApron.resolve(257.5, -257.5).isEmpty());
      assertTrue(remote.resolve(257.5, -257.5).isEmpty());
   }

   @Test
   void coarseRequestsCrossingACutMustSplit() {
      var layout = new CubeNetLayout(256);
      var remote = new CubeNetRemoteProjection(layout, 16);
      // POS_X south cut at planar Z=0; both sides are individually valid.
      assertTrue(remote.isUniformArea(384, -64, 64));
      assertTrue(remote.isUniformArea(384, 0, 16));
      assertFalse(remote.isUniformArea(384, -32, 64));
      // Do not infer a giant empty tile from a few empty corner samples.
      assertFalse(remote.isUniformArea(-4096, -4096, 8192));
   }

   @Test
   void farSideAndExactOverlapTiesRemainUnsupported() {
      var remote = new CubeNetRemoteProjection(new CubeNetLayout(256), 16);
      assertTrue(remote.resolve(10000.5, 10000.5).isEmpty());
      assertTrue(remote.resolve(300.5, -300.5).isEmpty());
      // A request farther than the native apron must not be folded into a
      // different face merely because it remains within half a face width.
      var outside = new CubeNetLayout(256).toPlanarUnbounded(
         new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, -17.5, 128.5));
      assertTrue(remote.resolve(outside.x(), outside.z()).isEmpty());
   }

   @Test
   void rejectsCubeVerticesInsteadOfChoosingAnArbitraryFace() {
      CubeNetLayout layout = CubeNetLayout.standard(64);
      CubeNetRemoteProjection projection = new CubeNetRemoteProjection(layout);
      var point = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.POS_Y, -3.0, -3.0));
      assertTrue(projection.resolve(point.x(), point.z()).isEmpty());
   }
}
