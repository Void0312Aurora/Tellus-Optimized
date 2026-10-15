package com.yucareux.tellus.worldgen.global;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Canonical shared-geometry audit for the closed quadrilateral prototype.
 * Every cube vertex is assigned one spherical position, and edge interiors
 * are compared using the exact same cubed-sphere map on both incident cells.
 * The resulting reports measure shared-boundary consistency. Radial block
 * volumes and Minecraft collision are outside this class.
 */
public final class ClosedQuadSphereEmbedding {
   public enum Mapping { EQUANGULAR_CUBE, SPHERIFIED_CUBE }

   /** Research maps must provide a conservative bound for their entire cell footprint. */
   public interface SurfaceMapping {
      String id();
      SphereWorldModel.GeoPoint point(int face, double u, double v);
      CellEnclosure enclosure(int face, double uLow, double uHigh, double vLow, double vHigh);
   }

   private final ClosedQuadSphereTopology topology;
   private final SphereWorldModel model;
   private final Mapping mapping;
   private final SurfaceMapping externalMapping;

   public ClosedQuadSphereEmbedding(ClosedQuadSphereTopology topology, SphereWorldModel model) {
      this(topology, model, Mapping.EQUANGULAR_CUBE);
   }

   public ClosedQuadSphereEmbedding(ClosedQuadSphereTopology topology, SphereWorldModel model,
      Mapping mapping) {
      this.topology = Objects.requireNonNull(topology, "topology");
      this.model = Objects.requireNonNull(model, "model");
      this.mapping = Objects.requireNonNull(mapping, "mapping");
      this.externalMapping = null;
   }

   public ClosedQuadSphereEmbedding(ClosedQuadSphereTopology topology, SphereWorldModel model,
      SurfaceMapping mapping) {
      this.topology = Objects.requireNonNull(topology, "topology");
      this.model = Objects.requireNonNull(model, "model");
      this.externalMapping = Objects.requireNonNull(mapping, "mapping");
      if (mapping.id() == null || mapping.id().isBlank()) throw new IllegalArgumentException("mapping ID");
      this.mapping = null;
   }

   public ClosedQuadSphereTopology topology() { return this.topology; }
   public SphereWorldModel model() { return this.model; }
   /** Built-in mapping only; external experiments identify themselves with mappingId(). */
   public Mapping mapping() {
      if (this.externalMapping != null) throw new IllegalStateException("external mapping has no built-in enum");
      return this.mapping;
   }
   public String mappingId() { return externalMapping == null ? mapping.name() : externalMapping.id(); }

   public SphereWorldModel.GeoPoint vertex(ClosedQuadSphereTopology.VertexKey key) {
      List<ClosedQuadSphereTopology.FaceVertex> uses = this.topology.uses(Objects.requireNonNull(key, "key"));
      if (uses.isEmpty()) throw new IllegalArgumentException("unknown vertex");
      // Selecting the first inserted use made tiny floating-point differences
      // (and exact-distance ties) depend on topology generation order.
      var canonicalUse = uses.stream().min(Comparator
         .comparingInt(ClosedQuadSphereTopology.FaceVertex::face)
         .thenComparingInt(ClosedQuadSphereTopology.FaceVertex::i)
         .thenComparingInt(ClosedQuadSphereTopology.FaceVertex::j)).orElseThrow();
      return point(canonicalUse);
   }

   public List<SphereWorldModel.GeoPoint> cellFootprint(ClosedQuadSphereTopology.CellId cell) {
      requireCell(cell);
      return this.topology.faceVertices(cell).stream().map(this::point).toList();
   }

   /** Continuous cell coordinates, where both fractions lie in [0, 1]. */
   public SphereWorldModel.GeoPoint cellPoint(ClosedQuadSphereTopology.CellId cell,
      double fractionI, double fractionJ) {
      requireCell(cell);
      if (!Double.isFinite(fractionI) || !Double.isFinite(fractionJ)
         || fractionI < 0.0 || fractionI > 1.0 || fractionJ < 0.0 || fractionJ > 1.0) {
         throw new IllegalArgumentException("cell fractions");
      }
      double scale = this.model.config().faceBlocks() / (double)this.topology.subdivision();
      return surfacePoint(cell.face(), (cell.i() + fractionI) * scale, (cell.j() + fractionJ) * scale);
   }

