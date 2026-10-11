package com.yucareux.tellus.worldgen.global;

import com.yucareux.tellus.worldgen.EarthProjection;

/**
 * Geometry kernel for a progressive, charted global map.
 *
 * <p>The Minecraft world remains a discrete block lattice. This class keeps
 * the lattice coordinates in the existing Tellus projection, while each chart
 * supplies a local tangent-space approximation for globe-aware operations.
 * Charts are deterministic and overlap only through their shared geodetic
 * anchor, so terrain and building providers can expand one chart at a time.
 * The truncated spherical exponential map is opt-in and does not change the
 * existing generator until a caller uses it.</p>
 */
public final class GlobalMapModel {
   public static final double EARTH_RADIUS_METERS = EarthProjection.METERS_PER_DEGREE * 180.0 / Math.PI;
   private static final int MAX_EXPANSION_ORDER = 12;

   private final Config config;

   public GlobalMapModel(Config config) {
      this.config = config == null ? Config.defaults(1.0) : config;
   }

   public static GlobalMapModel forWorldScale(double worldScale) {
      return new GlobalMapModel(Config.defaults(worldScale));
   }

   public Config config() {
      return this.config;
   }

   /** Returns the chart containing a projected block coordinate. */
   public ChartKey chartForBlock(double blockX, double blockZ) {
      long x = Math.floorDiv((long)Math.floor(blockX), (long)this.config.chartSizeBlocks());
      long z = Math.floorDiv((long)Math.floor(blockZ), (long)this.config.chartSizeBlocks());
      return new ChartKey(x, z);
   }

   /** Returns the chart containing a geodetic point under the active projection. */
   public ChartKey chartForGeo(GeoPoint point) {
      BlockPoint projected = this.project(point);
      return this.chartForBlock(projected.x(), projected.z());
   }

   public BlockPoint project(GeoPoint point) {
      if (point == null) {
         throw new IllegalArgumentException("point");
      }
      return new BlockPoint(
         EarthProjection.longitudeToBlockX(normalizeLongitude(point.longitude()), this.config.worldScale()),
         EarthProjection.latToBlockZ(point.latitude(), this.config.worldScale())
      );
   }

   public GeoPoint unproject(double blockX, double blockZ) {
      return new GeoPoint(
         EarthProjection.blockZToLat(blockZ, this.config.worldScale()),
         normalizeLongitude(EarthProjection.blockXToLongitude(blockX, this.config.worldScale())),
         0.0
      );
   }

   public ChartCoordinate toChartCoordinates(double blockX, double blockZ) {
      ChartKey key = this.chartForBlock(blockX, blockZ);
      double originX = key.originBlockX(this.config.chartSizeBlocks());
      double originZ = key.originBlockZ(this.config.chartSizeBlocks());
      return new ChartCoordinate(key, blockX - originX, blockZ - originZ);
   }

   public ChartCoordinate toChartCoordinates(GeoPoint point) {
      BlockPoint projected = this.project(point);
      return this.toChartCoordinates(projected.x(), projected.z());
   }

   /** Geodetic center of a chart in projected block space. */
   public GeoPoint chartCenter(ChartKey key) {
      if (key == null) {
         throw new IllegalArgumentException("key");
      }
      double half = this.config.chartSizeBlocks() * 0.5;
      return this.unproject(
         key.originBlockX(this.config.chartSizeBlocks()) + half,
         key.originBlockZ(this.config.chartSizeBlocks()) + half
      );
   }

   /**
    * Expands a local east/north offset from a chart center on the sphere.
    * Order 1 is the tangent-plane approximation; higher orders retain more
    * terms of sin(c) and cos(c), where c is angular distance.
    */
   public GeoPoint expand(ChartKey key, LocalOffset offset) {
      return this.expand(key, offset, this.config.expansionOrder());
   }

   public GeoPoint expand(ChartKey key, LocalOffset offset, int expansionOrder) {
      if (key == null || offset == null) {
         throw new IllegalArgumentException("chart and offset are required");
      }
      GeoPoint anchor = this.chartCenter(key);
      return expandFrom(anchor, offset, expansionOrder);
   }

