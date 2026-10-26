package com.yucareux.tellus.worldgen.global;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Standalone RFC Phase 0/1 prototype. It defines persistent patch/cell
 * identities and measures the metric of candidate local spherical charts. It
 * intentionally does not adapt Minecraft storage or replace SphereWorldModel.
 */
public final class SphericalLatticePrototype {
   private static final double EPSILON_METERS = 0.01;

   private SphericalLatticePrototype() { }

   public enum ChartKind { EXPONENTIAL, STEREOGRAPHIC }

   public enum Edge { WEST, EAST, NORTH, SOUTH;
      Edge opposite() {
         return switch (this) {
            case WEST -> EAST;
            case EAST -> WEST;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
         };
      }
   }

   public record GridVersion(String planetId, int version) {
      public GridVersion {
         if (planetId == null || planetId.isBlank() || version < 1) throw new IllegalArgumentException("grid version");
      }
   }

   public record PatchKey(int level, long x, long y) implements Comparable<PatchKey> {
      public PatchKey {
         if (level < 0) throw new IllegalArgumentException("level");
      }

      @Override public int compareTo(PatchKey other) {
         int result = Integer.compare(this.level, other.level);
         if (result == 0) result = Long.compare(this.x, other.x);
         if (result == 0) result = Long.compare(this.y, other.y);
         return result;
      }
   }

   /** The immutable identity of one persistent cell, including vertical block. */
   public record BlockId(GridVersion grid, PatchKey patch, long i, long j, int k) {
      public BlockId {
         Objects.requireNonNull(grid, "grid");
         Objects.requireNonNull(patch, "patch");
         if (i < 0 || j < 0) throw new IllegalArgumentException("cell indices");
      }
   }

   public record PatchDescriptor(
      GridVersion grid,
      PatchKey key,
      SphereWorldModel.GeoPoint anchor,
      double sideMeters,
      int cellsPerSide,
      ChartKind chart
   ) {
      public PatchDescriptor {
         Objects.requireNonNull(grid, "grid");
         Objects.requireNonNull(key, "key");
         Objects.requireNonNull(anchor, "anchor");
         Objects.requireNonNull(chart, "chart");
         if (!(sideMeters > 0.0) || !Double.isFinite(sideMeters) || cellsPerSide < 1) {
            throw new IllegalArgumentException("patch dimensions");
         }
      }

      public double halfSideMeters() { return this.sideMeters * 0.5; }

      public BlockId cell(long i, long j, int k) {
         if (i < 0 || j < 0 || i >= this.cellsPerSide || j >= this.cellsPerSide) {
            throw new IllegalArgumentException("cell outside patch");
         }
         return new BlockId(this.grid, this.key, i, j, k);
      }

      public SphereWorldModel.GeoPoint pointAt(double xMeters, double yMeters) {
         if (!Double.isFinite(xMeters) || !Double.isFinite(yMeters)) throw new IllegalArgumentException("chart coordinate");
         return switch (this.chart) {
            case EXPONENTIAL -> SphereWorldModel.expandFrom(
               this.anchor, new SphereWorldModel.LocalOffset(xMeters, yMeters), 12);
            case STEREOGRAPHIC -> stereographic(this.anchor, xMeters, yMeters);
         };
      }

      public SphereWorldModel.GeoPoint cellCenter(long i, long j) {
         if (i < 0 || j < 0 || i >= this.cellsPerSide || j >= this.cellsPerSide) {
            throw new IllegalArgumentException("cell outside patch");
         }
         double cell = this.sideMeters / this.cellsPerSide;
         return this.pointAt((i + 0.5) * cell - this.halfSideMeters(),
            (j + 0.5) * cell - this.halfSideMeters());
      }

      /** Inverse chart coordinates used for deterministic ownership. */
      public ChartCoordinate localCoordinates(SphereWorldModel.GeoPoint point) {
         Vector3 center = Vector3.from(this.anchor);
         Vector3 p = Vector3.from(point);
         Vector3 east = tangentEast(center);
         Vector3 north = east.cross(center).normalized();
         if (this.chart == ChartKind.EXPONENTIAL) {
            SphereWorldModel.LocalOffset offset = SphereWorldModel.localOffset(this.anchor, point);
            return new ChartCoordinate(offset.eastMeters(), offset.northMeters());
         }
         double denominator = 1.0 + p.dot(center);
         if (denominator <= 1.0E-12) throw new IllegalArgumentException("point is outside stereographic hemisphere");
         return new ChartCoordinate(
            2.0 * SphereWorldModel.EARTH_RADIUS_METERS * p.dot(east) / denominator,
            2.0 * SphereWorldModel.EARTH_RADIUS_METERS * p.dot(north) / denominator);
      }

      private static Vector3 tangentEast(Vector3 center) {
         Vector3 east = new Vector3(-center.z, 0.0, center.x);
         return east.length() <= 1.0E-12 ? new Vector3(1.0, 0.0, 0.0) : east.normalized();
      }

      private static SphereWorldModel.GeoPoint stereographic(
         SphereWorldModel.GeoPoint anchor, double xMeters, double yMeters
      ) {
         double radius = SphereWorldModel.EARTH_RADIUS_METERS;
         double u = xMeters / (2.0 * radius), v = yMeters / (2.0 * radius);
         double q = u * u + v * v;
         Vector3 center = Vector3.from(anchor);
         Vector3 east = tangentEast(center);
         Vector3 north = east.cross(center).normalized();
         Vector3 result = center.scale((1.0 - q) / (1.0 + q))
            .add(east.scale(2.0 * u / (1.0 + q)))
            .add(north.scale(2.0 * v / (1.0 + q)));
         return Vector3.toGeoPoint(result.normalized());
      }
   }