   /**
    * A cap enclosing the entire mapped cell, including curved edge interiors.
    * For equiangular faces, all rays are positive combinations of corner rays,
    * so the convex cap containing the corners also contains the cell.
    * For spherified faces, interval bounds enclose all three Cartesian
    * coordinates before deriving a maximum chord distance to the center.
    * The bound is specific to these maps; a new map needs its own enclosure.
    */
   public CellEnclosure cellEnclosure(ClosedQuadSphereTopology.CellId cell) {
      requireCell(cell);
      if (externalMapping != null) {
         double n = topology.subdivision();
         return Objects.requireNonNull(externalMapping.enclosure(cell.face(),
            2.0 * cell.i() / n - 1.0, 2.0 * (cell.i() + 1) / n - 1.0,
            2.0 * cell.j() / n - 1.0, 2.0 * (cell.j() + 1) / n - 1.0), "external enclosure");
      }
      SphereWorldModel.GeoPoint center = cellPoint(cell, 0.5, 0.5);
      double radius;
      if (this.mapping == Mapping.EQUANGULAR_CUBE) {
         radius = 0.0;
         for (var corner : cellFootprint(cell)) {
            radius = Math.max(radius, SphereWorldModel.greatCircleDistanceMeters(center, corner));
         }
      } else {
         double n = this.topology.subdivision();
         Interval u = new Interval(2.0 * cell.i() / n - 1.0, 2.0 * (cell.i() + 1) / n - 1.0);
         Interval v = new Interval(2.0 * cell.j() / n - 1.0, 2.0 * (cell.j() + 1) / n - 1.0);
         Interval positive = new Interval(1.0, 1.0);
         Interval negative = new Interval(-1.0, -1.0);
         Interval[] cube = switch (cell.face()) {
            case 0 -> new Interval[]{positive, v, u.negated()};
            case 1 -> new Interval[]{negative, v, u};
            case 2 -> new Interval[]{u, positive, v.negated()};
            case 3 -> new Interval[]{u, negative, v};
            case 4 -> new Interval[]{u, v, positive};
            case 5 -> new Interval[]{u.negated(), v, negative};
            default -> throw new AssertionError();
         };
         Vec3 c = unit(center);
         double[] components = {c.x, c.y, c.z};
         double chordSquared = 0.0;
         for (int axis = 0; axis < 3; axis++) {
            Interval a = cube[(axis + 1) % 3].squared();
            Interval b = cube[(axis + 2) % 3].squared();
            // 1-a/2-b/2+ab/3 decreases in a and b throughout [0,1].
            Interval factor = new Interval(Math.sqrt(spherifiedFactor(a.high, b.high)),
               Math.sqrt(spherifiedFactor(a.low, b.low)));
            Interval coordinate = cube[axis].multiplied(factor);
            double bound = Math.max(Math.abs(coordinate.low - components[axis]),
               Math.abs(coordinate.high - components[axis]));
            chordSquared += bound * bound;
         }
         radius = 2.0 * SphereWorldModel.EARTH_RADIUS_METERS
            * Math.asin(Math.min(1.0, Math.sqrt(chordSquared) * 0.5));
      }
      // Outward margin for floating-point roundoff, also at exact vertices.
      return new CellEnclosure(center, Math.min(Math.PI * SphereWorldModel.EARTH_RADIUS_METERS,
         radius + 1.0E-5));
   }

   /** Corner polygon area using great-circle edges, not a curved-map area integral. */
   public double cellAreaMetersSquared(ClosedQuadSphereTopology.CellId cell) {
      return sphericalQuadrilateralArea(cellFootprint(cell));
   }

   private void requireCell(ClosedQuadSphereTopology.CellId cell) {
      if (this.topology.vertices(Objects.requireNonNull(cell, "cell")) == null) {
         throw new IllegalArgumentException("unknown cell: " + cell);
      }
   }

   private static double spherifiedFactor(double a, double b) {
      return 1.0 - a * 0.5 - b * 0.5 + a * b / 3.0;
   }

   /**
    * Measure the connected mesh represented by this embedding.
    *
    * <p>The reference edge is the nominal face-centre block scale multiplied
    * by the number of face blocks covered by one topology cell.  Edge lengths
    * use the exact spherical arcs, corner angles use the tangent directions of
    * those arcs, and cell areas use spherical excess.  Cells incident to one
    * of the eight degree-three vertices are reported separately so an
    * extraordinary region cannot disappear into an all-cell average.</p>
    */
   public MetricReport measureMetrics() {
      return measureMetrics(cell -> true);
   }

