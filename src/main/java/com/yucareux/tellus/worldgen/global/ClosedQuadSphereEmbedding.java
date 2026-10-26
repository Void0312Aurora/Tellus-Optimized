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
   private final ClosedQuadSphereTopology topology;
   private final SphereWorldModel model;

   public ClosedQuadSphereEmbedding(ClosedQuadSphereTopology topology, SphereWorldModel model) {
      this.topology = Objects.requireNonNull(topology, "topology");
      this.model = Objects.requireNonNull(model, "model");
   }

   public ClosedQuadSphereTopology topology() { return this.topology; }
   public SphereWorldModel model() { return this.model; }

   public SphereWorldModel.GeoPoint vertex(ClosedQuadSphereTopology.VertexKey key) {
      List<ClosedQuadSphereTopology.FaceVertex> uses = this.topology.uses(Objects.requireNonNull(key, "key"));
      if (uses.isEmpty()) throw new IllegalArgumentException("unknown vertex");
      return point(uses.get(0));
   }

   public List<SphereWorldModel.GeoPoint> cellFootprint(ClosedQuadSphereTopology.CellId cell) {
      return this.topology.faceVertices(cell).stream().map(this::point).toList();
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
      return this.model.exactSurfacePoint(start.face(), i * scale, j * scale);
   }

   private SphereWorldModel.GeoPoint point(ClosedQuadSphereTopology.FaceVertex vertex) {
      double scale = this.model.config().faceBlocks() / (double)this.topology.subdivision();
      return this.model.exactSurfacePoint(vertex.face(), vertex.i() * scale, vertex.j() * scale);
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

   private record EdgePair(ClosedQuadSphereTopology.CellId first, ClosedQuadSphereTopology.CellId second) {
      static EdgePair of(ClosedQuadSphereTopology.CellId left, ClosedQuadSphereTopology.CellId right) {
         return left.compareTo(right) <= 0 ? new EdgePair(left, right) : new EdgePair(right, left);
      }
   }

   public record SeamReport(int samplesPerEdge, int edges, double maxVertexMismatchMeters,
      double maxInteriorMismatchMeters) { }
}
