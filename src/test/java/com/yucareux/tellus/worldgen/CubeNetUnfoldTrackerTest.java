package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetUnfoldTracker;
import com.yucareux.tellus.worldgen.global.CubeNetUnfolding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CubeNetUnfoldTrackerTest {
   private static final int SIZE = 256;
   private final CubeNetLayout layout = CubeNetLayout.standard(SIZE);

   private CubeNetUnfoldTracker tracker() {
      return new CubeNetUnfoldTracker(new CubeNetUnfolding(this.layout, 16));
   }

   private double[] planar(CubeNetLayout.Face face, double u, double v) {
      var p = this.layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(face, u, v));
      return new double[]{p.x(), p.z()};
   }

   @Test
   void walkingInsideAFaceSendsNothingAfterTheFirstUpdate() {
      var tracker = tracker();
      double[] start = planar(CubeNetLayout.Face.POS_X, 100.5, 100.5);
      var first = tracker.observe(start[0], start[1]);
      assertNotNull(first);
      assertNull(first.chartChange());
      assertEquals(13 * CubeNetUnfolding.ENCODED_STRIDE, first.sheets().length);
      for (double u = 20.5; u < 240; u += 37) {
         double[] p = planar(CubeNetLayout.Face.POS_X, u, 200.25);
         assertNull(tracker.observe(p[0], p[1]));
      }
   }

   @Test
   void aDirectNetEdgeChangesTheFaceButNotTheChart() {
      int checked = 0;
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
            if (!this.layout.arePlanarNeighbors(face, side)) continue;
            var tracker = tracker();
            double[] here = planar(face, 128.5, 128.5);
            tracker.observe(here[0], here[1]);
            var target = this.layout.toPlanar(this.layout.mapAcross(face, side, 128.5, 4.5));
            var update = tracker.observe(target.x(), target.z());
            assertNotNull(update, face + " " + side);
            assertEquals(this.layout.seam(face, side).target(), update.frame().physical());
            assertNull(update.chartChange(), face + " " + side);
            checked++;
         }
      }
      assertEquals(10, checked, "five direct edges, each in both directions");
   }

   @Test
   void aSeamRebaseSendsTheRigidChartChange() {
      int checked = 0;
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
            if (this.layout.arePlanarNeighbors(face, side)) continue;
            var tracker = tracker();
            // Walk from the source face into its apron: same physical target face, apron chart.
            double[] source = planar(face, side == CubeNetLayout.Side.WEST || side == CubeNetLayout.Side.EAST ? 128.5 : 100.5,
               side == CubeNetLayout.Side.NORTH || side == CubeNetLayout.Side.SOUTH ? 128.5 : 100.5);
            tracker.observe(source[0], source[1]);
            double u = switch (side) { case WEST -> -8.25; case EAST -> SIZE + 8.25; default -> 100.5; };
            double v = switch (side) { case NORTH -> -8.25; case SOUTH -> SIZE + 8.25; default -> 100.5; };
            double[] apron = planar(face, u, v);
            var entering = tracker.observe(apron[0], apron[1]);
            assertNotNull(entering);
            assertNull(entering.chartChange(), "entering the apron moves no camera");

            // The rebase teleports the camera onto the canonical target face.
            var rebased = this.layout.toPlanar(this.layout.mapAcross(face, side, 100.5, 8.25));
            var update = tracker.observe(rebased.x(), rebased.z());
            assertNotNull(update, face + " " + side);
            assertNotNull(update.chartChange(), face + " " + side);
            int[] c = update.chartChange();
            // The chart change carries the camera from its apron position to the rebased position.
            assertEquals(rebased.x(), c[0] * apron[0] + c[1] * apron[1] + c[4], 1.0E-9);
            assertEquals(rebased.z(), c[2] * apron[0] + c[3] * apron[1] + c[5], 1.0E-9);
            assertNull(tracker.observe(rebased.x(), rebased.z()));
            checked++;
         }
      }
      assertEquals(14, checked);
   }

   @Test
   void vertexTiesKeepTheCurrentDevelopment() {
      var tracker = tracker();
      double[] start = planar(CubeNetLayout.Face.POS_X, 100.5, 100.5);
      var first = tracker.observe(start[0], start[1]);
      assertNull(tracker.observe(257.5, -257.5));
      assertEquals(first.frame(), tracker.published());
      tracker.reset();
      assertArrayEquals(first.sheets(), tracker.observe(start[0], start[1]).sheets());
   }
}