   /** Metrics for a nonempty cell subset, including each incident edge once. */
   public MetricReport measureMetrics(Predicate<ClosedQuadSphereTopology.CellId> include) {
      Objects.requireNonNull(include, "include");
      List<ClosedQuadSphereTopology.CellId> cells = this.topology.cells().stream().filter(include).toList();
      if (cells.isEmpty()) throw new IllegalArgumentException("metric cell subset is empty");
      Set<ClosedQuadSphereTopology.EdgeKey> edges = new HashSet<>();
      for (var cell : cells) {
         var keys = this.topology.vertices(cell);
         for (int index = 0; index < 4; index++) {
            edges.add(new ClosedQuadSphereTopology.EdgeKey(keys.get(index), keys.get((index + 1) % 4)));
         }
      }
      double nominalEdgeMeters = this.model.nominalBlockScaleMeters()
         * this.model.config().faceBlocks() / (double)this.topology.subdivision();
      double minEdgeScale = Double.POSITIVE_INFINITY;
      double maxEdgeScale = 0.0;
      for (var edge : edges) {
         double scale = SphereWorldModel.greatCircleDistanceMeters(
            this.vertex(edge.first()), this.vertex(edge.second())) / nominalEdgeMeters;
         minEdgeScale = Math.min(minEdgeScale, scale);
         maxEdgeScale = Math.max(maxEdgeScale, scale);
      }

      double maxAngleError = 0.0;
      double minAreaScale = Double.POSITIVE_INFINITY;
      double maxAreaScale = 0.0;
      double exceptionalMinEdgeScale = Double.POSITIVE_INFINITY;
      double exceptionalMaxEdgeScale = 0.0;
      double exceptionalMaxAngleError = 0.0;
      double exceptionalMinAreaScale = Double.POSITIVE_INFINITY;
      double exceptionalMaxAreaScale = 0.0;
      int exceptionalCells = 0;
      double nominalArea = nominalEdgeMeters * nominalEdgeMeters;

      for (var cell : cells) {
         List<ClosedQuadSphereTopology.VertexKey> keys = this.topology.vertices(cell);
         boolean exceptional = keys.stream().anyMatch(key -> this.topology.vertexDegree(key) == 3);
         if (exceptional) exceptionalCells++;

         List<SphereWorldModel.GeoPoint> corners = this.cellFootprint(cell);
         double cellMaxAngleError = 0.0;
         for (int index = 0; index < corners.size(); index++) {
            var previous = unit(corners.get((index + corners.size() - 1) % corners.size()));
            var current = unit(corners.get(index));
            var next = unit(corners.get((index + 1) % corners.size()));
            double angle = angleAt(current, previous, next);
            double error = Math.abs(angle - Math.PI * 0.5);
            maxAngleError = Math.max(maxAngleError, error);
            cellMaxAngleError = Math.max(cellMaxAngleError, error);
         }

         double areaScale = sphericalQuadrilateralArea(corners) / nominalArea;
         minAreaScale = Math.min(minAreaScale, areaScale);
         maxAreaScale = Math.max(maxAreaScale, areaScale);
         if (exceptional) {
            exceptionalMaxAngleError = Math.max(exceptionalMaxAngleError, cellMaxAngleError);
            exceptionalMinAreaScale = Math.min(exceptionalMinAreaScale, areaScale);
            exceptionalMaxAreaScale = Math.max(exceptionalMaxAreaScale, areaScale);
            for (int index = 0; index < keys.size(); index++) {
               var first = keys.get(index);
               var second = keys.get((index + 1) % keys.size());
               if (this.topology.vertexDegree(first) == 3 || this.topology.vertexDegree(second) == 3) {
                  double scale = SphereWorldModel.greatCircleDistanceMeters(
                     corners.get(index), corners.get((index + 1) % corners.size())) / nominalEdgeMeters;
                  exceptionalMinEdgeScale = Math.min(exceptionalMinEdgeScale, scale);
                  exceptionalMaxEdgeScale = Math.max(exceptionalMaxEdgeScale, scale);
               }
            }
         }
      }

      return new MetricReport(cells.size(), edges.size(),
         nominalEdgeMeters, minEdgeScale, maxEdgeScale, maxAngleError,
         minAreaScale, maxAreaScale, exceptionalCells, exceptionalMinEdgeScale,
         exceptionalMaxEdgeScale, exceptionalMaxAngleError, exceptionalMinAreaScale,
         exceptionalMaxAreaScale);
   }