   public record ChartCoordinate(double eastMeters, double northMeters) { }

   public record MetricSample(
      double minScale,
      double maxScale,
      double condition,
      double areaScale,
      double angleErrorRadians
   ) {
      public MetricSample {
         if (!(minScale > 0.0) || !(maxScale >= minScale) || !Double.isFinite(condition)
            || !Double.isFinite(areaScale) || !Double.isFinite(angleErrorRadians)) {
            throw new IllegalArgumentException("metric sample");
         }
      }
   }

   public record DistortionReport(
      ChartKind chart,
      double sideMeters,
      int samplesPerAxis,
      double minScale,
      double maxScale,
      double maxCondition,
      double minAreaScale,
      double maxAreaScale,
      double maxAngleErrorRadians
   ) {
      public double maxRelativeScaleError() {
         return Math.max(Math.abs(this.minScale - 1.0), Math.abs(this.maxScale - 1.0));
      }
   }

   /** Measurement of the current fixed equiangular cubed-sphere baseline. */
   public record CubedSphereReport(
      int samplesPerAxis,
      double minScale,
      double maxScale,
      double maxCondition,
      double minAreaScale,
      double maxAreaScale,
      double maxAngleErrorRadians
   ) { }

   /** Numerically measures a finite patch; the result is deterministic. */
   public static DistortionReport measurePatch(PatchDescriptor patch, int samplesPerAxis) {
      Objects.requireNonNull(patch, "patch");
      if (samplesPerAxis < 2) throw new IllegalArgumentException("samplesPerAxis");
      double minScale = Double.POSITIVE_INFINITY, maxScale = 0.0, maxCondition = 0.0;
      double minArea = Double.POSITIVE_INFINITY, maxArea = 0.0, maxAngle = 0.0;
      for (int ix = 0; ix < samplesPerAxis; ix++) {
         for (int iy = 0; iy < samplesPerAxis; iy++) {
            double x = (ix / (double)(samplesPerAxis - 1) - 0.5) * patch.sideMeters();
            double y = (iy / (double)(samplesPerAxis - 1) - 0.5) * patch.sideMeters();
            MetricSample sample = metricAt(patch, x, y);
            minScale = Math.min(minScale, sample.minScale());
            maxScale = Math.max(maxScale, sample.maxScale());
            maxCondition = Math.max(maxCondition, sample.condition());
            minArea = Math.min(minArea, sample.areaScale());
            maxArea = Math.max(maxArea, sample.areaScale());
            maxAngle = Math.max(maxAngle, sample.angleErrorRadians());
         }
      }
      return new DistortionReport(patch.chart(), patch.sideMeters(), samplesPerAxis,
         minScale, maxScale, maxCondition, minArea, maxArea, maxAngle);
   }

