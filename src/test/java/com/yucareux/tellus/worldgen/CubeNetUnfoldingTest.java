package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetUnfolding;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The render-only development must agree with the one-step seam maps used by gameplay. */
public class CubeNetUnfoldingTest {
   private static final int SIZE = 256;
   private static final int APRON = 16;
   private final CubeNetLayout layout = CubeNetLayout.standard(SIZE);
   private final CubeNetUnfolding unfolding = new CubeNetUnfolding(this.layout, APRON);

   @Test
   void chartTransformsMatchTheSeamMapForEveryDirectedEdge() {
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
            CubeNetUnfolding.Affine transform = this.unfolding.chartTransform(face, side);
            for (double edge : new double[]{0.5, 100.25, SIZE - 0.5}) {
               for (double penetration : new double[]{0.5, 37.0, SIZE - 0.5}) {
                  var expected = this.layout.toPlanarUnbounded(outside(face, side, edge, penetration));
                  var target = this.layout.mapAcross(face, side, edge, penetration);
                  var canonical = this.layout.toPlanar(target);
                  double[] actual = transform.apply(canonical.x(), canonical.z());
                  String label = face + " " + side + " edge=" + edge + " p=" + penetration;
                  assertEquals(expected.x(), actual[0], 1.0E-9, label);
                  assertEquals(expected.z(), actual[1], 1.0E-9, label);
               }
            }
         }
      }
   }

   @Test
   void reverseChartTransformsCancel() {
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
            var seam = this.layout.seam(face, side);
            var there = this.unfolding.chartTransform(face, side);
            var back = this.unfolding.chartTransform(seam.target(), seam.targetSide());
            assertEquals(CubeNetUnfolding.Affine.IDENTITY, back.then(there), face + " " + side);
         }
      }
   }

   @Test
   void placementsTileTheThreeByThreeBlockAroundTheHomeFace() {
      for (CubeNetLayout.Face home : CubeNetLayout.Face.values()) {
         var placements = this.unfolding.around(new CubeNetUnfolding.Frame(home, CubeNetUnfolding.Affine.IDENTITY));
         assertEquals(13, placements.size());
         long[] homeRect = this.unfolding.canonicalRect(home);
         assertRect(homeRect, 0, 0, screenRect(placements.get(0)), home + " home");
         CubeNetLayout.Side[] sides = CubeNetLayout.Side.values();
         for (int i = 0; i < sides.length; i++) {
            var placement = placements.get(1 + i);
            assertNull(placement.portal());
            assertEquals(this.layout.seam(home, sides[i]).target(), placement.face());
            double[] out = outward(home, sides[i]);
            assertRect(homeRect, out[0], out[1], screenRect(placement), home + " " + sides[i]);
         }
         Set<CubeNetLayout.Face> cornerFaces = new HashSet<>();
         for (int corner = 0; corner < 4; corner++) {
            var first = placements.get(5 + corner * 2);
            var second = placements.get(6 + corner * 2);
            assertNotNull(first.portal());
            assertNotNull(second.portal());
            assertNotEquals(first.face(), second.face(), home + " corner " + corner);
            long[] a = screenRect(first), b = screenRect(second);
            assertEquals(a[0], b[0]);
            assertEquals(a[1], b[1]);
            assertEquals(SIZE, a[2]);
            // A corner square is diagonal to home: it touches it only at one vertex.
            long dx = a[0] - homeRect[0], dz = a[1] - homeRect[1];
            assertEquals(SIZE, Math.abs(dx), home + " corner " + corner);
            assertEquals(SIZE, Math.abs(dz), home + " corner " + corner);
            cornerFaces.add(first.face());
            cornerFaces.add(second.face());
         }
         assertEquals(4, cornerFaces.size(), "corner candidates are the four edge neighbours of " + home);
      }
   }

   @Test
   void cornerPointsShowTheFaceReachedThroughTheEdgeTheSightLineLeavesBy() {
      // Corner order and through-side order match CubeNetUnfolding.around.
      CubeNetLayout.Side[][] corners = {
         {CubeNetLayout.Side.NORTH, CubeNetLayout.Side.WEST},
         {CubeNetLayout.Side.NORTH, CubeNetLayout.Side.EAST},
         {CubeNetLayout.Side.SOUTH, CubeNetLayout.Side.WEST},
         {CubeNetLayout.Side.SOUTH, CubeNetLayout.Side.EAST},
      };
      double[] players = {12.5, 128.0, 243.5};
      double[] offsets = {0.07, 0.5, 0.93};
      int checked = 0;
      for (CubeNetLayout.Face home : CubeNetLayout.Face.values()) {
         var placements = this.unfolding.around(new CubeNetUnfolding.Frame(home, CubeNetUnfolding.Affine.IDENTITY));
         for (int corner = 0; corner < 4; corner++) {
            CubeNetLayout.Side vertical = corners[corner][0], horizontal = corners[corner][1];
            var throughVertical = placements.get(5 + corner * 2);
            var throughHorizontal = placements.get(6 + corner * 2);
            assertEquals(this.layout.seam(home, horizontal).target(), throughVertical.face());
            assertEquals(this.layout.seam(home, vertical).target(), throughHorizontal.face());
            for (double pu : players) for (double pv : players) for (double fu : offsets) for (double fv : offsets) {
               // Face-local point in the diagonal square beyond this vertex.
               double qu = horizontal == CubeNetLayout.Side.WEST ? -fu * SIZE : SIZE + fu * SIZE;
               double qv = vertical == CubeNetLayout.Side.NORTH ? -fv * SIZE : SIZE + fv * SIZE;
               double edgeV = vertical == CubeNetLayout.Side.NORTH ? 0.0 : SIZE;
               double t = (edgeV - pv) / (qv - pv);
               double uAtEdge = pu + t * (qu - pu);
               // A sight line through the vertex itself belongs to neither sheet.
               if (Math.abs(uAtEdge) < 1.0E-9 || Math.abs(uAtEdge - SIZE) < 1.0E-9) continue;
               boolean leavesVertically = uAtEdge > 0.0 && uAtEdge < SIZE;
               var player = this.layout.toPlanar(new CubeNetLayout.FacePoint(home, pu, pv));
               var point = this.layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(home, qu, qv));
               String label = home + " corner " + vertical + "/" + horizontal
                  + " player=(" + pu + ", " + pv + ") point=(" + qu + ", " + qv + ")";
               assertEquals(leavesVertically, throughVertical.admits(player.x(), player.z(), point.x(), point.z()), label);
               assertEquals(!leavesVertically, throughHorizontal.admits(player.x(), player.z(), point.x(), point.z()), label);
               checked++;
            }
         }
      }
      assertTrue(checked > 1500, "checked " + checked);
   }

   @Test
   void apronFrameRebasesOntoTheTargetFaceWithoutMovingAnySheet() {
      int checked = 0;
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
            if (this.layout.arePlanarNeighbors(face, side)) continue;
            var seam = this.layout.seam(face, side);
            var apron = this.layout.toPlanarUnbounded(outside(face, side, 100.5, 8.25));
            var before = this.unfolding.frameAt(apron.x(), apron.z());
            assertNotNull(before);
            assertEquals(seam.target(), before.physical());
            assertEquals(this.unfolding.chartTransform(face, side), before.chart());

            var rebased = this.layout.toPlanar(this.layout.mapAcross(face, side, 100.5, 8.25));
            var after = this.unfolding.frameAt(rebased.x(), rebased.z());
            assertNotNull(after);
            assertEquals(seam.target(), after.physical());
            assertEquals(CubeNetUnfolding.Affine.IDENTITY, after.chart());

            CubeNetUnfolding.Affine change = before.chart().inverse().then(after.chart());
            double[] moved = change.apply(apron.x(), apron.z());
            assertEquals(rebased.x(), moved[0], 1.0E-9);
            assertEquals(rebased.z(), moved[1], 1.0E-9);
            assertEquals(Set.copyOf(moved(this.unfolding.around(before), change)),
               Set.copyOf(this.unfolding.around(after)), face + " " + side);
            checked++;
         }
      }
      assertEquals(14, checked, "seven cut edges, each in both directions");
   }

   @Test
   void canonicalFramesAreIdentityAndVertexTiesAreUnresolved() {
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         var point = this.layout.toPlanar(new CubeNetLayout.FacePoint(face, 40.5, 200.5));
         var frame = this.unfolding.frameAt(point.x(), point.z());
         assertNotNull(frame);
         assertEquals(face, frame.physical());
         assertEquals(CubeNetUnfolding.Affine.IDENTITY, frame.chart());
      }
      // The concave net corner beside POS_X lies in two native cut strips.
      assertNull(this.unfolding.frameAt(257.5, -257.5));
   }

   @Test
   void encodingCarriesRectTransformAndPortal() {
      var placements = this.unfolding.around(new CubeNetUnfolding.Frame(CubeNetLayout.Face.POS_X,
         CubeNetUnfolding.Affine.IDENTITY));
      int[] encoded = this.unfolding.encode(placements);
      assertEquals(placements.size() * CubeNetUnfolding.ENCODED_STRIDE, encoded.length);
      for (int i = 0; i < placements.size(); i++) {
         var placement = placements.get(i);
         int base = i * CubeNetUnfolding.ENCODED_STRIDE;
         long[] rect = this.unfolding.canonicalRect(placement.face());
         assertEquals(rect[0], encoded[base]);
         assertEquals(rect[1], encoded[base + 1]);
         assertEquals(SIZE, encoded[base + 2]);
         assertEquals(placement.transform().m00(), encoded[base + 3]);
         assertEquals(placement.transform().tz(), encoded[base + 8]);
         assertEquals(placement.portal() == null ? 0 : 1, encoded[base + 9]);
      }
   }

   private List<CubeNetUnfolding.Placement> moved(List<CubeNetUnfolding.Placement> placements,
      CubeNetUnfolding.Affine change) {
      return placements.stream().map(placement -> {
         var portal = placement.portal();
         CubeNetUnfolding.Portal movedPortal = null;
         if (portal != null) {
            double[] start = change.apply(portal.startX(), portal.startZ());
            double[] end = change.apply(portal.endX(), portal.endZ());
            movedPortal = new CubeNetUnfolding.Portal(Math.round(start[0]), Math.round(start[1]),
               Math.round(end[0]), Math.round(end[1]));
         }
         return new CubeNetUnfolding.Placement(placement.face(), placement.transform().then(change), movedPortal);
      }).toList();
   }

   private long[] screenRect(CubeNetUnfolding.Placement placement) {
      long[] rect = this.unfolding.canonicalRect(placement.face());
      double[] a = placement.transform().apply(rect[0], rect[1]);
      double[] b = placement.transform().apply(rect[0] + rect[2], rect[1] + rect[2]);
      return new long[]{Math.round(Math.min(a[0], b[0])), Math.round(Math.min(a[1], b[1])), rect[2]};
   }

   private double[] outward(CubeNetLayout.Face face, CubeNetLayout.Side side) {
      return switch (side) {
         case WEST -> this.layout.planarVector(face, -SIZE, 0);
         case EAST -> this.layout.planarVector(face, SIZE, 0);
         case NORTH -> this.layout.planarVector(face, 0, -SIZE);
         case SOUTH -> this.layout.planarVector(face, 0, SIZE);
      };
   }

   private static void assertRect(long[] home, double dx, double dz, long[] actual, String label) {
      assertEquals(home[0] + Math.round(dx), actual[0], label);
      assertEquals(home[1] + Math.round(dz), actual[1], label);
      assertEquals(home[2], actual[2], label);
   }

   private static CubeNetLayout.FacePoint outside(CubeNetLayout.Face face, CubeNetLayout.Side side,
      double edge, double penetration) {
      double u = switch (side) {
         case WEST -> -penetration;
         case EAST -> SIZE + penetration;
         case NORTH, SOUTH -> edge;
      };
      double v = switch (side) {
         case NORTH -> -penetration;
         case SOUTH -> SIZE + penetration;
         case WEST, EAST -> edge;
      };
      return new CubeNetLayout.FacePoint(face, u, v);
   }
}