   public SeamReport auditSeams(int samplesPerEdge) {
      if (samplesPerEdge < 2) throw new IllegalArgumentException("samplesPerEdge");
      double maxVertexMismatch = 0.0;
      for (var vertex : this.topology.vertices()) {
         var uses = this.topology.uses(vertex);
         var reference = point(uses.get(0));
         for (int i = 1; i < uses.size(); i++) {
            maxVertexMismatch = Math.max(maxVertexMismatch,
               SphereWorldModel.greatCircleDistanceMeters(reference, point(uses.get(i))));
         }
      }

      Set<EdgePair> visited = new HashSet<>();
      double maxInteriorMismatch = 0.0;
      for (var cell : this.topology.cells()) {
         for (var direction : ClosedQuadSphereTopology.EdgeDirection.values()) {
            var neighbor = this.topology.neighbor(cell, direction);
            EdgePair pair = EdgePair.of(cell, neighbor);
            if (!visited.add(pair)) continue;
            var neighborDirection = reciprocalDirection(neighbor, cell);
            var sourceStart = edgePoint(cell, direction, 0.0);
            var targetStart = edgePoint(neighbor, neighborDirection, 0.0);
            var sourceEnd = edgePoint(cell, direction, 1.0);
            var targetEnd = edgePoint(neighbor, neighborDirection, 1.0);
            double direct = SphereWorldModel.greatCircleDistanceMeters(sourceStart, targetStart)
               + SphereWorldModel.greatCircleDistanceMeters(sourceEnd, targetEnd);
            double reverse = SphereWorldModel.greatCircleDistanceMeters(sourceStart, targetEnd)
               + SphereWorldModel.greatCircleDistanceMeters(sourceEnd, targetStart);
            boolean reversed = reverse < direct;
            for (int sample = 0; sample < samplesPerEdge; sample++) {
               double t = sample / (double)(samplesPerEdge - 1);
               double targetT = reversed ? 1.0 - t : t;
               maxInteriorMismatch = Math.max(maxInteriorMismatch,
                  SphereWorldModel.greatCircleDistanceMeters(edgePoint(cell, direction, t),
                     edgePoint(neighbor, neighborDirection, targetT)));
            }
         }
      }
      return new SeamReport(samplesPerEdge, this.topology.audit().edges(), maxVertexMismatch, maxInteriorMismatch);
   }

   private SphereWorldModel.GeoPoint edgePoint(ClosedQuadSphereTopology.CellId cell,
      ClosedQuadSphereTopology.EdgeDirection direction, double t) {
      List<ClosedQuadSphereTopology.FaceVertex> corners = this.topology.faceVertices(cell);
      ClosedQuadSphereTopology.FaceVertex start;
      ClosedQuadSphereTopology.FaceVertex end;
      switch (direction) {
         case SOUTH -> { start = corners.get(0); end = corners.get(1); }
         case EAST -> { start = corners.get(1); end = corners.get(2); }
         case NORTH -> { start = corners.get(2); end = corners.get(3); }
         case WEST -> { start = corners.get(3); end = corners.get(0); }
         default -> throw new AssertionError(direction);
      }
      double i = start.i() + (end.i() - start.i()) * t;
      double j = start.j() + (end.j() - start.j()) * t;
      double scale = this.model.config().faceBlocks() / (double)this.topology.subdivision();
      return surfacePoint(start.face(), i * scale, j * scale);
   }

   private SphereWorldModel.GeoPoint point(ClosedQuadSphereTopology.FaceVertex vertex) {
      double scale = this.model.config().faceBlocks() / (double)this.topology.subdivision();
      return surfacePoint(vertex.face(), vertex.i() * scale, vertex.j() * scale);
   }