   /**
    * Measures one face of the existing six-face mapping in units of its
    * nominal center scale. All six faces have the same extrema by symmetry.
    */
   public static CubedSphereReport measureCubedSphere(int samplesPerAxis) {
      if (samplesPerAxis < 2) throw new IllegalArgumentException("samplesPerAxis");
      int faceBlocks = 4096;
      SphereWorldModel model = new SphereWorldModel(new SphereWorldModel.Config(
         1.0, faceBlocks, 16, 3, 0.25));
      double nominal = Math.PI * SphereWorldModel.EARTH_RADIUS_METERS * 0.5 / faceBlocks;
      double minScale = Double.POSITIVE_INFINITY, maxScale = 0.0, maxCondition = 0.0;
      double minArea = Double.POSITIVE_INFINITY, maxArea = 0.0, maxAngle = 0.0;
      double step = Math.max(0.01, faceBlocks / (double)(samplesPerAxis * 10));
      for (int ix = 0; ix < samplesPerAxis; ix++) {
         for (int iy = 0; iy < samplesPerAxis; iy++) {
            double x = step + 0.5 + ix * (faceBlocks - 1.0 - 2.0 * step) / (samplesPerAxis - 1.0);
            double y = step + 0.5 + iy * (faceBlocks - 1.0 - 2.0 * step) / (samplesPerAxis - 1.0);
            Vector3 px0 = Vector3.from(model.exactSurfacePoint(0, x - step, y));
            Vector3 px1 = Vector3.from(model.exactSurfacePoint(0, x + step, y));
            Vector3 py0 = Vector3.from(model.exactSurfacePoint(0, x, y - step));
            Vector3 py1 = Vector3.from(model.exactSurfacePoint(0, x, y + step));
            Vector3 dx = px1.subtract(px0).scale(SphereWorldModel.EARTH_RADIUS_METERS / (2.0 * step * nominal));
            Vector3 dy = py1.subtract(py0).scale(SphereWorldModel.EARTH_RADIUS_METERS / (2.0 * step * nominal));
            MetricSample sample = metricFromVectors(dx, dy);
            minScale = Math.min(minScale, sample.minScale());
            maxScale = Math.max(maxScale, sample.maxScale());
            maxCondition = Math.max(maxCondition, sample.condition());
            minArea = Math.min(minArea, sample.areaScale());
            maxArea = Math.max(maxArea, sample.areaScale());
            maxAngle = Math.max(maxAngle, sample.angleErrorRadians());
         }
      }
      return new CubedSphereReport(samplesPerAxis, minScale, maxScale, maxCondition,
         minArea, maxArea, maxAngle);
   }

   private static MetricSample metricAt(PatchDescriptor patch, double x, double y) {
      double h = Math.max(EPSILON_METERS, patch.sideMeters() / patch.cellsPerSide() / 16.0);
      Vector3 px0 = Vector3.from(patch.pointAt(x - h, y));
      Vector3 px1 = Vector3.from(patch.pointAt(x + h, y));
      Vector3 py0 = Vector3.from(patch.pointAt(x, y - h));
      Vector3 py1 = Vector3.from(patch.pointAt(x, y + h));
      Vector3 dx = px1.subtract(px0).scale(SphereWorldModel.EARTH_RADIUS_METERS / (2.0 * h));
      Vector3 dy = py1.subtract(py0).scale(SphereWorldModel.EARTH_RADIUS_METERS / (2.0 * h));
      double gxx = dx.dot(dx), gxy = dx.dot(dy), gyy = dy.dot(dy);
      double trace = gxx + gyy;
      double discriminant = Math.sqrt(Math.max(0.0, (gxx - gyy) * (gxx - gyy) + 4.0 * gxy * gxy));
      double largest = Math.max(1.0E-30, (trace + discriminant) * 0.5);
      double smallest = Math.max(1.0E-30, (trace - discriminant) * 0.5);
      double max = Math.sqrt(largest), min = Math.sqrt(smallest);
      double area = Math.sqrt(Math.max(0.0, gxx * gyy - gxy * gxy));
      return metricFromValues(min, max, area, gxx, gyy, gxy);
   }

