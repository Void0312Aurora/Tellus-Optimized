package com.yucareux.tellus.worldgen.global;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical shared-geometry audit for the closed quadrilateral prototype.
 * Every cube vertex is assigned one spherical position, and edge interiors
 * are compared using the exact same cubed-sphere map on both incident cells.
 * This provides a watertight surface embedding; radial block volumes and
 * Minecraft collision are intentionally outside this class.
 */
public final class ClosedQuadSphereEmbedding {
   public enum Mapping { EQUANGULAR_CUBE, SPHERIFIED_CUBE }

   private final ClosedQuadSphereTopology topology;
   private final SphereWorldModel model;
   private final Mapping mapping;

   public ClosedQuadSphereEmbedding(ClosedQuadSphereTopology topology, SphereWorldModel model) {
      this(topology, model, Mapping.EQUANGULAR_CUBE);
   }

   public ClosedQuadSphereEmbedding(ClosedQuadSphereTopology topology, SphereWorldModel model,
      Mapping mapping) {
      this.topology = Objects.requireNonNull(topology, "topology");
      this.model = Objects.requireNonNull(model, "model");
      this.mapping = Objects.requireNonNull(mapping, "mapping");
   }

   public ClosedQuadSphereTopology topology() { return this.topology; }
   public SphereWorldModel model() { return this.model; }
   public Mapping mapping() { return this.mapping; }

   public SphereWorldModel.GeoPoint vertex(ClosedQuadSphereTopology.VertexKey key) {
      List<ClosedQuadSphereTopology.FaceVertex> uses = this.topology.uses(Objects.requireNonNull(key, "key"));
      if (uses.isEmpty()) throw new IllegalArgumentException("unknown vertex");
      return point(uses.get(0));
   }

   public List<SphereWorldModel.GeoPoint> cellFootprint(ClosedQuadSphereTopology.CellId cell) {
      return this.topology.faceVertices(cell).stream().map(this::point).toList();
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
      double nominalEdgeMeters = this.model.nominalBlockScaleMeters()
         * this.model.config().faceBlocks() / (double)this.topology.subdivision();
      double minEdgeScale = Double.POSITIVE_INFINITY;
      double maxEdgeScale = 0.0;
      for (var edge : this.topology.edges()) {
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

      for (var cell : this.topology.cells()) {
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

      return new MetricReport(this.topology.cells().size(), this.topology.edges().size(),
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
      if (this.mapping == Mapping.EQUANGULAR_CUBE) {
         return this.model.exactSurfacePoint(face, blockX, blockZ);
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
