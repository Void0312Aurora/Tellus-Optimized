package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetSeamTransform;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the physical cube boundary.
 *
 * <p>This is intentionally a pure shared test layer. A Minecraft module may
 * materialize the chart as worlds, chunks, or an apron, but it must preserve
 * this same topology and orientation contract.</p>
 */
class CubeNetBoundaryContractTest {
   private static final int FACE_SIZE = 64;
   private static final double EPSILON = 1.0E-9;

   @Test
   void layoutExposesExactlyTwentyFourDirectedPhysicalEdges() {
      var layout = new CubeNetLayout(FACE_SIZE);
      Set<CubeNetLayout.Seam> seams = new HashSet<>();
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            var seam = layout.seam(face, side);
            assertEquals(face, seam.source(), seam.toString());
            assertEquals(side, seam.sourceSide(), seam.toString());
            assertTrue(seam.target() != face, seam.toString());
            assertTrue(seams.add(seam), "duplicate directed seam " + seam);
         }
      }
      assertEquals(6 * 4, seams.size());
   }

   @Test
   void everyDirectedEdgeHasOneReciprocalWithTheSameEndpointOrder() {
      var layout = new CubeNetLayout(FACE_SIZE);
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            var seam = layout.seam(face, side);
            var reverse = layout.seam(seam.target(), seam.targetSide());
            assertEquals(face, reverse.target(), seam.toString());
            assertEquals(side, reverse.targetSide(), seam.toString());
            assertEquals(seam.reverse(), reverse.reverse(), seam.toString());
            for (double edge : new double[]{0.5, 17.25, 31.5, 63.5}) {
               var target = layout.mapAcross(face, side, edge, 0.75);
               var restored = layout.mapAcross(target.face(), seam.targetSide(),
                  sideAlong(target, seam.targetSide()), 0.75);
               assertEquals(face, restored.face(), seam.toString());
               assertEquals(edge, sideAlong(restored, side), EPSILON, seam.toString());
            }
         }
      }
   }

   @Test
   void everySeamIsAnOrientationPreservingQuarterTurnWithAnAffineInverse() {
      var layout = new CubeNetLayout(FACE_SIZE);
      double[][] samples = {{0.25, 0.75}, {17.5, 42.25}, {63.75, 31.5}};
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            var seam = layout.seam(face, side);
            var transform = new CubeNetSeamTransform(layout, seam);
            var grid = transform.targetToSourceGridTransform();
            assertEquals(1, grid.determinant(), seam.toString());
            assertTrue(transform.targetToSourceClockwiseQuarterTurns().isPresent(), seam.toString());
            for (double[] sample : samples) {
               var target = new CubeNetLayout.FacePoint(seam.target(), sample[0], sample[1]);
               var sourceChart = transform.targetToSourcePlanar(target);
               var restored = transform.sourcePlanarToTarget(sourceChart);
               assertEquals(target.face(), restored.face(), seam.toString());
               assertEquals(target.u(), restored.u(), EPSILON, seam.toString());
               assertEquals(target.v(), restored.v(), EPSILON, seam.toString());
            }
            var vector = grid.apply(0.35, -0.8);
            var restoredVector = grid.inverse().apply(vector[0], vector[1]);
            assertEquals(0.35, restoredVector[0], EPSILON, seam.toString());
            assertEquals(-0.8, restoredVector[1], EPSILON, seam.toString());
         }
      }
   }

   @Test
   void directNetEdgesUseTheIdentityChartRotation() {
      var layout = new CubeNetLayout(FACE_SIZE);
      int directEdges = 0;
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            if (!layout.arePlanarNeighbors(face, side)) continue;
            directEdges++;
            var transform = new CubeNetSeamTransform(layout, layout.seam(face, side));
            assertEquals(0, transform.targetToSourceClockwiseQuarterTurns().orElseThrow(),
               face + " " + side);
         }
      }
      assertEquals(10, directEdges);
   }

   @Test
   void aSingleBoundaryCrossingKeepsTheRemainingStepOnTheTargetFace() {
      var layout = new CubeNetLayout(FACE_SIZE);
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            double u = switch (side) {
               case WEST -> 0.25;
               case EAST -> FACE_SIZE - 0.25;
               case NORTH, SOUTH -> FACE_SIZE * 0.5;
            };
            double v = switch (side) {
               case NORTH -> 0.25;
               case SOUTH -> FACE_SIZE - 0.25;
               case WEST, EAST -> FACE_SIZE * 0.5;
            };
            double deltaU = side == CubeNetLayout.Side.WEST ? -1.0
               : side == CubeNetLayout.Side.EAST ? 1.0 : 0.0;
            double deltaV = side == CubeNetLayout.Side.NORTH ? -1.0
               : side == CubeNetLayout.Side.SOUTH ? 1.0 : 0.0;
            var transfer = layout.crossSeam(new CubeNetLayout.FacePoint(face, u, v), deltaU, deltaV);
            assertTrue(transfer.crossed(), face + " " + side);
            assertEquals(layout.seam(face, side), transfer.seam(), face + " " + side);
            assertEquals(layout.seam(face, side).target(), transfer.destination().face(),
               face + " " + side);
            assertTrue(transfer.destination().u() >= 0.0
               && transfer.destination().u() <= FACE_SIZE, face + " " + side);
            assertTrue(transfer.destination().v() >= 0.0
               && transfer.destination().v() <= FACE_SIZE, face + " " + side);
         }
      }
   }

   @Test
   void aStepThatDoesNotReachAnEdgeStaysOnItsOwningFace() {
      var layout = new CubeNetLayout(FACE_SIZE);
      var source = new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_Y, 31.5, 27.25);
      var transfer = layout.crossSeam(source, 0.2, -0.3);
      assertFalse(transfer.crossed());
      assertEquals(null, transfer.seam());
      assertEquals(new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_Y, 31.7, 26.95),
         transfer.destination());
   }

   @Test
   void cornerCrossingIsRejectedUntilAnExplicitVertexPolicyExists() {
      var layout = new CubeNetLayout(FACE_SIZE);
      assertThrows(IllegalArgumentException.class, () -> layout.crossSeam(
         new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_Y, 0.25, 0.25), -1.0, -1.0));
      assertThrows(IllegalArgumentException.class, () -> layout.crossSeam(
         new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_Y, FACE_SIZE - 0.25, FACE_SIZE - 0.25),
         1.0, 1.0));
   }

   @Test
   void planarInteriorHasOneOwnerAndDirectSharedEdgesHaveNoImplicitSecondOwner() {
      var layout = new CubeNetLayout(FACE_SIZE);
      for (var face : CubeNetLayout.Face.values()) {
         for (double u : new double[]{0.25, 17.5, 63.75}) {
            for (double v : new double[]{0.25, 31.5, 63.75}) {
               var source = new CubeNetLayout.FacePoint(face, u, v);
               assertEquals(source, layout.fromPlanar(layout.toPlanar(source)));
            }
         }
      }
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            if (!layout.arePlanarNeighbors(face, side)) continue;
            double edge = FACE_SIZE * 0.5;
            var boundary = layout.toPlanar(layout.mapAcross(face, side, edge, 0.0));
            assertThrows(IllegalArgumentException.class, () -> layout.fromPlanar(boundary),
               face + " " + side);
         }
      }
   }

   @Test
   void everyCutApronSampleResolvesToExactlyOneCanonicalOwner() {
      var layout = new CubeNetLayout(FACE_SIZE);
      var resolver = new CubeNetApronResolver(layout, 32);
      int cutSeams = 0;
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            if (layout.arePlanarNeighbors(face, side)) continue;
            cutSeams++;
            var seam = layout.seam(face, side);
            // Stay away from the two concave net vertices. Those overlap
            // samples are covered by the explicit rejection test below.
            for (double edge : new double[]{0.5, 17.5, 31.5}) {
               for (double penetration : new double[]{0.5, 15.5, 31.5}) {
                  var sourceOutside = outsidePoint(face, side, edge, penetration);
                  var planar = layout.toPlanarUnbounded(sourceOutside);
                  var resolved = resolver.resolve(planar.x(), planar.z()).orElseThrow(
                     () -> new AssertionError("missing apron owner for " + seam
                        + " edge=" + edge + " penetration=" + penetration
                        + " planar=(" + planar.x() + "," + planar.z() + ")"));
                  var expected = layout.mapAcross(face, side, edge, penetration);
                  assertEquals(seam.target(), resolved.targetFace(), seam.toString());
                  assertEquals((int)Math.floor(expected.u()), resolved.blockX(), seam.toString());
                  assertEquals((int)Math.floor(expected.v()), resolved.blockZ(), seam.toString());
                  assertEquals(edge, resolved.edgeCoordinate(), EPSILON, seam.toString());

                  var canonicalPlanar = layout.toPlanar(expected);
                  assertTrue(resolver.resolve(canonicalPlanar.x(), canonicalPlanar.z()).isEmpty(),
                     "canonical face must remain the only owner for " + seam);
                  assertTrue(resolver.resolveCanonicalBlock(canonicalPlanar.x(), canonicalPlanar.z())
                     .stream().anyMatch(projection -> projection.sourceFace() == face
                        && projection.sourceSide() == side
                        && projection.targetFace() == expected.face()
                        && projection.targetBlockX() == (int)Math.floor(expected.u())
                        && projection.targetBlockZ() == (int)Math.floor(expected.v())),
                     "reverse projection missing for " + seam);
               }
            }
         }
      }
      assertEquals(14, cutSeams);
   }

   @Test
   void overlappingConcaveApronStripsAreRejectedUntilCornerPolicyExists() {
      var layout = new CubeNetLayout(FACE_SIZE);
      var resolver = new CubeNetApronResolver(layout, 32);
      var sourceOutside = outsidePoint(CubeNetLayout.Face.POS_X,
         CubeNetLayout.Side.WEST, 62.5, 0.5);
      var planar = layout.toPlanarUnbounded(sourceOutside);
      assertTrue(resolver.resolve(planar.x(), planar.z()).isEmpty());
   }

   private static CubeNetLayout.FacePoint outsidePoint(CubeNetLayout.Face face,
      CubeNetLayout.Side side, double edge, double penetration) {
      return switch (side) {
         case WEST -> new CubeNetLayout.FacePoint(face, -penetration, edge);
         case EAST -> new CubeNetLayout.FacePoint(face, FACE_SIZE + penetration, edge);
         case NORTH -> new CubeNetLayout.FacePoint(face, edge, -penetration);
         case SOUTH -> new CubeNetLayout.FacePoint(face, edge, FACE_SIZE + penetration);
      };
   }

   private static double sideAlong(CubeNetLayout.FacePoint point, CubeNetLayout.Side side) {
      return switch (side) {
         case WEST, EAST -> point.v();
         case NORTH, SOUTH -> point.u();
      };
   }
}