   private static MetricSample metricFromVectors(Vector3 dx, Vector3 dy) {
      double gxx = dx.dot(dx), gxy = dx.dot(dy), gyy = dy.dot(dy);
      double trace = gxx + gyy;
      double discriminant = Math.sqrt(Math.max(0.0, (gxx - gyy) * (gxx - gyy) + 4.0 * gxy * gxy));
      double largest = Math.max(1.0E-30, (trace + discriminant) * 0.5);
      double smallest = Math.max(1.0E-30, (trace - discriminant) * 0.5);
      return metricFromValues(Math.sqrt(smallest), Math.sqrt(largest),
         Math.sqrt(Math.max(0.0, gxx * gyy - gxy * gxy)), gxx, gyy, gxy);
   }

   private static MetricSample metricFromValues(double min, double max, double area, double gxx, double gyy, double gxy) {
      double cosine = gxy / Math.max(1.0E-30, Math.sqrt(gxx * gyy));
      cosine = Math.max(-1.0, Math.min(1.0, cosine));
      double angle = Math.abs(Math.acos(cosine) - Math.PI * 0.5);
      return new MetricSample(min, max, max / min, area, angle);
   }

   /**
    * Finite deterministic patch atlas. Ownership is lowest PatchKey among all
    * patches containing a point, so descriptor insertion order cannot alter it.
    */
   public static final class Atlas {
      private final GridVersion grid;
      private final Map<PatchKey, PatchDescriptor> patches;
      private final Map<BoundaryKey, BoundaryLink> links;
      private final Map<LocatorBucket, List<PatchKey>> locator;
      private final List<PatchKey> broadPatches;

      public Atlas(List<PatchDescriptor> descriptors, List<BoundaryLink> boundaryLinks) {
         if (descriptors == null || descriptors.isEmpty()) throw new IllegalArgumentException("patches");
         this.grid = Objects.requireNonNull(descriptors.get(0).grid(), "grid");
         this.patches = new HashMap<>();
         for (PatchDescriptor descriptor : descriptors) {
            if (!this.grid.equals(descriptor.grid())) throw new IllegalArgumentException("mixed grid versions");
            if (this.patches.put(descriptor.key(), descriptor) != null) throw new IllegalArgumentException("duplicate patch");
         }
         this.links = new HashMap<>();
         for (BoundaryLink link : boundaryLinks == null ? List.<BoundaryLink>of() : boundaryLinks) {
            if (!this.patches.containsKey(link.source()) || !this.patches.containsKey(link.target())) {
               throw new IllegalArgumentException("boundary references unknown patch");
            }
            BoundaryKey key = new BoundaryKey(link.source(), link.sourceEdge());
            if (this.links.put(key, link) != null) throw new IllegalArgumentException("duplicate boundary link");
         }
         for (BoundaryLink link : this.links.values()) {
            PatchDescriptor source = this.patches.get(link.source());
            PatchDescriptor target = this.patches.get(link.target());
            if (source.cellsPerSide() != target.cellsPerSide()) {
               throw new IllegalArgumentException("integer-grid boundary requires matching cell counts");
            }
            BoundaryLink reverse = this.links.get(new BoundaryKey(link.target(), link.targetEdge()));
            if (reverse == null || !reverse.target().equals(link.source())
               || reverse.sourceEdge() != link.targetEdge() || reverse.targetEdge() != link.sourceEdge()
               || reverse.reversed() != link.reversed()) {
               throw new IllegalArgumentException("boundary links must be reciprocal: " + link);
            }
         }
         this.locator = new HashMap<>();
         this.broadPatches = new ArrayList<>();
         this.buildLocator();
      }

