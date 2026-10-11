package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/**
 * Canonical spherical coordinate model for the progressive global world.
 *
 * <p>This model deliberately does not use a world-wide map projection. The
 * reference surface is a cubed sphere with six equiangular faces. A face is
 * divided into block-sized cells and then into charts. Minecraft chunks can
 * be materialized from {@link SphereChunkAddress} values without making a
 * Mercator plane the authoritative geometry.</p>
 *
 * <p>Web-Mercator tile coordinates remain an implementation detail of an
 * external DEM or OSM reader. They do not enter this class.</p>
 */
public final class SphereWorldModel {
   public static final double EARTH_RADIUS_METERS = 6_371_008.8;
   public static final int FACE_COUNT = 6;
   public static final int CHUNK_SIZE_BLOCKS = 16;
   private static final int MAX_EXPANSION_ORDER = 12;
   private static final double HALF_FACE_ANGLE = Math.PI * 0.25;
   private static final double EPSILON = 1.0E-12;

   private final Config config;

   public SphereWorldModel(Config config) {
      this.config = Objects.requireNonNull(config, "config");
   }

   public static SphereWorldModel forWorldScale(double worldScale) {
      return new SphereWorldModel(Config.defaults(worldScale));
   }

   public Config config() {
      return this.config;
   }

   public long chunksPerFace() {
      return this.config.faceBlocks() / CHUNK_SIZE_BLOCKS;
   }

   public long chartsPerFace() {
      return this.config.faceBlocks() / this.config.chartSizeBlocks();
   }

   /**
    * Nominal metres represented by one face block at the face midpoint. The
    * cubed-sphere map has bounded, position-dependent scale distortion; this
    * value is the reference used when choosing the face resolution.
    */
   public double nominalBlockScaleMeters() {
      return Math.PI * EARTH_RADIUS_METERS * 0.5 / this.config.faceBlocks();
   }

   /** Returns the chart containing a block cell on a cubed-sphere face. */
   public ChartKey chartForBlock(int face, long blockX, long blockZ) {
      validateFace(face);
      validateFaceBlock(blockX, blockZ);
      return new ChartKey(
         face,
         Math.floorDiv(blockX, (long)this.config.chartSizeBlocks()),
         Math.floorDiv(blockZ, (long)this.config.chartSizeBlocks())
      );
   }

   /** Returns the chart containing a chunk address. */
   public ChartKey chartForChunk(SphereChunkAddress address) {
      Objects.requireNonNull(address, "address");
      return this.chartForBlock(address.face(), address.blockX(), address.blockZ());
   }

   /** Returns the chart containing a geodetic point. */
   public ChartKey chartForGeo(GeoPoint point) {
      FaceCoordinate coordinate = this.faceCoordinate(point);
      return this.chartForBlock(coordinate.face(), coordinate.blockX(), coordinate.blockZ());
   }

   /** Returns the geodetic center of a chart using the exact cubed-sphere map. */
   public GeoPoint chartCenter(ChartKey key) {
      Objects.requireNonNull(key, "key");
      validateFace(key.face());
      long originX = key.originBlockX(this.config.chartSizeBlocks());
      long originZ = key.originBlockZ(this.config.chartSizeBlocks());
      double centerX = originX + this.config.chartSizeBlocks() * 0.5;
      double centerZ = originZ + this.config.chartSizeBlocks() * 0.5;
      return this.exactSurfacePoint(key.face(), centerX, centerZ);
   }

   /**
    * Maps a chunk center to the spherical surface using the configured
    * truncated exponential map. The exact cubed-sphere map remains available
    * through {@link #exactSurfacePoint(SphereChunkAddress)} for validation.
    */
   public GeoPoint surfacePoint(SphereChunkAddress address) {
      return this.surfacePoint(address, this.config.expansionOrder());
   }

   public GeoPoint surfacePoint(SphereChunkAddress address, int expansionOrder) {
      Objects.requireNonNull(address, "address");
      ChartKey chart = this.chartForChunk(address);
      GeoPoint anchor = this.chartCenter(chart);
      GeoPoint exact = this.exactSurfacePoint(address);
      LocalOffset offset = localOffset(anchor, exact);
      return expandFrom(anchor, offset, expansionOrder);
   }

