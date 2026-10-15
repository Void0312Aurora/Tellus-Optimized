package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubeNetLayoutTest {
   @Test
   void standardNetPlacesAllSixFacesWithoutOverlap() {
      var layout = new CubeNetLayout(32);
      assertEquals(6, java.util.Arrays.stream(CubeNetLayout.Face.values())
         .map(face -> layout.toPlanar(new CubeNetLayout.FacePoint(face, 16.0, 16.0)))
         .distinct().count());
      assertEquals(5, java.util.Arrays.stream(CubeNetLayout.Face.values())
         .flatMap(face -> java.util.Arrays.stream(CubeNetLayout.Side.values())
            .filter(side -> layout.arePlanarNeighbors(face, side)))
         .count() / 2);
   }

   @Test
   void everyPhysicalEdgeHasOneReciprocalSeam() {
      var layout = new CubeNetLayout(48);
      int seams = 0;
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         var seam = layout.seam(face, side);
         if (!layout.arePlanarNeighbors(face, side)) seams++;
         var reverse = layout.seam(seam.target(), seam.targetSide());
         assertEquals(face, reverse.target());
         assertEquals(side, reverse.targetSide());
         assertEquals(seam.reverse(), reverse.reverse());
      }
      assertEquals(14, seams);
   }

   @Test
   void edgeCoordinateRoundTripsAcrossEverySeam() {
      var layout = new CubeNetLayout(64);
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(face, side)) continue;
         for (double edge : new double[]{0.0, 0.25, 17.5, 63.5, 64.0}) {
            var target = layout.mapAcross(face, side, edge, 0.75);
            var reverseSide = layout.seam(face, side).targetSide();
            var source = layout.mapAcross(target.face(), reverseSide,
               sideAlong(target, reverseSide), 0.75);
            assertEquals(face, source.face());
            assertEquals(edge, sideAlong(source, side), 1.0E-9);
         }
      }
   }

   @Test
   void planarInteriorRoundTripsToTheOwningFace() {
      var layout = new CubeNetLayout(64);
      for (var face : CubeNetLayout.Face.values()) {
         var source = new CubeNetLayout.FacePoint(face, 17.25, 42.75);
         var resolved = layout.fromPlanar(layout.toPlanar(source));
         assertEquals(source, resolved);
      }
   }

   @Test
   void directNetEdgesShareTheirPlanarBoundary() {
      var layout = new CubeNetLayout(16);
      assertTrue(layout.arePlanarNeighbors(CubeNetLayout.Face.POS_Y, CubeNetLayout.Side.NORTH));
      assertTrue(layout.arePlanarNeighbors(CubeNetLayout.Face.POS_Y, CubeNetLayout.Side.SOUTH));
      assertTrue(layout.arePlanarNeighbors(CubeNetLayout.Face.POS_Y, CubeNetLayout.Side.WEST));
      assertTrue(layout.arePlanarNeighbors(CubeNetLayout.Face.POS_Y, CubeNetLayout.Side.EAST));
      assertTrue(layout.arePlanarNeighbors(CubeNetLayout.Face.NEG_Z, CubeNetLayout.Side.NORTH));
      assertFalse(layout.arePlanarNeighbors(CubeNetLayout.Face.POS_Z, CubeNetLayout.Side.NORTH));
   }

   @Test
   void cutSeamTransformsTheRemainingStepAndItsInverse() {
      var layout = new CubeNetLayout(64);
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(face, side)) continue;
         double u = side == CubeNetLayout.Side.WEST ? 0.25
            : side == CubeNetLayout.Side.EAST ? 63.75 : 31.5;
         double v = side == CubeNetLayout.Side.NORTH ? 0.25
            : side == CubeNetLayout.Side.SOUTH ? 63.75 : 31.5;
         double du = side == CubeNetLayout.Side.WEST ? -1.0
            : side == CubeNetLayout.Side.EAST ? 1.0 : 0.0;
         double dv = side == CubeNetLayout.Side.NORTH ? -1.0
            : side == CubeNetLayout.Side.SOUTH ? 1.0 : 0.0;
         var transfer = layout.crossSeam(new CubeNetLayout.FacePoint(face, u, v), du, dv);
         assertTrue(transfer.crossed());
         assertEquals(layout.seam(face, side).target(), transfer.destination().face());
         assertTrue(transfer.destination().u() >= 0.0 && transfer.destination().u() <= 64.0);
         assertTrue(transfer.destination().v() >= 0.0 && transfer.destination().v() <= 64.0);
      }
   }

   @Test
   void seamVectorTransportIsReciprocal() {
      var layout = new CubeNetLayout(64);
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         var seam = layout.seam(face, side);
         double[] mapped = layout.transportVector(face, side, 0.35, -0.8);
         double[] restored = layout.transportVector(seam.target(), seam.targetSide(), mapped[0], mapped[1]);
         assertEquals(0.35, restored[0], 1.0E-9);
         assertEquals(-0.8, restored[1], 1.0E-9);
      }
   }

   private static double sideAlong(CubeNetLayout.FacePoint point, CubeNetLayout.Side side) {
      return switch (side) {
         case WEST, EAST -> point.v();
         case NORTH, SOUTH -> point.u();
      };
   }
}
