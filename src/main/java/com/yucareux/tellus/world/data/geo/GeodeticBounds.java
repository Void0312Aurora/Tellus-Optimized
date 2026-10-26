package com.yucareux.tellus.world.data.geo;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.List;
import java.util.Objects;

/** Latitude/longitude bounds; west > east denotes an antimeridian crossing. */
public record GeodeticBounds(double south, double west, double north, double east) {
   public GeodeticBounds {
      if (!Double.isFinite(south) || !Double.isFinite(west)
         || !Double.isFinite(north) || !Double.isFinite(east)
         || south < -90.0 || north > 90.0 || south > north
         || west < -180.0 || west > 180.0 || east < -180.0 || east > 180.0) {
         throw new IllegalArgumentException("invalid geodetic bounds");
      }
   }

   /** Conservative latitude/longitude envelope of a spherical distance cap. */
   public static GeodeticBounds around(SphereWorldModel.GeoPoint center, double radiusMeters) {
      Objects.requireNonNull(center, "center");
      if (!(radiusMeters >= 0.0) || !Double.isFinite(radiusMeters)) {
         throw new IllegalArgumentException("radiusMeters must be finite and non-negative");
      }
      double angle = Math.min(Math.PI, radiusMeters / SphereWorldModel.EARTH_RADIUS_METERS);
      double latitude = Math.toRadians(center.latitude());
      double south = Math.max(-90.0, center.latitude() - Math.toDegrees(angle));
      double north = Math.min(90.0, center.latitude() + Math.toDegrees(angle));
      if (latitude - angle <= -Math.PI / 2.0 || latitude + angle >= Math.PI / 2.0) {
         return new GeodeticBounds(south, -180.0, north, 180.0);
      }
      double longitudeRadius = Math.toDegrees(Math.asin(Math.min(1.0, Math.sin(angle) / Math.cos(latitude))));
      return new GeodeticBounds(
         south, wrap(center.longitude() - longitudeRadius), north, wrap(center.longitude() + longitudeRadius)
      );
   }

   /** Split into ordinary rectangles, clipped to the raster service domain. */
   public List<GeodeticBounds> mercatorWindows() {
      double clippedSouth = Math.max(this.south, WebMercatorTileMath.MIN_LATITUDE);
      double clippedNorth = Math.min(this.north, WebMercatorTileMath.MAX_LATITUDE);
      if (clippedSouth > clippedNorth) {
         return List.of();
      }
      if (this.west <= this.east) {
         return List.of(new GeodeticBounds(clippedSouth, this.west, clippedNorth, this.east));
      }
      return List.of(
         new GeodeticBounds(clippedSouth, this.west, clippedNorth, 180.0),
         new GeodeticBounds(clippedSouth, -180.0, clippedNorth, this.east)
      );
   }

   public boolean contains(SphereWorldModel.GeoPoint point) {
      return point.latitude() >= this.south && point.latitude() <= this.north
         && (this.west <= this.east
            ? point.longitude() >= this.west && point.longitude() <= this.east
            : point.longitude() >= this.west || point.longitude() <= this.east);
   }

   private static double wrap(double longitude) {
      if (longitude < -180.0) return longitude + 360.0;
      if (longitude > 180.0) return longitude - 360.0;
      return longitude;
   }
}
