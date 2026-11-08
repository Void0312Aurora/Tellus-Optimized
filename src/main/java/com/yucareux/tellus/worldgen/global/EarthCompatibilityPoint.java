package com.yucareux.tellus.worldgen.global;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Objects;

/**
 * A projected Earth-grid coordinate used by compatibility data sources.
 *
 * <p>This is a data view, not canonical spherical ownership. The X/Z values
 * use the active {@link EarthProjection} mode and the supplied metres-per
 * block scale. In particular, they must not be passed to cubed-sphere face
 * ownership or seam APIs.</p>
 */
public record EarthCompatibilityPoint(
   double blockX,
   double blockZ,
   double worldScale,
   String projectionModeId,
   boolean withinMercatorCoverage
) {
   public EarthCompatibilityPoint {
      if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
         throw new IllegalArgumentException("Earth compatibility coordinates must be finite");
      }
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) {
         throw new IllegalArgumentException("worldScale must be finite and positive");
      }
      projectionModeId = Objects.requireNonNull(projectionModeId, "projectionModeId");
      if (projectionModeId.isBlank()) {
         throw new IllegalArgumentException("projectionModeId must not be blank");
      }
   }

   /**
    * Derives the Earth compatibility view from a canonical geodetic point.
    * The active projection mode is read from the existing projection contract;
    * this method has no cache or generator side effects.
    */
   public static EarthCompatibilityPoint fromGeoPoint(
      SphereWorldModel.GeoPoint point,
      double worldScale
   ) {
      Objects.requireNonNull(point, "point");
      String mode = EarthProjection.projectionModeId();
      double blockX = EarthProjection.longitudeToBlockX(point.longitude(), worldScale);
      double blockZ = EarthProjection.latToBlockZ(point.latitude(), worldScale);
      boolean covered = "legacy".equalsIgnoreCase(mode)
         || Math.abs(point.latitude()) <= EarthProjection.MAX_MERCATOR_LATITUDE + 1.0E-9;
      return new EarthCompatibilityPoint(blockX, blockZ, worldScale, mode, covered);
   }

   /** Returns the projected cell containing this continuous point. */
   public long floorBlockX() {
      return (long)Math.floor(this.blockX);
   }

   /** Returns the projected cell containing this continuous point. */
   public long floorBlockZ() {
      return (long)Math.floor(this.blockZ);
   }
}
