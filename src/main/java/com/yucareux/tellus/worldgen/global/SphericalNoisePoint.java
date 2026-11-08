package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/**
 * A seam-stable, Earth-centred coordinate view for deterministic noise.
 *
 * <p>The integer pair is deliberately not an Earth projected grid cell. It is
 * derived from the unit ECEF direction so it remains continuous across the
 * date line and cubed-sphere face seams.</p>
 */
public record SphericalNoisePoint(
   double unitX,
   double unitY,
   double unitZ,
   int noiseX,
   int noiseZ,
   String algorithmId
) {
   public static final String DEFAULT_ALGORITHM_ID = "tellus:spherical-noise-v1-round";
   private static final double X_SCALE = 1_000_000.0;
   private static final double Y_NOISE_X_SCALE = 37_000.0;
   private static final double Y_NOISE_Z_SCALE = 91_000.0;
   private static final double UNIT_TOLERANCE = 1.0E-9;

   public SphericalNoisePoint {
      if (!Double.isFinite(unitX) || !Double.isFinite(unitY) || !Double.isFinite(unitZ)) {
         throw new IllegalArgumentException("spherical unit vector must be finite");
      }
      double length = Math.sqrt(unitX * unitX + unitY * unitY + unitZ * unitZ);
      if (!(length > 0.0) || Math.abs(length - 1.0) > UNIT_TOLERANCE) {
         throw new IllegalArgumentException("spherical noise vector must be unit length");
      }
      algorithmId = Objects.requireNonNull(algorithmId, "algorithmId");
      if (algorithmId.isBlank()) {
         throw new IllegalArgumentException("algorithmId must not be blank");
      }
   }

   /**
    * Derives the versioned noise view using one canonical rounding rule.
    * Elevation does not participate in horizontal spherical noise identity.
    */
   public static SphericalNoisePoint fromGeoPoint(SphereWorldModel.GeoPoint point) {
      return fromGeoPoint(point, DEFAULT_ALGORITHM_ID);
   }

   /** Derives the same coordinates under an explicitly named algorithm version. */
   public static SphericalNoisePoint fromGeoPoint(
      SphereWorldModel.GeoPoint point,
      String algorithmId
   ) {
      Objects.requireNonNull(point, "point");
      algorithmId = Objects.requireNonNull(algorithmId, "algorithmId");
      double latitude = Math.toRadians(point.latitude());
      double longitude = Math.toRadians(point.longitude());
      double cosLatitude = Math.cos(latitude);
      double unitX = cosLatitude * Math.cos(longitude);
      double unitY = Math.sin(latitude);
      double unitZ = cosLatitude * Math.sin(longitude);
      int noiseX = safeCoordinate(unitX * X_SCALE + unitY * Y_NOISE_X_SCALE);
      int noiseZ = safeCoordinate(unitZ * X_SCALE + unitY * Y_NOISE_Z_SCALE);
      return new SphericalNoisePoint(unitX, unitY, unitZ, noiseX, noiseZ, algorithmId);
   }

   private static int safeCoordinate(double value) {
      if (!Double.isFinite(value)) {
         throw new IllegalArgumentException("spherical noise coordinate must be finite");
      }
      if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
      if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
      return (int)Math.round(value);
   }
}