   /** Maps a geodetic point to a local east/north offset from the chart center. */
   public LocalOffset localOffset(ChartKey key, GeoPoint point) {
      GeoPoint anchor = this.chartCenter(key);
      double lat0 = Math.toRadians(anchor.latitude());
      double dLat = Math.toRadians(point.latitude() - anchor.latitude());
      double dLon = Math.toRadians(shortestLongitudeDelta(point.longitude(), anchor.longitude()));
      return new LocalOffset(
         EARTH_RADIUS_METERS * Math.cos(lat0) * dLon,
         EARTH_RADIUS_METERS * dLat
      );
   }

   public LocalMetric metricAt(double blockX, double blockZ) {
      return new LocalMetric(
         EarthProjection.groundMetersPerBlockX(blockZ, this.config.worldScale()),
         EarthProjection.groundMetersPerBlockZ(blockZ, this.config.worldScale())
      );
   }

   /** Conservative Taylor remainder bound for a chart footprint. */
   public double truncationErrorBoundMeters(double footprintMeters, int expansionOrder) {
      if (!(footprintMeters >= 0.0) || !Double.isFinite(footprintMeters)) {
         throw new IllegalArgumentException("footprintMeters must be finite and non-negative");
      }
      int order = clampOrder(expansionOrder);
      double angularRadius = footprintMeters / EARTH_RADIUS_METERS;
      double remainder = Math.exp(Math.min(1.0, angularRadius))
         * Math.pow(angularRadius, order + 1) / factorial(order + 1);
      return EARTH_RADIUS_METERS * remainder;
   }

   /** Largest symmetric footprint whose conservative truncation bound is within tolerance. */
   public double maxFootprintMeters(double toleranceMeters, int expansionOrder) {
      if (!(toleranceMeters > 0.0) || !Double.isFinite(toleranceMeters)) {
         throw new IllegalArgumentException("toleranceMeters must be finite and positive");
      }
      double low = 0.0;
      double high = EARTH_RADIUS_METERS;
      for (int i = 0; i < 80; i++) {
         double mid = (low + high) * 0.5;
         if (this.truncationErrorBoundMeters(mid, expansionOrder) <= toleranceMeters) {
            low = mid;
         } else {
            high = mid;
         }
      }
      return low;
   }

   /** Returns the smallest configured expansion order that meets the chart tolerance. */
   public int recommendedExpansionOrder(double footprintMeters) {
      if (!(footprintMeters >= 0.0) || !Double.isFinite(footprintMeters)) {
         throw new IllegalArgumentException("footprintMeters must be finite and non-negative");
      }
      for (int order = 1; order <= MAX_EXPANSION_ORDER; order++) {
         if (this.truncationErrorBoundMeters(footprintMeters, order) <= this.config.truncationToleranceMeters()) {
            return order;
         }
      }
      return MAX_EXPANSION_ORDER;
   }

   /** Approximate half-diagonal of a chart in ground metres at its centre latitude. */
   public double chartHalfDiagonalMeters(ChartKey key) {
      if (key == null) {
         throw new IllegalArgumentException("key");
      }
      double half = this.config.chartSizeBlocks() * 0.5;
      double centerBlockX = key.originBlockX(this.config.chartSizeBlocks()) + half;
      double centerBlockZ = key.originBlockZ(this.config.chartSizeBlocks()) + half;
      LocalMetric metric = this.metricAt(centerBlockX, centerBlockZ);
      return Math.hypot(half * metric.eastMetersPerBlock(), half * metric.northMetersPerBlock());
   }

   /** Returns the order selected for the chart's conservative half-diagonal. */
   public int recommendedExpansionOrder(ChartKey key) {
      return this.recommendedExpansionOrder(this.chartHalfDiagonalMeters(key));
   }