      public GridVersion grid() { return this.grid; }

      public List<PatchDescriptor> patches() {
         return this.patches.values().stream().sorted(Comparator.comparing(PatchDescriptor::key)).toList();
      }

      public List<BoundaryLink> boundaryLinks() {
         return this.links.values().stream()
            .sorted(Comparator.comparing(BoundaryLink::source).thenComparing(BoundaryLink::sourceEdge)
               .thenComparing(BoundaryLink::target).thenComparing(BoundaryLink::targetEdge))
            .toList();
      }

      public PatchDescriptor patch(PatchKey key) {
         return Objects.requireNonNull(this.patches.get(key), "unknown patch");
      }

      /**
       * Returns the canonical owner of a direct cell identity under the
       * prototype's center-ownership rule. Overlapping non-owner cells are
       * therefore explicit ghosts and cannot be treated as mutable cells.
       */
      public BlockId canonical(BlockId cell) {
         Objects.requireNonNull(cell, "cell");
         if (!this.grid.equals(cell.grid())) throw new IllegalArgumentException("mixed grid versions");
         PatchDescriptor patch = this.patch(cell.patch());
         patch.cell(cell.i(), cell.j(), cell.k());
         BlockId owner = this.owner(patch.cellCenter(cell.i(), cell.j()));
         return new BlockId(this.grid, owner.patch(), owner.i(), owner.j(), cell.k());
      }

      public boolean isCanonical(BlockId cell) {
         return Objects.requireNonNull(cell, "cell").equals(this.canonical(cell));
      }

      public BlockId owner(SphereWorldModel.GeoPoint point) {
         Objects.requireNonNull(point, "point");
         return this.candidatePatches(point).stream()
            .map(this.patches::get)
            .filter(patch -> withinCap(patch, point) && contains(patch, point))
            .min(Comparator.comparing(PatchDescriptor::key))
            .map(patch -> {
               ChartCoordinate offset = patch.localCoordinates(point);
               double cell = patch.sideMeters() / patch.cellsPerSide();
               long i = Math.max(0L, Math.min(patch.cellsPerSide() - 1L,
                  (long)Math.floor((offset.eastMeters() + patch.halfSideMeters()) / cell)));
               long j = Math.max(0L, Math.min(patch.cellsPerSide() - 1L,
                  (long)Math.floor((offset.northMeters() + patch.halfSideMeters()) / cell)));
               return patch.cell(i, j, 0);
            }).orElseThrow(() -> new IllegalArgumentException("point is outside finite atlas"));
      }

      /** Number of coarse-index candidates before exact chart containment. */
      public int candidatePatchCount(SphereWorldModel.GeoPoint point) {
         return this.candidatePatches(Objects.requireNonNull(point, "point")).size();
      }

      public BlockId neighbor(BlockId cell, int dx, int dz) {
         if (Math.abs(dx) + Math.abs(dz) != 1) throw new IllegalArgumentException("cardinal neighbor only");
         if (!this.isCanonical(cell)) throw new IllegalArgumentException("noncanonical cell has no mutable neighbor");
         PatchDescriptor patch = this.patch(cell.patch());
         long i = cell.i() + dx, j = cell.j() + dz;
         if (i >= 0 && j >= 0 && i < patch.cellsPerSide() && j < patch.cellsPerSide()) {
            return canonicalNeighbor(patch.cell(i, j, cell.k()));
         }
         Edge edge = i < 0 ? Edge.WEST : i >= patch.cellsPerSide() ? Edge.EAST
            : j < 0 ? Edge.SOUTH : Edge.NORTH;
         BoundaryLink link = this.links.get(new BoundaryKey(patch.key(), edge));
         if (link == null) throw new IllegalStateException("unlinked patch boundary: " + patch.key() + " " + edge);
         long along = edge == Edge.WEST || edge == Edge.EAST ? cell.j() : cell.i();
         long mapped = link.reversed() ? patch.cellsPerSide() - 1L - along : along;
         PatchDescriptor target = this.patch(link.target());
         long targetI = link.targetEdge() == Edge.WEST ? 0 : link.targetEdge() == Edge.EAST ? target.cellsPerSide() - 1 : mapped;
         long targetJ = link.targetEdge() == Edge.SOUTH ? 0 : link.targetEdge() == Edge.NORTH ? target.cellsPerSide() - 1 : mapped;
         BlockId destination = target.cell(targetI, targetJ, cell.k());
         return canonicalNeighbor(destination);
      }

