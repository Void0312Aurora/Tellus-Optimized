package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.world.data.geo.WebMercatorTileMath;
import java.util.Objects;

/** Allocation-free tile-key admission for the fixed-zoom OSM vector sources. */
public final class OsmGeoQueryBudget {
   private OsmGeoQueryBudget() { }

   /**
    * Counts the inclusive tile visits made by the building/road GeoArea queries.
    * The caller supplies its initialized source's resolved query zoom. Date-line
    * windows are counted separately, just as the source queries visit them; this
    * does not deduplicate their keys, lower the zoom, or truncate the rectangle.
    * An unsupported all-polar rectangle is not admitted as a known empty query.
    * Only the at-most-two Mercator window objects are created, never tile keys.
    */
   public static boolean withinBudget(GeodeticBounds bounds, int zoom, int maxTiles) {
      Objects.requireNonNull(bounds, "bounds");
      if (zoom < 0 || zoom > 20) {
         throw new IllegalArgumentException("zoom must be in [0, 20]");
      }
      if (maxTiles < 1) {
         throw new IllegalArgumentException("maxTiles must be positive");
      }
      var windows = bounds.mercatorWindows();
      if (windows.isEmpty()) return false;

      int tilesPerAxis = 1 << zoom;
      long visits = 0L;
      for (var window : windows) {
         int minX = clampTile(longitudeTile(window.west(), tilesPerAxis), tilesPerAxis);
         int maxX = clampTile(longitudeTile(window.east(), tilesPerAxis), tilesPerAxis);
         int minY = clampTile(latitudeTile(window.north(), tilesPerAxis), tilesPerAxis);
         int maxY = clampTile(latitudeTile(window.south(), tilesPerAxis), tilesPerAxis);
         long width = Math.max(0L, (long)maxX - minX + 1L);
         long height = Math.max(0L, (long)maxY - minY + 1L);
         // At zoom <= 20 even two full-world windows fit comfortably in long.
         visits += width * height;
         if (visits > maxTiles) return false;
      }
      return true;
   }

   private static int longitudeTile(double longitude, int tilesPerAxis) {
      return (int)Math.floor((longitude + 180.0) / 360.0 * tilesPerAxis);
   }

   private static int latitudeTile(double latitude, int tilesPerAxis) {
      double clamped = Math.max(WebMercatorTileMath.MIN_LATITUDE,
         Math.min(WebMercatorTileMath.MAX_LATITUDE, latitude));
      double radians = Math.toRadians(clamped);
      double normalized = (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0;
      return (int)Math.floor(normalized * tilesPerAxis);
   }

   private static int clampTile(int value, int tilesPerAxis) {
      return Math.max(0, Math.min(tilesPerAxis - 1, value));
   }
}