   private SphereWorldModel.GeoPoint surfacePoint(int face, double blockX, double blockZ) {
      if (externalMapping != null) {
         double size = this.model.config().faceBlocks();
         return Objects.requireNonNull(externalMapping.point(face,
            blockX / size * 2.0 - 1.0, blockZ / size * 2.0 - 1.0), "external point");
      }
      if (this.mapping == Mapping.EQUANGULAR_CUBE) {
         double size = this.model.config().faceBlocks();
         return new EquiangularCubedSphere().point(face, blockX / size * 2.0 - 1.0,
            blockZ / size * 2.0 - 1.0);
      }
      double size = this.model.config().faceBlocks();
      double u = blockX / size * 2.0 - 1.0;
      double v = blockZ / size * 2.0 - 1.0;
      Vec3 cube = switch (face) {
         case 0 -> new Vec3(1.0, v, -u);
         case 1 -> new Vec3(-1.0, v, u);
         case 2 -> new Vec3(u, 1.0, -v);
         case 3 -> new Vec3(u, -1.0, v);
         case 4 -> new Vec3(u, v, 1.0);
         case 5 -> new Vec3(-u, v, -1.0);
         default -> throw new IllegalArgumentException("face");
      };
      double x2 = cube.x * cube.x;
      double y2 = cube.y * cube.y;
      double z2 = cube.z * cube.z;
      Vec3 sphere = new Vec3(
         cube.x * Math.sqrt(Math.max(0.0, 1.0 - y2 * 0.5 - z2 * 0.5 + y2 * z2 / 3.0)),
         cube.y * Math.sqrt(Math.max(0.0, 1.0 - z2 * 0.5 - x2 * 0.5 + z2 * x2 / 3.0)),
         cube.z * Math.sqrt(Math.max(0.0, 1.0 - x2 * 0.5 - y2 * 0.5 + x2 * y2 / 3.0))
      ).normalized();
      return new SphereWorldModel.GeoPoint(Math.toDegrees(Math.asin(clamp(sphere.y, -1.0, 1.0))),
         Math.toDegrees(Math.atan2(sphere.z, sphere.x)), 0.0);
   }

   private ClosedQuadSphereTopology.EdgeDirection reciprocalDirection(
      ClosedQuadSphereTopology.CellId source, ClosedQuadSphereTopology.CellId target
   ) {
      ClosedQuadSphereTopology.EdgeDirection result = null;
      for (var direction : ClosedQuadSphereTopology.EdgeDirection.values()) {
         if (this.topology.neighbor(source, direction).equals(target)) {
            if (result != null) throw new IllegalStateException("multiple reciprocal edge directions");
            result = direction;
         }
      }
      if (result == null) throw new IllegalStateException("missing reciprocal edge direction");
      return result;
   }

   private static double sphericalQuadrilateralArea(List<SphereWorldModel.GeoPoint> corners) {
      Vec3 first = unit(corners.get(0));
      Vec3 second = unit(corners.get(1));
      Vec3 third = unit(corners.get(2));
      Vec3 fourth = unit(corners.get(3));
      return (triangleAreaSteradians(first, second, third)
         + triangleAreaSteradians(first, third, fourth))
         * SphereWorldModel.EARTH_RADIUS_METERS * SphereWorldModel.EARTH_RADIUS_METERS;
   }

   private static double triangleAreaSteradians(Vec3 first, Vec3 second, Vec3 third) {
      double numerator = Math.abs(first.dot(second.cross(third)));
      double denominator = 1.0 + first.dot(second) + second.dot(third) + third.dot(first);
      return 2.0 * Math.atan2(numerator, denominator);
   }

   private static double angleAt(Vec3 current, Vec3 previous, Vec3 next) {
      Vec3 first = previous.subtract(current.scale(current.dot(previous))).normalized();
      Vec3 second = next.subtract(current.scale(current.dot(next))).normalized();
      return Math.acos(clamp(first.dot(second), -1.0, 1.0));
   }

   private static Vec3 unit(SphereWorldModel.GeoPoint point) {
      double latitude = Math.toRadians(point.latitude());
      double longitude = Math.toRadians(point.longitude());
      double cosine = Math.cos(latitude);
      return new Vec3(cosine * Math.cos(longitude), Math.sin(latitude), cosine * Math.sin(longitude));
   }

   private static double clamp(double value, double low, double high) {
      return Math.max(low, Math.min(high, value));
   }

   private record EdgePair(ClosedQuadSphereTopology.CellId first, ClosedQuadSphereTopology.CellId second) {
      static EdgePair of(ClosedQuadSphereTopology.CellId left, ClosedQuadSphereTopology.CellId right) {
         return left.compareTo(right) <= 0 ? new EdgePair(left, right) : new EdgePair(right, left);
      }
   }

   public record SeamReport(int samplesPerEdge, int edges, double maxVertexMismatchMeters,
      double maxInteriorMismatchMeters) { }

