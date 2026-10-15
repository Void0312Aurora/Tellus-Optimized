package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/**
 * Canonical spherical coordinate model for the progressive global world.
 *
 * <p>This model deliberately does not use a world-wide map projection. The
 * reference surface is a cubed sphere with six conformal faces. A face is
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
   public static final String MAPPING_ID = ConformalCubedSphere.MAP_ID;
   private static final int MAX_EXPANSION_ORDER = 12;
   private static final double HALF_FACE_ANGLE = Math.PI * 0.25;
   private static final double EPSILON = 1.0E-12;
   private static final double MAPPING_DIFF_H = 1.0E-8;
   private static final SphereSurfaceMapping DEFAULT_MAPPING = new ConformalCubedSphere();

   private final Config config;
   private final SphereSurfaceMapping mapping;

   public SphereWorldModel(Config config) {
      this(config, DEFAULT_MAPPING);
   }

   public SphereWorldModel(Config config, SphereSurfaceMapping mapping) {
      this.config = Objects.requireNonNull(config, "config");
      this.mapping = Objects.requireNonNull(mapping, "mapping");
   }

   public static SphereWorldModel forWorldScale(double worldScale) {
      return new SphereWorldModel(Config.defaults(worldScale));
   }

   public Config config() {
      return this.config;
   }

   /** Stable mapping identifier persisted with the spherical world contract. */
   public String mappingId() {
      return this.mapping.id();
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
      return this.surfacePoint(address, this.prepareChart(this.chartForChunk(address)).expansionOrder());
   }

   public GeoPoint surfacePoint(SphereChunkAddress address, int expansionOrder) {
      Objects.requireNonNull(address, "address");
      ChartKey chart = this.chartForChunk(address);
      GeoPoint anchor = this.chartCenter(chart);
      GeoPoint exact = this.exactSurfacePoint(address);
      LocalOffset offset = localOffset(anchor, exact);
      return expandFrom(anchor, offset, expansionOrder);
   }

   /** Maps one block-cell center through its owning chart's truncated map. */
   public GeoPoint surfacePoint(SphereBlockAddress address) {
      Objects.requireNonNull(address, "address");
      ChartKey chart = this.chartForBlock(address.face(), address.blockX(), address.blockZ());
      return this.surfacePoint(this.prepareChart(chart), address);
   }

   /** Select the expansion once per chart request, without a global chart cache. */
   public PreparedChart prepareChart(ChartKey chart) {
      double footprint = this.chartHalfDiagonalMeters(chart);
      int order = Math.max(this.config.expansionOrder(), this.recommendedExpansionOrder(chart));
      double error = this.truncationErrorBoundMeters(footprint, order);
      if (error > this.config.truncationToleranceMeters()) {
         throw new IllegalArgumentException("chart is too large for the configured truncation tolerance");
      }
      return new PreparedChart(chart, this.chartCenter(chart), order, error);
   }

   public GeoPoint surfacePoint(PreparedChart chart, SphereBlockAddress address) {
      Objects.requireNonNull(chart, "chart");
      Objects.requireNonNull(address, "address");
      if (!this.chartForBlock(address.face(), address.blockX(), address.blockZ()).equals(chart.key())) {
         throw new IllegalArgumentException("block does not belong to the prepared chart");
      }
      GeoPoint exact = this.exactSurfacePoint(address.face(), address.blockX() + 0.5, address.blockZ() + 0.5);
      return expandFrom(chart.anchor(), localOffset(chart.anchor(), exact), chart.expansionOrder());
   }

   /** Exact cubed-sphere mapping for a chunk center. */
   public GeoPoint exactSurfacePoint(SphereChunkAddress address) {
      Objects.requireNonNull(address, "address");
      return this.exactSurfacePoint(address.face(), address.blockX() + 8.0, address.blockZ() + 8.0);
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
      return Objects.requireNonNull(this.mapping.point(face, u, v), "mapping point");
   }

   /**
    * Continue a face chart beyond an edge on its front hemisphere. This is a
    * continuous entity coordinate, not a block-cell lookup. The open bounds
    * exclude the tangent singularities at +/- pi/2.
    */
   public GeoPoint continuedSurfacePoint(int face, double blockX, double blockZ) {
      validateFace(face);
      double size = this.config.faceBlocks();
      if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)
         || blockX <= -size * 0.5 || blockX >= size * 1.5
         || blockZ <= -size * 0.5 || blockZ >= size * 1.5) {
         throw new IllegalArgumentException("coordinate is outside the face's front hemisphere");
      }
      return continuedNormalized(face,
         blockToFaceCoordinate(blockX, this.config.faceBlocks()),
         blockToFaceCoordinate(blockZ, this.config.faceBlocks()));
   }

   /** Own a spherical point without rounding its fractional block coordinate. */
   public ContinuousFaceCoordinate continuousForGeo(GeoPoint point) {
      Objects.requireNonNull(point, "point");
      return continuousCoordinate(this.mapping.inverse(point));
   }

   /**
    * Re-own a continued face coordinate and transport horizontal coordinate
    * vectors with the analytic differential of the chart change. This preserves
    * the ECEF tangent vector; it need not preserve its flat x/z Euclidean norm.
    */
   public FaceTransition transition(int face, double blockX, double blockZ) {
      // Validate before evaluating tangent functions.
      this.continuedSurfacePoint(face, blockX, blockZ);
      double u = blockToFaceCoordinate(blockX, this.config.faceBlocks());
      double v = blockToFaceCoordinate(blockZ, this.config.faceBlocks());
      SphereSurfaceMapping.FaceCoordinate destinationUv = continuedCoordinate(face, u, v);
      ContinuousFaceCoordinate destination = continuousCoordinate(destinationUv);
      TangentBasis sourceBasis = continuedBasis(face, u, v);
      TangentBasis targetBasis = mappingBasis(destination.face(), destinationUv.u(), destinationUv.v());
      double xx = targetBasis.x().dot(targetBasis.x());
      double xz = targetBasis.x().dot(targetBasis.z());
      double zz = targetBasis.z().dot(targetBasis.z());
      double determinant = xx * zz - xz * xz;
      double sxX = targetBasis.x().dot(sourceBasis.x());
      double szX = targetBasis.z().dot(sourceBasis.x());
      double sxZ = targetBasis.x().dot(sourceBasis.z());
      double szZ = targetBasis.z().dot(sourceBasis.z());
      return new FaceTransition(destination,
         (zz * sxX - xz * szX) / determinant,
         (zz * sxZ - xz * szZ) / determinant,
         (xx * szX - xz * sxX) / determinant,
         (xx * szZ - xz * sxZ) / determinant);
   }

   private ContinuousFaceCoordinate continuousCoordinate(SphereSurfaceMapping.FaceCoordinate coordinate) {
      double size = this.config.faceBlocks();
      return new ContinuousFaceCoordinate(coordinate.face(),
         clamp((coordinate.u() + 1.0) * 0.5 * size, 0.0, size),
         clamp((coordinate.v() + 1.0) * 0.5 * size, 0.0, size));
   }

   /** Maps a normalized face coordinate, extending only the small seam band. */
   private GeoPoint continuedNormalized(int face, double u, double v) {
      SphereSurfaceMapping.FaceCoordinate coordinate = continuedCoordinate(face, u, v);
      return this.mapping.point(coordinate.face(), coordinate.u(), coordinate.v());
   }

   private SphereSurfaceMapping.FaceCoordinate continuedCoordinate(int face, double u, double v) {
      double cu = clamp(u, -1.0, 1.0), cv = clamp(v, -1.0, 1.0);
      if (u == cu && v == cv) return new SphereSurfaceMapping.FaceCoordinate(face, u, v);
      SphereSurfaceMapping.FaceCoordinate target = boundaryCoordinate(face, u, v, cu, cv);
      TangentBasis sourceBasis = mappingBasis(face, cu, cv);
      TangentBasis targetBasis = mappingBasis(target.face(), target.u(), target.v());
      Vector3 displacement = sourceBasis.x().scale(u - cu).add(sourceBasis.z().scale(v - cv));
      double xx = targetBasis.x().dot(targetBasis.x());
      double xz = targetBasis.x().dot(targetBasis.z());
      double zz = targetBasis.z().dot(targetBasis.z());
      double determinant = xx * zz - xz * xz;
      double tx = targetBasis.x().dot(displacement);
      double tz = targetBasis.z().dot(displacement);
      double du = (zz * tx - xz * tz) / determinant;
      double dv = (xx * tz - xz * tx) / determinant;
      double targetU = target.u() + du, targetV = target.v() + dv;
      if (targetU < -1.0 || targetU > 1.0 || targetV < -1.0 || targetV > 1.0) {
         if (Math.abs(targetU - target.u()) + Math.abs(targetV - target.v()) < 1.0E-12) {
            throw new IllegalArgumentException("continued face chart exceeded seam depth");
         }
         return continuedCoordinate(target.face(), targetU, targetV);
      }
      return new SphereSurfaceMapping.FaceCoordinate(target.face(), targetU, targetV);
   }

   private SphereSurfaceMapping.FaceCoordinate boundaryCoordinate(int face, double u, double v,
      double cu, double cv) {
      FaceUv owner = faceUv(faceVector(face, u, v));
      if ((cu == -1.0 || cu == 1.0) && (cv == -1.0 || cv == 1.0)) {
         return new SphereSurfaceMapping.FaceCoordinate(owner.face(),
            Math.copySign(1.0, owner.u()), Math.copySign(1.0, owner.v()));
      }
      GeoPoint boundary = this.mapping.point(face, cu, cv);
      return this.mapping.inverseOnFace(owner.face(), boundary);
   }

   /** Numerical tangent basis of the active mapping in normalized face units. */
   private TangentBasis mappingBasis(int face, double u, double v) {
      double h = MAPPING_DIFF_H;
      double ul = clamp(u - h, -1.0, 1.0), uh = clamp(u + h, -1.0, 1.0);
      double vl = clamp(v - h, -1.0, 1.0), vh = clamp(v + h, -1.0, 1.0);
      Vector3 center = toVector(this.mapping.point(face, u, v));
      Vector3 du = toVector(this.mapping.point(face, uh, v)).subtract(toVector(this.mapping.point(face, ul, v)))
         .scale(1.0 / Math.max(EPSILON, uh - ul));
      Vector3 dv = toVector(this.mapping.point(face, u, vh)).subtract(toVector(this.mapping.point(face, u, vl)))
         .scale(1.0 / Math.max(EPSILON, vh - vl));
      return new TangentBasis(projectTangent(center, du), projectTangent(center, dv));
   }

   /** Numerical tangent basis that remains defined just outside a face edge. */
   private TangentBasis continuedBasis(int face, double u, double v) {
      double h = MAPPING_DIFF_H;
      Vector3 center = toVector(continuedNormalized(face, u, v));
      Vector3 du = toVector(continuedNormalized(face, u + h, v))
         .subtract(toVector(continuedNormalized(face, u - h, v))).scale(1.0 / (2.0 * h));
      Vector3 dv = toVector(continuedNormalized(face, u, v + h))
         .subtract(toVector(continuedNormalized(face, u, v - h))).scale(1.0 / (2.0 * h));
      return new TangentBasis(projectTangent(center, du), projectTangent(center, dv));
   }

   private static Vector3 projectTangent(Vector3 center, Vector3 derivative) {
      return derivative.subtract(center.scale(center.dot(derivative)));
   }

   private TangentBasis faceBasis(int face, double u, double v) {
      double a = Math.tan(u * HALF_FACE_ANGLE), b = Math.tan(v * HALF_FACE_ANGLE);
      Vector3 ray;
      Vector3 da;
      Vector3 db;
      switch (face) {
         case 0 -> { ray = new Vector3(1, b, -a); da = new Vector3(0, 0, -1); db = new Vector3(0, 1, 0); }
         case 1 -> { ray = new Vector3(-1, b, a); da = new Vector3(0, 0, 1); db = new Vector3(0, 1, 0); }
         case 2 -> { ray = new Vector3(a, 1, -b); da = new Vector3(1, 0, 0); db = new Vector3(0, 0, -1); }
         case 3 -> { ray = new Vector3(a, -1, b); da = new Vector3(1, 0, 0); db = new Vector3(0, 0, 1); }
         case 4 -> { ray = new Vector3(a, b, 1); da = new Vector3(1, 0, 0); db = new Vector3(0, 1, 0); }
         case 5 -> { ray = new Vector3(-a, b, -1); da = new Vector3(-1, 0, 0); db = new Vector3(0, 1, 0); }
         default -> throw new IllegalArgumentException("face");
      }
      Vector3 normal = ray.normalized();
      double anglePerBlock = Math.PI * 0.5 / this.config.faceBlocks();
      Vector3 dx = da.scale((1 + a * a) * anglePerBlock);
      Vector3 dz = db.scale((1 + b * b) * anglePerBlock);
      return new TangentBasis(
         dx.subtract(normal.scale(normal.dot(dx))).scale(1.0 / ray.length()),
         dz.subtract(normal.scale(normal.dot(dz))).scale(1.0 / ray.length()));
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
      SphereSurfaceMapping.FaceCoordinate destination = continuedCoordinate(address.face(),
         blockToFaceCoordinate(centerX, this.config.faceBlocks()),
         blockToFaceCoordinate(centerZ, this.config.faceBlocks()));
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
      double angularDistance = Math.atan2(anchorVector.cross(pointVector).length(), cosine);
      if (angularDistance <= EPSILON) {
         return new LocalOffset(0.0, 0.0);
      }

      Vector3 tangent = pointVector.subtract(anchorVector.scale(cosine)).normalized();
      Vector3 east = eastBasis(anchorVector);
      Vector3 north = east.cross(anchorVector).normalized();
      double distance = EARTH_RADIUS_METERS * angularDistance;
      return new LocalOffset(tangent.dot(east) * distance, tangent.dot(north) * distance);
   }

   /**
    * Returns the right-handed local frame used by a future client/collision
    * adapter. East and north lie in the reference-sphere tangent plane and up
    * is the outward radial direction. The frame is a conversion contract only;
    * it does not change the engine's flat ChunkPos or collision implementation.
    */
   public TangentFrame tangentFrame(GeoPoint point) {
      Objects.requireNonNull(point, "point");
      Vector3 up = toVector(point);
      Vector3 east = eastBasis(up);
      Vector3 north = east.cross(up).normalized();
      return new TangentFrame(point,
         new EcefVector(east.x(), east.y(), east.z()),
         new EcefVector(north.x(), north.y(), north.z()),
         new EcefVector(up.x(), up.y(), up.z()));
   }

   /** Expands a chart-local offset with the configured truncation order. */
   public GeoPoint expand(ChartKey chart, LocalOffset offset) {
      Objects.requireNonNull(chart, "chart");
      Objects.requireNonNull(offset, "offset");
      double footprint = Math.max(this.chartHalfDiagonalMeters(chart), Math.hypot(offset.eastMeters(), offset.northMeters()));
      int order = Math.max(this.config.expansionOrder(), this.recommendedExpansionOrderFor(footprint));
      if (this.truncationErrorBoundMeters(footprint, order) > this.config.truncationToleranceMeters()) {
         throw new IllegalArgumentException("offset is too large for the configured truncation tolerance");
      }
      return expandFrom(this.chartCenter(chart), offset, order);
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
      return this.recommendedExpansionOrderFor(this.chartHalfDiagonalMeters(chart));
   }

   private int recommendedExpansionOrderFor(double footprint) {
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
      SphereSurfaceMapping.FaceCoordinate coordinate = this.mapping.inverse(point);
      long blockX = faceCoordinateToBlock(coordinate.u(), this.config.faceBlocks());
      long blockZ = faceCoordinateToBlock(coordinate.v(), this.config.faceBlocks());
      return new FaceCoordinate(coordinate.face(), blockX, blockZ);
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

   public record PreparedChart(ChartKey key, GeoPoint anchor, int expansionOrder, double errorBoundMeters) {
      public PreparedChart {
         Objects.requireNonNull(key, "key");
         Objects.requireNonNull(anchor, "anchor");
         if (expansionOrder < 1 || expansionOrder > MAX_EXPANSION_ORDER
            || !(errorBoundMeters >= 0.0) || !Double.isFinite(errorBoundMeters)) {
            throw new IllegalArgumentException("invalid prepared chart");
         }
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

   public record ContinuousFaceCoordinate(int face, double blockX, double blockZ) { }

   public record HorizontalVector(double x, double z) { }

   public record FaceTransition(ContinuousFaceCoordinate destination, double xx, double xz, double zx, double zz) {
      public HorizontalVector transport(double x, double z) {
         return new HorizontalVector(this.xx * x + this.xz * z, this.zx * x + this.zz * z);
      }

      /** Minecraft yaw: zero faces +Z, positive yaw turns toward -X. */
      public float transportYaw(float yawDegrees) {
         double yaw = Math.toRadians(yawDegrees);
         HorizontalVector direction = this.transport(-Math.sin(yaw), Math.cos(yaw));
         return (float)Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
      }
   }

   private record TangentBasis(Vector3 x, Vector3 z) { }

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

   /** A right-handed east/north/up frame anchored at a geodetic point. */
   public record TangentFrame(GeoPoint origin, EcefVector east, EcefVector north, EcefVector up) {
      public TangentFrame {
         Objects.requireNonNull(origin, "origin");
         Objects.requireNonNull(east, "east");
         Objects.requireNonNull(north, "north");
         Objects.requireNonNull(up, "up");
         if (Math.abs(east.length() - 1.0) > 1.0E-12
            || Math.abs(north.length() - 1.0) > 1.0E-12
            || Math.abs(up.length() - 1.0) > 1.0E-12
            || Math.abs(east.dot(north)) > 1.0E-12
            || Math.abs(east.dot(up)) > 1.0E-12
            || Math.abs(north.dot(up)) > 1.0E-12) {
            throw new IllegalArgumentException("frame basis must be orthonormal");
         }
      }

      /** Converts local metres into an ECEF displacement in metres. */
      public EcefVector toEcef(LocalVector local) {
         Objects.requireNonNull(local, "local");
         return east.scale(local.eastMeters())
            .add(north.scale(local.northMeters()))
            .add(up.scale(local.upMeters()));
      }

      /** Projects an ECEF displacement in metres into east/north/up metres. */
      public LocalVector fromEcef(EcefVector displacement) {
         Objects.requireNonNull(displacement, "displacement");
         return new LocalVector(east.dot(displacement), north.dot(displacement), up.dot(displacement));
      }
   }

   /** Cartesian displacement or unit direction in the Earth-centered frame. */
   public record EcefVector(double x, double y, double z) {
      public EcefVector {
         if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("ECEF vector must be finite");
         }
      }

      public double dot(EcefVector other) {
         Objects.requireNonNull(other, "other");
         return this.x * other.x + this.y * other.y + this.z * other.z;
      }

      public EcefVector add(EcefVector other) {
         Objects.requireNonNull(other, "other");
         return new EcefVector(this.x + other.x, this.y + other.y, this.z + other.z);
      }

      public EcefVector scale(double factor) {
         if (!Double.isFinite(factor)) throw new IllegalArgumentException("factor must be finite");
         return new EcefVector(this.x * factor, this.y * factor, this.z * factor);
      }

      public double length() {
         return Math.sqrt(this.dot(this));
      }
   }

   /** Local tangent-frame displacement in metres. */
   public record LocalVector(double eastMeters, double northMeters, double upMeters) {
      public LocalVector {
         if (!Double.isFinite(eastMeters) || !Double.isFinite(northMeters) || !Double.isFinite(upMeters)) {
            throw new IllegalArgumentException("local vector must be finite");
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
