package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/**
 * Versioned equiangular cubed-sphere map retained for historical comparisons.
 * The canonical runtime uses {@link ConformalCubedSphere}; this class keeps
 * old RFC measurements explicit instead of making them depend on that default.
 */
public final class EquiangularCubedSphere implements SphereSurfaceMapping {
   public static final String MAP_ID = "equiangular-cube-v1";
   private static final double HALF_FACE_ANGLE = Math.PI * 0.25;

   @Override public String id() { return MAP_ID; }

   @Override public SphereWorldModel.GeoPoint point(int face, double u, double v) {
      validate(face, u, v);
      return vector(face, u, v).geo();
   }

   @Override public SphereSurfaceMapping.FaceCoordinate inverse(SphereWorldModel.GeoPoint point) {
      Vector p = Vector.from(Objects.requireNonNull(point, "point"));
      double ax = Math.abs(p.x), ay = Math.abs(p.y), az = Math.abs(p.z);
      if (ax >= ay && ax >= az) {
         return p.x >= 0.0 ? new SphereSurfaceMapping.FaceCoordinate(0,
            Math.atan2(-p.z, p.x) / HALF_FACE_ANGLE,
            Math.atan2(p.y, p.x) / HALF_FACE_ANGLE) : new SphereSurfaceMapping.FaceCoordinate(1,
            Math.atan2(p.z, -p.x) / HALF_FACE_ANGLE,
            Math.atan2(p.y, -p.x) / HALF_FACE_ANGLE);
      }
      if (ay >= az) {
         return p.y >= 0.0 ? new SphereSurfaceMapping.FaceCoordinate(2,
            Math.atan2(p.x, p.y) / HALF_FACE_ANGLE,
            Math.atan2(-p.z, p.y) / HALF_FACE_ANGLE) : new SphereSurfaceMapping.FaceCoordinate(3,
            Math.atan2(p.x, -p.y) / HALF_FACE_ANGLE,
            Math.atan2(p.z, -p.y) / HALF_FACE_ANGLE);
      }
      return p.z >= 0.0 ? new SphereSurfaceMapping.FaceCoordinate(4,
         Math.atan2(p.x, p.z) / HALF_FACE_ANGLE,
         Math.atan2(p.y, p.z) / HALF_FACE_ANGLE) : new SphereSurfaceMapping.FaceCoordinate(5,
         Math.atan2(-p.x, -p.z) / HALF_FACE_ANGLE,
         Math.atan2(p.y, -p.z) / HALF_FACE_ANGLE);
   }

   @Override public SphereSurfaceMapping.FaceCoordinate inverseOnFace(int face,
      SphereWorldModel.GeoPoint point) {
      SphereSurfaceMapping.FaceCoordinate result = inverse(point);
      if (result.face() != face) throw new IllegalArgumentException("point is outside face");
      return result;
   }

   private static Vector vector(int face, double u, double v) {
      double a = Math.tan(u * HALF_FACE_ANGLE), b = Math.tan(v * HALF_FACE_ANGLE);
      return switch (face) {
         case 0 -> new Vector(1.0, b, -a).normalized();
         case 1 -> new Vector(-1.0, b, a).normalized();
         case 2 -> new Vector(a, 1.0, -b).normalized();
         case 3 -> new Vector(a, -1.0, b).normalized();
         case 4 -> new Vector(a, b, 1.0).normalized();
         case 5 -> new Vector(-a, b, -1.0).normalized();
         default -> throw new IllegalArgumentException("face");
      };
   }

   private static void validate(int face, double u, double v) {
      if (face < 0 || face >= SphereWorldModel.FACE_COUNT || !Double.isFinite(u)
         || !Double.isFinite(v) || Math.abs(u) > 1.0 || Math.abs(v) > 1.0) {
         throw new IllegalArgumentException("face coordinates");
      }
   }

   private record Vector(double x, double y, double z) {
      private Vector normalized() {
         double length = Math.sqrt(x * x + y * y + z * z);
         return new Vector(x / length, y / length, z / length);
      }

      private SphereWorldModel.GeoPoint geo() {
         return new SphereWorldModel.GeoPoint(Math.toDegrees(Math.atan2(y, Math.hypot(x, z))),
            Math.toDegrees(Math.atan2(z, x)), 0.0);
      }

      private static Vector from(SphereWorldModel.GeoPoint point) {
         double lat = Math.toRadians(point.latitude()), lon = Math.toRadians(point.longitude());
         double cos = Math.cos(lat);
         return new Vector(cos * Math.cos(lon), Math.sin(lat), cos * Math.sin(lon));
      }
   }
}