      private BlockId canonicalNeighbor(BlockId destination) {
         if (!this.isCanonical(destination)) {
            throw new IllegalStateException("neighbor resolves to a noncanonical overlap cell: " + destination);
         }
         return destination;
      }

      private static boolean contains(PatchDescriptor patch, SphereWorldModel.GeoPoint point) {
         ChartCoordinate offset;
         try {
            offset = patch.localCoordinates(point);
         } catch (IllegalArgumentException outsideChart) {
            return false;
         }
         return Math.abs(offset.eastMeters()) <= patch.halfSideMeters() + EPSILON_METERS
            && Math.abs(offset.northMeters()) <= patch.halfSideMeters() + EPSILON_METERS;
      }

      private void buildLocator() {
         for (PatchDescriptor patch : this.patches.values()) {
            double radiusRadians = (patch.sideMeters() / Math.sqrt(2.0) + EPSILON_METERS)
               / SphereWorldModel.EARTH_RADIUS_METERS;
            double radiusDegrees = Math.toDegrees(radiusRadians);
            if (radiusDegrees >= 90.0) {
               this.broadPatches.add(patch.key());
               continue;
            }
            double minLatitude = Math.max(-90.0, patch.anchor().latitude() - radiusDegrees);
            double maxLatitude = Math.min(90.0, patch.anchor().latitude() + radiusDegrees);
            double longitudeHalfWidth = longitudeHalfWidthDegrees(patch.anchor().latitude(), radiusRadians);
            int minLatBucket = latitudeBucket(minLatitude);
            int maxLatBucket = latitudeBucket(maxLatitude);
            for (int lat = minLatBucket; lat <= maxLatBucket; lat++) {
               addLongitudeRange(patch.key(), lat, patch.anchor().longitude() - longitudeHalfWidth,
                  patch.anchor().longitude() + longitudeHalfWidth);
            }
         }
      }

