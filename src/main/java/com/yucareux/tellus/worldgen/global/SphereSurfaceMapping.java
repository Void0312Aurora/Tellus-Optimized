package com.yucareux.tellus.worldgen.global;

/** Versioned face mapping used by the canonical spherical runtime. */
public interface SphereSurfaceMapping {
   String id();
   SphereWorldModel.GeoPoint point(int face, double u, double v);
   FaceCoordinate inverse(SphereWorldModel.GeoPoint point);

   default FaceCoordinate inverseOnFace(int face, SphereWorldModel.GeoPoint point) {
      FaceCoordinate result = inverse(point);
      if (result.face() != face) throw new IllegalArgumentException("point is outside face");
      return result;
   }

   record FaceCoordinate(int face, double u, double v) { }
}
