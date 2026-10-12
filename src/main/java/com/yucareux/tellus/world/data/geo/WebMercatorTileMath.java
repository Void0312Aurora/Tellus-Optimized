package com.yucareux.tellus.world.data.geo;

/**
 * Web-Mercator tile math for external raster services.
 *
 * <p>This class is deliberately limited to the data-service coordinate
 * system. It does not define the Minecraft world geometry. A spherical world
 * can therefore use this code to address DEM tiles without converting its
 * geodetic point back into a projected Minecraft block coordinate.</p>
 */
public final class WebMercatorTileMath {
   public static final double MIN_LATITUDE = -85.05112878;
   public static final double MAX_LATITUDE = 85.05112878;
   public static final double MIN_LONGITUDE = -180.0;
   public static final double MAX_LONGITUDE = 180.0;

   private WebMercatorTileMath() {
   }

   /**
    * Projects a geodetic point to a raster tile at {@code zoom}.
    *
    * <p>Longitude wraps at the antimeridian, while latitude is rejected when
    * it is outside the finite Web-Mercator domain. The returned pixel
    * coordinates are global pixel coordinates in a square pyramid whose tile
    * side is {@code tileSize}.</p>
    */
   public static TileCoordinate project(double longitude, double latitude, int zoom, int tileSize) {
      if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
         || zoom < 0 || zoom > 30 || tileSize <= 0
         || latitude < MIN_LATITUDE || latitude > MAX_LATITUDE) {
         return null;
      }

      double wrappedLongitude = wrapLongitude(longitude);
      double n = Math.scalb(1.0, zoom);
      double latRadians = Math.toRadians(latitude);
      double x = (wrappedLongitude + 180.0) / 360.0 * n;
      double y = (1.0 - Math.log(Math.tan(latRadians) + 1.0 / Math.cos(latRadians)) / Math.PI) * 0.5 * n;
      // The upper latitude limit can round to exactly the pyramid edge.
      // Keep the point addressable by selecting the last pixel in that case.
      double maxPixel = Math.nextDown(n * tileSize);
      double globalX = Math.max(0.0, Math.min(x * tileSize, maxPixel));
      double globalY = Math.max(0.0, Math.min(y * tileSize, maxPixel));
      if (!(globalX >= 0.0) || !(globalY >= 0.0) || !(globalX <= maxPixel) || !(globalY <= maxPixel)) {
         return null;
      }

      int tileX = Math.min((int)n - 1, (int)Math.floor(globalX / tileSize));
      int tileY = Math.min((int)n - 1, (int)Math.floor(globalY / tileSize));
      return new TileCoordinate(
         tileX,
         tileY,
         globalX,
         globalY,
         globalX / tileSize,
         globalY / tileSize
      );
   }

   private static double wrapLongitude(double longitude) {
      double wrapped = longitude % 360.0;
      if (wrapped < MIN_LONGITUDE) {
         wrapped += 360.0;
      } else if (wrapped >= MAX_LONGITUDE) {
         wrapped -= 360.0;
      }
      return wrapped;
   }

   public record TileCoordinate(
      int tileX,
      int tileY,
      double globalPixelX,
      double globalPixelY,
      double tileXCoordinate,
      double tileYCoordinate
   ) {
      public TileCoordinate {
         if (tileX < 0 || tileY < 0
            || !Double.isFinite(globalPixelX) || !Double.isFinite(globalPixelY)
            || !Double.isFinite(tileXCoordinate) || !Double.isFinite(tileYCoordinate)) {
            throw new IllegalArgumentException("invalid tile coordinate");
         }
      }
   }
}
