package com.yucareux.tellus.worldgen.global;

import java.util.Objects;
import java.util.Optional;

/**
 * Projects geodetic feature vertices into the same canonical face and planar
 * cube-net address used by terrain columns. Feature code must use this helper
 * instead of converting longitude/latitude through a global flat projection.
 */
public final class CubeNetFeatureProjector {
   private CubeNetFeatureProjector() { }

   public static Optional<Projection> project(
      SphereWorldModel model, CubeNetLayout layout, double latitude, double longitude
   ) {
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(layout, "layout");
      if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) return Optional.empty();
      SphereWorldModel.GeoPoint point = new SphereWorldModel.GeoPoint(latitude, longitude, 0.0);
      SphereWorldModel.ContinuousFaceCoordinate coordinate = model.continuousForGeo(point);
      if (!(coordinate.blockX() >= 0.0 && coordinate.blockX() <= layout.faceSize()
         && coordinate.blockZ() >= 0.0 && coordinate.blockZ() <= layout.faceSize())) {
         return Optional.empty();
      }
      CubeNetLayout.PlanarPoint planar = layout.toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(coordinate.face()), coordinate.blockX(), coordinate.blockZ()
      ));
      return Optional.of(new Projection(
         point, coordinate.face(), coordinate.blockX(), coordinate.blockZ(), planar.x(), planar.z()
      ));
   }

   public record Projection(
      SphereWorldModel.GeoPoint point,
      int face,
      double faceX,
      double faceZ,
      double planarX,
      double planarZ
   ) {
      public Projection {
         Objects.requireNonNull(point, "point");
         if (face < 0 || face >= SphereWorldModel.FACE_COUNT
            || !Double.isFinite(faceX) || !Double.isFinite(faceZ)
            || !Double.isFinite(planarX) || !Double.isFinite(planarZ)) {
            throw new IllegalArgumentException("invalid feature projection");
         }
      }
   }
}