      /**
       * Returns a conservative longitude half-width for a spherical cap.
       * Treating angular distance as a longitude delta is unsound near the
       * poles; the cap may cover every longitude once it reaches a pole.
       */
      private static double longitudeHalfWidthDegrees(double latitude, double radiusRadians) {
         double absoluteLatitude = Math.abs(Math.toRadians(latitude));
         double poleDistance = Math.PI * 0.5 - absoluteLatitude;
         if (radiusRadians >= poleDistance) return 180.0;
         double denominator = Math.cos(absoluteLatitude);
         double ratio = Math.sin(radiusRadians) / Math.max(1.0E-15, denominator);
         if (ratio >= 1.0) return 180.0;
         return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, ratio))));
      }

      private void addLongitudeRange(PatchKey key, int latitude, double minimum, double maximum) {
         double width = maximum - minimum;
         if (width >= 360.0) {
            for (int longitude = 0; longitude < LONGITUDE_BUCKETS; longitude++) {
               this.locator.computeIfAbsent(new LocatorBucket(latitude, longitude), ignored -> new ArrayList<>()).add(key);
            }
            return;
         }
         while (minimum < -180.0) {
            minimum += 360.0;
            maximum += 360.0;
         }
         while (minimum >= 180.0) {
            minimum -= 360.0;
            maximum -= 360.0;
         }
         if (maximum <= 180.0) {
            addLongitudeSegment(key, latitude, minimum, maximum);
         } else {
            addLongitudeSegment(key, latitude, minimum, 180.0);
            addLongitudeSegment(key, latitude, -180.0, maximum - 360.0);
         }
      }

      private void addLongitudeSegment(PatchKey key, int latitude, double minimum, double maximum) {
         int minBucket = longitudeBucket(Math.max(-180.0, minimum));
         int maxBucket = longitudeBucket(Math.min(180.0, maximum));
         for (int longitude = minBucket; longitude <= maxBucket; longitude++) {
            this.locator.computeIfAbsent(new LocatorBucket(latitude, longitude), ignored -> new ArrayList<>()).add(key);
         }
      }

      private List<PatchKey> candidatePatches(SphereWorldModel.GeoPoint point) {
         Set<PatchKey> candidates = new HashSet<>(this.broadPatches);
         LocatorBucket bucket = new LocatorBucket(latitudeBucket(point.latitude()), longitudeBucket(point.longitude()));
         candidates.addAll(this.locator.getOrDefault(bucket, List.of()));
         return candidates.stream().sorted().toList();
      }

      private static boolean withinCap(PatchDescriptor patch, SphereWorldModel.GeoPoint point) {
         double radius = patch.sideMeters() / Math.sqrt(2.0) + EPSILON_METERS;
         return SphereWorldModel.greatCircleDistanceMeters(patch.anchor(), point) <= radius;
      }

      private static int latitudeBucket(double latitude) {
         return Math.max(0, Math.min(LATITUDE_BUCKETS - 1,
            (int)Math.floor((latitude + 90.0) / LOCATOR_BUCKET_DEGREES)));
      }

      private static int longitudeBucket(double longitude) {
         double normalized = longitude == 180.0 ? Math.nextDown(180.0) : longitude;
         return Math.max(0, Math.min(LONGITUDE_BUCKETS - 1,
            (int)Math.floor((normalized + 180.0) / LOCATOR_BUCKET_DEGREES)));
      }
   }

   public record BoundaryLink(PatchKey source, Edge sourceEdge, PatchKey target, Edge targetEdge, boolean reversed) {
      public BoundaryLink {
         Objects.requireNonNull(source, "source");
         Objects.requireNonNull(sourceEdge, "sourceEdge");
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(targetEdge, "targetEdge");
      }

      public BoundaryLink reverse() {
         return new BoundaryLink(this.target, this.targetEdge, this.source, this.sourceEdge, this.reversed);
      }
   }

   private record BoundaryKey(PatchKey patch, Edge edge) { }

   private static final double LOCATOR_BUCKET_DEGREES = 2.0;
   private static final int LATITUDE_BUCKETS = (int)(180.0 / LOCATOR_BUCKET_DEGREES);
   private static final int LONGITUDE_BUCKETS = (int)(360.0 / LOCATOR_BUCKET_DEGREES);
   private record LocatorBucket(int latitude, int longitude) { }

   private record Vector3(double x, double y, double z) {
      static Vector3 from(SphereWorldModel.GeoPoint point) {
         double lat = Math.toRadians(point.latitude()), lon = Math.toRadians(point.longitude());
         double c = Math.cos(lat);
         return new Vector3(c * Math.cos(lon), Math.sin(lat), c * Math.sin(lon));
      }

      static SphereWorldModel.GeoPoint toGeoPoint(Vector3 vector) {
         Vector3 n = vector.normalized();
         return new SphereWorldModel.GeoPoint(Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, n.y)))),
            Math.toDegrees(Math.atan2(n.z, n.x)), 0.0);
      }

      Vector3 add(Vector3 other) { return new Vector3(x + other.x, y + other.y, z + other.z); }
      Vector3 subtract(Vector3 other) { return new Vector3(x - other.x, y - other.y, z - other.z); }
      Vector3 scale(double value) { return new Vector3(x * value, y * value, z * value); }
      Vector3 cross(Vector3 other) { return new Vector3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x); }
      double dot(Vector3 other) { return x * other.x + y * other.y + z * other.z; }
      double length() { return Math.sqrt(dot(this)); }
      Vector3 normalized() { return scale(1.0 / Math.max(1.0E-30, length())); }
   }
}