   /** Exact cubed-sphere mapping for a block cell center. */
   public GeoPoint exactSurfacePoint(SphereChunkAddress address) {
      Objects.requireNonNull(address, "address");
      return this.exactSurfacePoint(address.face(), address.blockX() + 0.5, address.blockZ() + 0.5);
   }

   /** Exact cubed-sphere mapping for continuous face coordinates. */
   public GeoPoint exactSurfacePoint(int face, double blockX, double blockZ) {
      validateFace(face);
      if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)
         || blockX < 0.0 || blockX > this.config.faceBlocks()
         || blockZ < 0.0 || blockZ > this.config.faceBlocks()) {
         throw new IllegalArgumentException("face coordinates are outside the face");
      }
      double u = blockToFaceCoordinate(blockX, this.config.faceBlocks());
      double v = blockToFaceCoordinate(blockZ, this.config.faceBlocks());
      return fromVector(faceVector(face, u, v), 0.0);
   }

   /** Maps a geodetic point to its owning face and block cell. */
   public SphereBlockAddress blockForGeo(GeoPoint point) {
      FaceCoordinate coordinate = this.faceCoordinate(point);
      return new SphereBlockAddress(coordinate.face(), coordinate.blockX(), coordinate.blockZ());
   }

   /** Returns the chunk containing a geodetic point. */
   public SphereChunkAddress chunkForGeo(GeoPoint point) {
      SphereBlockAddress block = this.blockForGeo(point);
      return new SphereChunkAddress(
         block.face(),
         Math.floorDiv(block.blockX(), (long)CHUNK_SIZE_BLOCKS),
         Math.floorDiv(block.blockZ(), (long)CHUNK_SIZE_BLOCKS)
      );
   }

   /**
    * Returns a cardinal neighboring block, including a face transition.
    *
    * <p>The neighbor is found by extending the source face ray by one cell and
    * re-owning that ray with the canonical face selection rule. This keeps
    * both sides of a face edge on the same spherical point instead of adding a
    * special-case seam offset.</p>
    */
   public SphereBlockAddress neighbor(SphereBlockAddress address, int deltaX, int deltaZ) {
      Objects.requireNonNull(address, "address");
      if (Math.abs(deltaX) + Math.abs(deltaZ) != 1) {
         throw new IllegalArgumentException("only cardinal neighbors are supported");
      }
      validateFace(address.face());
      validateFaceBlock(address.blockX(), address.blockZ());
      double centerX = address.blockX() + 0.5 + deltaX;
      double centerZ = address.blockZ() + 0.5 + deltaZ;
      FaceUv destination = faceUv(faceVector(
         address.face(),
         blockToFaceCoordinate(centerX, this.config.faceBlocks()),
         blockToFaceCoordinate(centerZ, this.config.faceBlocks())
      ));
      return new SphereBlockAddress(
         destination.face(),
         faceCoordinateToBlock(destination.u(), this.config.faceBlocks()),
         faceCoordinateToBlock(destination.v(), this.config.faceBlocks())
      );
   }

   /** Returns a local east/north offset from a chart center to a point. */
   public LocalOffset localOffset(ChartKey chart, GeoPoint point) {
      Objects.requireNonNull(chart, "chart");
      return localOffset(this.chartCenter(chart), point);
   }

   /** Returns a geodesic local offset between two points on the reference sphere. */
   public static LocalOffset localOffset(GeoPoint anchor, GeoPoint point) {
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(point, "point");
      Vector3 anchorVector = toVector(anchor);
      Vector3 pointVector = toVector(point);
      double cosine = clamp(anchorVector.dot(pointVector), -1.0, 1.0);
      double angularDistance = Math.acos(cosine);
      if (angularDistance <= EPSILON) {
         return new LocalOffset(0.0, 0.0);
      }

      Vector3 tangent = pointVector.subtract(anchorVector.scale(cosine)).normalized();
      Vector3 east = eastBasis(anchorVector);
      Vector3 north = east.cross(anchorVector).normalized();
      double distance = EARTH_RADIUS_METERS * angularDistance;
      return new LocalOffset(tangent.dot(east) * distance, tangent.dot(north) * distance);
   }

   /** Expands a chart-local offset with the configured truncation order. */
   public GeoPoint expand(ChartKey chart, LocalOffset offset) {
      Objects.requireNonNull(chart, "chart");
      return expandFrom(this.chartCenter(chart), offset, this.config.expansionOrder());
   }

   /** Conservative Taylor remainder bound for a local chart footprint. */
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

   /** Returns the exact spherical half-diagonal of a chart. */
   public double chartHalfDiagonalMeters(ChartKey chart) {
      Objects.requireNonNull(chart, "chart");
      GeoPoint center = this.chartCenter(chart);
      long originX = chart.originBlockX(this.config.chartSizeBlocks());
      long originZ = chart.originBlockZ(this.config.chartSizeBlocks());
      double size = this.config.chartSizeBlocks();
      double maxDistance = 0.0;
      for (int x = 0; x <= 1; x++) {
         for (int z = 0; z <= 1; z++) {
            GeoPoint corner = this.exactSurfacePoint(
               chart.face(),
               originX + x * size,
               originZ + z * size
            );
            maxDistance = Math.max(maxDistance, greatCircleDistanceMeters(center, corner));
         }
      }
      return maxDistance;
   }

   /** Selects the smallest order that satisfies the configured tolerance. */
   public int recommendedExpansionOrder(ChartKey chart) {
      double footprint = this.chartHalfDiagonalMeters(chart);
      for (int order = 1; order <= MAX_EXPANSION_ORDER; order++) {
         if (this.truncationErrorBoundMeters(footprint, order) <= this.config.truncationToleranceMeters()) {
            return order;
         }
      }
      return MAX_EXPANSION_ORDER;
   }

   /** Exact spherical exponential map with a truncated sin/cos evaluation. */
   public static GeoPoint expandFrom(GeoPoint anchor, LocalOffset offset, int expansionOrder) {
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(offset, "offset");
      int order = clampOrder(expansionOrder);
      double distance = Math.hypot(offset.eastMeters(), offset.northMeters());
      if (distance == 0.0) {
         return anchor;
      }

      Vector3 anchorVector = toVector(anchor);
      Vector3 east = eastBasis(anchorVector);
      Vector3 north = east.cross(anchorVector).normalized();
      Vector3 direction = east.scale(offset.eastMeters() / distance)
         .add(north.scale(offset.northMeters() / distance));
      double angularDistance = distance / EARTH_RADIUS_METERS;
      Vector3 result = anchorVector.scale(truncatedCos(angularDistance, order))
         .add(direction.scale(truncatedSin(angularDistance, order)))
         .normalized();
      return fromVector(result, anchor.elevationMeters());
   }

   public static double greatCircleDistanceMeters(GeoPoint first, GeoPoint second) {
      Vector3 firstVector = toVector(first);
      Vector3 secondVector = toVector(second);
      // atan2 keeps sub-metre distances measurable when the dot product has
      // rounded to exactly one, which is common for adjacent block samples.
      return EARTH_RADIUS_METERS * Math.atan2(
         firstVector.cross(secondVector).length(),
         clamp(firstVector.dot(secondVector), -1.0, 1.0)
      );
   }

   private FaceCoordinate faceCoordinate(GeoPoint point) {
      Objects.requireNonNull(point, "point");
      Vector3 vector = toVector(point);
      FaceUv faceUv = faceUv(vector);
      long blockX = faceCoordinateToBlock(faceUv.u(), this.config.faceBlocks());
      long blockZ = faceCoordinateToBlock(faceUv.v(), this.config.faceBlocks());
      return new FaceCoordinate(faceUv.face(), blockX, blockZ);
   }

   private void validateFaceBlock(long blockX, long blockZ) {
      if (blockX < 0L || blockX >= this.config.faceBlocks() || blockZ < 0L || blockZ >= this.config.faceBlocks()) {
         throw new IllegalArgumentException("block coordinate is outside the face");
      }
   }

   private static void validateFace(int face) {
      if (face < 0 || face >= FACE_COUNT) {
         throw new IllegalArgumentException("face must be in [0, 5]");
      }
   }

   private static double blockToFaceCoordinate(double block, int faceBlocks) {
      return block / faceBlocks * 2.0 - 1.0;
   }

   private static long faceCoordinateToBlock(double coordinate, int faceBlocks) {
      double normalized = clamp((coordinate + 1.0) * 0.5, 0.0, Math.nextDown(1.0));
      return Math.min(faceBlocks - 1L, (long)Math.floor(normalized * faceBlocks));
   }

   private static Vector3 faceVector(int face, double u, double v) {
      double alpha = u * HALF_FACE_ANGLE;
      double beta = v * HALF_FACE_ANGLE;
      double tanAlpha = Math.tan(alpha);
      double tanBeta = Math.tan(beta);
      return switch (face) {
         // The linear face coordinates are angular coordinates. The tangent
         // terms below are the standard equiangular cubed-sphere map; the
         // final normalization turns each cube ray into a unit sphere vector.
         case 0 -> new Vector3(1.0, tanBeta, -tanAlpha).normalized(); // +X
         case 1 -> new Vector3(-1.0, tanBeta, tanAlpha).normalized(); // -X
         case 2 -> new Vector3(tanAlpha, 1.0, -tanBeta).normalized(); // +Y
         case 3 -> new Vector3(tanAlpha, -1.0, tanBeta).normalized(); // -Y
         case 4 -> new Vector3(tanAlpha, tanBeta, 1.0).normalized(); // +Z
         case 5 -> new Vector3(-tanAlpha, tanBeta, -1.0).normalized(); // -Z
         default -> throw new IllegalArgumentException("face must be in [0, 5]");
      };
   }

   private static FaceUv faceUv(Vector3 vector) {
      double ax = Math.abs(vector.x());
      double ay = Math.abs(vector.y());
      double az = Math.abs(vector.z());
      if (ax >= ay && ax >= az) {
         if (vector.x() >= 0.0) {
            return new FaceUv(0, Math.atan2(-vector.z(), vector.x()) / HALF_FACE_ANGLE, Math.atan2(vector.y(), vector.x()) / HALF_FACE_ANGLE);
         }
         return new FaceUv(1, Math.atan2(vector.z(), -vector.x()) / HALF_FACE_ANGLE, Math.atan2(vector.y(), -vector.x()) / HALF_FACE_ANGLE);
      }
      if (ay >= az) {
         if (vector.y() >= 0.0) {
            return new FaceUv(2, Math.atan2(vector.x(), vector.y()) / HALF_FACE_ANGLE, Math.atan2(-vector.z(), vector.y()) / HALF_FACE_ANGLE);
         }
         return new FaceUv(3, Math.atan2(vector.x(), -vector.y()) / HALF_FACE_ANGLE, Math.atan2(vector.z(), -vector.y()) / HALF_FACE_ANGLE);
      }
      if (vector.z() >= 0.0) {
         return new FaceUv(4, Math.atan2(vector.x(), vector.z()) / HALF_FACE_ANGLE, Math.atan2(vector.y(), vector.z()) / HALF_FACE_ANGLE);
      }
      return new FaceUv(5, Math.atan2(-vector.x(), -vector.z()) / HALF_FACE_ANGLE, Math.atan2(vector.y(), -vector.z()) / HALF_FACE_ANGLE);
   }

   private static GeoPoint fromVector(Vector3 vector, double elevationMeters) {
      Vector3 normalized = vector.normalized();
      return new GeoPoint(
         Math.toDegrees(Math.asin(clamp(normalized.y(), -1.0, 1.0))),
         Math.toDegrees(Math.atan2(normalized.z(), normalized.x())),
         elevationMeters
      );
   }

   private static Vector3 toVector(GeoPoint point) {
      double latitude = Math.toRadians(point.latitude());
      double longitude = Math.toRadians(point.longitude());
      double cosLatitude = Math.cos(latitude);
      return new Vector3(
         cosLatitude * Math.cos(longitude),
         Math.sin(latitude),
         cosLatitude * Math.sin(longitude)
      );
   }

   private static Vector3 eastBasis(Vector3 anchor) {
      Vector3 east = new Vector3(-anchor.z(), 0.0, anchor.x());
      if (east.length() <= EPSILON) {
         east = new Vector3(1.0, 0.0, 0.0);
      }
      return east.normalized();
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
      for (int i = 2; i <= n; i++) {
         result *= i;
      }
      return result;
   }

   private static double clamp(double value, double low, double high) {
      return Math.max(low, Math.min(high, value));
   }

   public record Config(
      double worldScale,
      int faceBlocks,
      int chartSizeBlocks,
      int expansionOrder,
      double truncationToleranceMeters
   ) {
      public Config {
         if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) {
            throw new IllegalArgumentException("worldScale");
         }
         if (faceBlocks < CHUNK_SIZE_BLOCKS || faceBlocks % CHUNK_SIZE_BLOCKS != 0) {
            throw new IllegalArgumentException("faceBlocks must be a positive multiple of 16");
         }
         if (chartSizeBlocks < CHUNK_SIZE_BLOCKS || chartSizeBlocks % CHUNK_SIZE_BLOCKS != 0
            || faceBlocks % chartSizeBlocks != 0) {
            throw new IllegalArgumentException("chartSizeBlocks must divide faceBlocks and be a multiple of 16");
         }
         if (expansionOrder < 1 || expansionOrder > MAX_EXPANSION_ORDER) {
            throw new IllegalArgumentException("expansionOrder");
         }
         if (!(truncationToleranceMeters > 0.0) || !Double.isFinite(truncationToleranceMeters)) {
            throw new IllegalArgumentException("truncationToleranceMeters");
         }
      }

      public static Config defaults(double worldScale) {
         if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) {
            throw new IllegalArgumentException("worldScale");
         }
         int chartSize = 1024;
         double rawFaceBlocksValue = Math.ceil((Math.PI * EARTH_RADIUS_METERS * 0.5) / worldScale);
         if (!Double.isFinite(rawFaceBlocksValue) || rawFaceBlocksValue > Integer.MAX_VALUE - (long)chartSize) {
            throw new IllegalArgumentException("worldScale produces an unsupported face size");
         }
         long rawFaceBlocks = (long)rawFaceBlocksValue;
         long alignedFaceBlocks = ((rawFaceBlocks + chartSize - 1L) / chartSize) * chartSize;
         if (alignedFaceBlocks > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("worldScale produces an unsupported face size");
         }
         return new Config(worldScale, (int)alignedFaceBlocks, chartSize, 3, 0.25);
      }
   }

   public record ChartKey(int face, long x, long z) {
      public long originBlockX(int chartSizeBlocks) {
         return Math.multiplyExact(this.x, (long)chartSizeBlocks);
      }

      public long originBlockZ(int chartSizeBlocks) {
         return Math.multiplyExact(this.z, (long)chartSizeBlocks);
      }
   }

   public record SphereChunkAddress(int face, long chunkX, long chunkZ) {
      public long blockX() {
         return Math.multiplyExact(this.chunkX, (long)CHUNK_SIZE_BLOCKS);
      }

      public long blockZ() {
         return Math.multiplyExact(this.chunkZ, (long)CHUNK_SIZE_BLOCKS);
      }
   }

   public record SphereBlockAddress(int face, long blockX, long blockZ) {
   }

   public record GeoPoint(double latitude, double longitude, double elevationMeters) {
      public GeoPoint {
         if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(elevationMeters)) {
            throw new IllegalArgumentException("geodetic coordinates must be finite");
         }
         latitude = Math.max(-90.0, Math.min(90.0, latitude));
         longitude = normalizeLongitude(longitude);
      }

      private static double normalizeLongitude(double longitude) {
         double normalized = longitude % 360.0;
         if (normalized > 180.0) normalized -= 360.0;
         if (normalized <= -180.0) normalized += 360.0;
         return normalized;
      }
   }

   public record LocalOffset(double eastMeters, double northMeters) {
      public LocalOffset {
         if (!Double.isFinite(eastMeters) || !Double.isFinite(northMeters)) {
            throw new IllegalArgumentException("local offset must be finite");
         }
      }
   }

   private record FaceCoordinate(int face, long blockX, long blockZ) {
   }

   private record FaceUv(int face, double u, double v) {
   }

   private record Vector3(double x, double y, double z) {
      private Vector3 add(Vector3 other) {
         return new Vector3(this.x + other.x, this.y + other.y, this.z + other.z);
      }

      private Vector3 subtract(Vector3 other) {
         return new Vector3(this.x - other.x, this.y - other.y, this.z - other.z);
      }

      private Vector3 scale(double factor) {
         return new Vector3(this.x * factor, this.y * factor, this.z * factor);
      }

      private double dot(Vector3 other) {
         return this.x * other.x + this.y * other.y + this.z * other.z;
      }

      private Vector3 cross(Vector3 other) {
         return new Vector3(
            this.y * other.z - this.z * other.y,
            this.z * other.x - this.x * other.z,
            this.x * other.y - this.y * other.x
         );
      }

      private double length() {
         return Math.sqrt(this.dot(this));
      }

      private Vector3 normalized() {
         double length = this.length();
         if (!(length > EPSILON)) {
            throw new IllegalStateException("cannot normalize a zero vector");
         }
         return this.scale(1.0 / length);
      }
   }
}