   public static GeoPoint expandFrom(GeoPoint anchor, LocalOffset offset, int expansionOrder) {
      if (anchor == null || offset == null) {
         throw new IllegalArgumentException("anchor and offset are required");
      }
      int order = clampOrder(expansionOrder);
      double east = offset.eastMeters();
      double north = offset.northMeters();
      double distance = Math.hypot(east, north);
      if (distance == 0.0) {
         return anchor;
      }

      double angularDistance = distance / EARTH_RADIUS_METERS;
      double sinC = truncatedSin(angularDistance, order);
      double cosC = truncatedCos(angularDistance, order);
      double bearingEast = east / distance;
      double bearingNorth = north / distance;
      double lat0 = Math.toRadians(anchor.latitude());
      double lon0 = Math.toRadians(anchor.longitude());
      double sinLat = Math.sin(lat0) * cosC + bearingNorth * Math.cos(lat0) * sinC;
      double latitude = Math.toDegrees(Math.asin(clampUnit(sinLat)));
      double longitude = Math.toDegrees(
         lon0 + Math.atan2(
            bearingEast * sinC,
            Math.cos(lat0) * cosC - bearingNorth * Math.sin(lat0) * sinC
         )
      );
      return new GeoPoint(latitude, normalizeLongitude(longitude), anchor.elevationMeters());
   }

   public static double normalizeLongitude(double longitude) {
      if (!Double.isFinite(longitude)) {
         throw new IllegalArgumentException("longitude must be finite");
      }
      double normalized = longitude % 360.0;
      if (normalized > 180.0) normalized -= 360.0;
      if (normalized <= -180.0) normalized += 360.0;
      return normalized;
   }

   public static double shortestLongitudeDelta(double longitude, double reference) {
      return normalizeLongitude(longitude - reference);
   }

   private static int clampOrder(int order) {
      return Math.max(1, Math.min(MAX_EXPANSION_ORDER, order));
   }

   private static double truncatedSin(double value, int order) {
      double result = 0.0;
      for (int n = 0; 2 * n + 1 <= order; n++) {
         result += ((n & 1) == 0 ? 1.0 : -1.0) * Math.pow(value, 2 * n + 1) / factorial(2 * n + 1);
      }
      return result == 0.0 ? value : result;
   }

   private static double truncatedCos(double value, int order) {
      double result = 0.0;
      for (int n = 0; 2 * n <= order; n++) {
         result += ((n & 1) == 0 ? 1.0 : -1.0) * Math.pow(value, 2 * n) / factorial(2 * n);
      }
      return result == 0.0 ? 1.0 : result;
   }

   private static double factorial(int n) {
      double result = 1.0;
      for (int i = 2; i <= n; i++) result *= i;
      return result;
   }

   private static double clampUnit(double value) {
      return Math.max(-1.0, Math.min(1.0, value));
   }

   public record Config(double worldScale, int chartSizeBlocks, int expansionOrder, double truncationToleranceMeters) {
      public Config {
         if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) throw new IllegalArgumentException("worldScale");
         if (chartSizeBlocks < 16) throw new IllegalArgumentException("chartSizeBlocks must be at least 16");
         if (expansionOrder < 1 || expansionOrder > MAX_EXPANSION_ORDER) throw new IllegalArgumentException("expansionOrder");
         if (!(truncationToleranceMeters > 0.0) || !Double.isFinite(truncationToleranceMeters)) {
            throw new IllegalArgumentException("truncationToleranceMeters");
         }
      }

      public static Config defaults(double worldScale) {
         return new Config(worldScale, 1024, 3, 0.25);
      }
   }

   public record ChartKey(long x, long z) {
      public long originBlockX(int chartSizeBlocks) {
         return Math.multiplyExact(this.x, (long)chartSizeBlocks);
      }

      public long originBlockZ(int chartSizeBlocks) {
         return Math.multiplyExact(this.z, (long)chartSizeBlocks);
      }
   }

   public record ChartCoordinate(ChartKey chart, double localX, double localZ) {
   }

   public record BlockPoint(double x, double z) {
   }

   public record GeoPoint(double latitude, double longitude, double elevationMeters) {
      public GeoPoint {
         if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(elevationMeters)) {
            throw new IllegalArgumentException("geodetic coordinates must be finite");
         }
         latitude = Math.max(-90.0, Math.min(90.0, latitude));
         longitude = normalizeLongitude(longitude);
      }
   }

   public record LocalOffset(double eastMeters, double northMeters) {
      public LocalOffset {
         if (!Double.isFinite(eastMeters) || !Double.isFinite(northMeters)) {
            throw new IllegalArgumentException("local offset must be finite");
         }
      }
   }

   public record LocalMetric(double eastMetersPerBlock, double northMetersPerBlock) {
   }
}