   public record CellEnclosure(SphereWorldModel.GeoPoint center, double radiusMeters) {
      public CellEnclosure {
         Objects.requireNonNull(center, "center");
         if (radiusMeters < 0 || !Double.isFinite(radiusMeters)
            || radiusMeters > Math.PI * SphereWorldModel.EARTH_RADIUS_METERS) {
            throw new IllegalArgumentException("enclosure radius");
         }
      }
   }

   private record Interval(double low, double high) {
      private Interval negated() { return new Interval(-high, -low); }
      private Interval squared() {
         return new Interval(low <= 0.0 && high >= 0.0 ? 0.0 : Math.min(low * low, high * high),
            Math.max(low * low, high * high));
      }
      private Interval multiplied(Interval other) {
         double first = low * other.low, second = low * other.high;
         double third = high * other.low, fourth = high * other.high;
         return new Interval(Math.min(Math.min(first, second), Math.min(third, fourth)),
            Math.max(Math.max(first, second), Math.max(third, fourth)));
      }
   }

   public record MetricReport(int cells, int edges, double nominalEdgeMeters,
      double minEdgeScale, double maxEdgeScale, double maxAngleErrorRadians,
      double minAreaScale, double maxAreaScale, int extraordinaryCells,
      double extraordinaryMinEdgeScale, double extraordinaryMaxEdgeScale,
      double extraordinaryMaxAngleErrorRadians, double extraordinaryMinAreaScale,
      double extraordinaryMaxAreaScale) {
      public MetricReport {
         if (cells < 1 || edges < 1 || extraordinaryCells < 0
            || !(nominalEdgeMeters > 0.0) || !Double.isFinite(nominalEdgeMeters)
            || !(minEdgeScale > 0.0) || !Double.isFinite(minEdgeScale)
            || !(maxEdgeScale >= minEdgeScale) || !Double.isFinite(maxEdgeScale)
            || !(maxAngleErrorRadians >= 0.0) || !Double.isFinite(maxAngleErrorRadians)
            || !(minAreaScale > 0.0) || !Double.isFinite(minAreaScale)
            || !(maxAreaScale >= minAreaScale) || !Double.isFinite(maxAreaScale)
            || extraordinaryCells > cells) {
            throw new IllegalArgumentException("invalid mesh metric report");
         }
         if (extraordinaryCells == 0) {
            if (extraordinaryMinEdgeScale != Double.POSITIVE_INFINITY
               || extraordinaryMaxEdgeScale != 0.0
               || extraordinaryMaxAngleErrorRadians != 0.0
               || extraordinaryMinAreaScale != Double.POSITIVE_INFINITY
               || extraordinaryMaxAreaScale != 0.0) {
               throw new IllegalArgumentException("invalid empty extraordinary report");
            }
         } else if (!(extraordinaryMinEdgeScale > 0.0)
            || !(extraordinaryMaxEdgeScale >= extraordinaryMinEdgeScale)
            || !(extraordinaryMaxAngleErrorRadians >= 0.0)
            || !(extraordinaryMinAreaScale > 0.0)
            || !(extraordinaryMaxAreaScale >= extraordinaryMinAreaScale)
            || !Double.isFinite(extraordinaryMinEdgeScale)
            || !Double.isFinite(extraordinaryMaxEdgeScale)
            || !Double.isFinite(extraordinaryMaxAngleErrorRadians)
            || !Double.isFinite(extraordinaryMinAreaScale)
            || !Double.isFinite(extraordinaryMaxAreaScale)) {
            throw new IllegalArgumentException("invalid extraordinary report");
         }
      }
   }

   private record Vec3(double x, double y, double z) {
      private double dot(Vec3 other) { return this.x * other.x + this.y * other.y + this.z * other.z; }

      private Vec3 cross(Vec3 other) {
         return new Vec3(this.y * other.z - this.z * other.y,
            this.z * other.x - this.x * other.z,
            this.x * other.y - this.y * other.x);
      }

      private Vec3 subtract(Vec3 other) {
         return new Vec3(this.x - other.x, this.y - other.y, this.z - other.z);
      }

      private Vec3 scale(double factor) { return new Vec3(this.x * factor, this.y * factor, this.z * factor); }

      private Vec3 normalized() {
         double length = Math.sqrt(this.dot(this));
         if (!(length > 1.0E-15)) throw new IllegalStateException("cannot normalize zero tangent");
         return this.scale(1.0 / length);
      }
   }
}
