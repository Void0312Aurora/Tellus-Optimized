package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end contracts for the boundary between two physical cube faces.
 *
 * <p>The planar net is only a view. These checks therefore follow the same
 * edge through the layout table, the exact cubed-sphere mapping, and the
 * point-sampler API. A failure identifies a cross-face seam before Minecraft
 * chunk materialization is involved.</p>
 */
class CubeNetCrossFaceConsistencyTest {
   private static final double EPSILON_METERS = 1.0E-6;
   private static final double SEAM_BAND_TOLERANCE_METERS = 1.0E-3;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1.0);
   private static final CubeNetLayout LAYOUT = new CubeNetLayout(MODEL.config().faceBlocks());

   @Test
   void everyDirectedEdgeSharesTheSameExactSphereAtInteriorAndVertices() {
      double size = LAYOUT.faceSize();
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            var seam = LAYOUT.seam(face, side);
            for (double edge : new double[]{0.0, 0.5, 8.0, size * 0.25,
               size * 0.5, size * 0.75, size - 8.0, size - 0.5, size}) {
               var source = boundary(face, side, edge, 0.0);
               var target = LAYOUT.mapAcross(face, side, edge, 0.0);
               var sourceGeo = MODEL.exactSurfacePoint(source.face().index(), source.u(), source.v());
               var targetGeo = MODEL.exactSurfacePoint(target.face().index(), target.u(), target.v());
               double distance = SphereWorldModel.greatCircleDistanceMeters(sourceGeo, targetGeo);
               assertEquals(0.0, distance, EPSILON_METERS,
                  () -> seam + " edge=" + edge + " distance=" + distance);
            }
         }
      }
   }

   @Test
   void continuedSeamBandAgreesWithTheCanonicalTargetFace() {
      double size = LAYOUT.faceSize();
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            var seam = LAYOUT.seam(face, side);
            for (double edge : new double[]{8.0, size * 0.25, size * 0.5,
               size * 0.75, size - 8.0}) {
               for (double penetration : new double[]{0.125, 1.0, 8.0}) {
                  var outside = boundary(face, side, edge, -penetration);
                  var continued = MODEL.continuedSurfacePoint(face.index(), outside.u(), outside.v());
                  var target = LAYOUT.mapAcross(face, side, edge, penetration);
                  var canonical = MODEL.exactSurfacePoint(target.face().index(), target.u(), target.v());
                  double distance = SphereWorldModel.greatCircleDistanceMeters(continued, canonical);
                  assertTrue(distance <= SEAM_BAND_TOLERANCE_METERS,
                     () -> seam + " edge=" + edge + " penetration=" + penetration
                        + " distance=" + distance);
               }
            }
         }
      }
   }

   @Test
   void pointSamplingUsesTheSamePhysicalValueFromEitherFace() {
      var sampler = new SphereChunkSampler(MODEL, (point, resolution) -> {
         double latitude = Math.toRadians(point.latitude());
         double longitude = Math.toRadians(point.longitude());
         double value = Math.sin(latitude) * 17.0
            + Math.cos(longitude) * 11.0
            + Math.sin(latitude + longitude) * 3.0;
         return new SphereChunkSampler.SurfaceData(value, 30);
      });
      double size = LAYOUT.faceSize();
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            var seam = LAYOUT.seam(face, side);
            for (double edge : new double[]{8.0, size * 0.31, size * 0.5, size * 0.69, size - 8.0}) {
               var source = boundary(face, side, edge, 0.0);
               var target = LAYOUT.mapAcross(face, side, edge, 0.0);
               var sourceGeo = MODEL.exactSurfacePoint(source.face().index(), source.u(), source.v());
               var targetGeo = MODEL.exactSurfacePoint(target.face().index(), target.u(), target.v());
               var sourceValue = sampler.samplePoint(sourceGeo, 1.0).elevationMeters();
               var targetValue = sampler.samplePoint(targetGeo, 1.0).elevationMeters();
               assertEquals(sourceValue, targetValue, 1.0E-12,
                  () -> seam + " edge=" + edge);
            }
         }
      }
   }

   @Test
   void cornerAndApronOwnershipRejectsAmbiguousCrossFaceSamples() {
      var remote = new com.yucareux.tellus.worldgen.global.CubeNetRemoteProjection(LAYOUT, 32.0);
      int ambiguousCorners = 0;
      for (var face : CubeNetLayout.Face.values()) {
         for (var first : CubeNetLayout.Side.values()) for (var second : CubeNetLayout.Side.values()) {
            if (first.ordinal() >= second.ordinal() || sameAxis(first, second)) continue;
            if (LAYOUT.arePlanarNeighbors(face, first) || LAYOUT.arePlanarNeighbors(face, second)) continue;
            double u = first == CubeNetLayout.Side.WEST || first == CubeNetLayout.Side.EAST
               ? (first == CubeNetLayout.Side.WEST ? -0.5 : LAYOUT.faceSize() + 0.5)
               : (second == CubeNetLayout.Side.WEST ? -0.5 : LAYOUT.faceSize() + 0.5);
            double v = first == CubeNetLayout.Side.NORTH || first == CubeNetLayout.Side.SOUTH
               ? (first == CubeNetLayout.Side.NORTH ? -0.5 : LAYOUT.faceSize() + 0.5)
               : (second == CubeNetLayout.Side.NORTH ? -0.5 : LAYOUT.faceSize() + 0.5);
            var planar = LAYOUT.toPlanarUnbounded(new CubeNetLayout.FacePoint(face, u, v));
            if (remote.resolve(planar.x(), planar.z()).isEmpty()) ambiguousCorners++;
         }
      }
      assertEquals(8, ambiguousCorners,
         "the standard net has eight concave cut-strip overlaps");
   }

   private static boolean sameAxis(CubeNetLayout.Side first, CubeNetLayout.Side second) {
      boolean firstHorizontal = first == CubeNetLayout.Side.WEST || first == CubeNetLayout.Side.EAST;
      boolean secondHorizontal = second == CubeNetLayout.Side.WEST || second == CubeNetLayout.Side.EAST;
      return firstHorizontal == secondHorizontal;
   }

   private static CubeNetLayout.FacePoint boundary(CubeNetLayout.Face face,
      CubeNetLayout.Side side, double edge, double penetration) {
      double size = LAYOUT.faceSize();
      return switch (side) {
         case WEST -> new CubeNetLayout.FacePoint(face, penetration, edge);
         case EAST -> new CubeNetLayout.FacePoint(face, size - penetration, edge);
         case NORTH -> new CubeNetLayout.FacePoint(face, edge, penetration);
         case SOUTH -> new CubeNetLayout.FacePoint(face, edge, size - penetration);
      };
   }
}
