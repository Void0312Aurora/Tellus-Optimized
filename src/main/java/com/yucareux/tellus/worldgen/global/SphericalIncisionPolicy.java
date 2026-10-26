package com.yucareux.tellus.worldgen.global;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Research-only quarantine policy for the eight cubed-sphere singularities.
 *
 * <p>This class does not implement the Rančić/McGregor conformal map. It
 * supplies the runtime boundary that a future conformal or optimized
 * embedding must satisfy: a cell near a degree-three vertex cannot silently
 * enter the ordinary six-neighbor area. Cells in the excluded cap are
 * rejected from ordinary materialization; transition cells remain addressable
 * for a later special collision and movement implementation.</p>
 *
 * <p>Classification uses a map-specific enclosing cap for the entire cell. It
 * can over-classify cells, which is intentional for this gate. It must not be
 * used as a proof that a production cap has exact geodesic boundaries.</p>
 */
public final class SphericalIncisionPolicy {
   public enum CellStatus { ORDINARY, TRANSITION, EXCLUDED }

   public record Config(double excludedRadiusMeters, double transitionWidthMeters) {
      public Config {
         if (excludedRadiusMeters < 0.0 || !Double.isFinite(excludedRadiusMeters)) {
            throw new IllegalArgumentException("excludedRadiusMeters");
         }
         if (transitionWidthMeters < 0.0 || !Double.isFinite(transitionWidthMeters)) {
            throw new IllegalArgumentException("transitionWidthMeters");
         }
         if (!Double.isFinite(excludedRadiusMeters + transitionWidthMeters)) {
            throw new IllegalArgumentException("combined radius overflows");
         }
      }
   }

   /** Classification evidence retained for radius sweeps and review. */
   public record CellClassification(
      ClosedQuadSphereTopology.CellId cell,
      CellStatus status,
      ClosedQuadSphereTopology.VertexKey nearestExtraordinaryVertex,
      double centerDistanceMeters,
      double cellRadiusMeters,
      double conservativeDistanceMeters
   ) {
      public CellClassification {
         Objects.requireNonNull(cell, "cell");
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(nearestExtraordinaryVertex, "nearestExtraordinaryVertex");
         if (centerDistanceMeters < 0.0 || !Double.isFinite(centerDistanceMeters)
            || cellRadiusMeters < 0.0 || !Double.isFinite(cellRadiusMeters)
            || conservativeDistanceMeters < 0.0 || !Double.isFinite(conservativeDistanceMeters)) {
            throw new IllegalArgumentException("invalid cell classification metric");
         }
      }
   }

   private final ClosedQuadSphereTopology topology;
   private final ClosedQuadSphereEmbedding embedding;
   private final Config config;
   private final Map<ClosedQuadSphereTopology.CellId, CellClassification> classifications;
   private final Set<ClosedQuadSphereTopology.VertexKey> extraordinaryVertices;

   public SphericalIncisionPolicy(ClosedQuadSphereTopology topology,
      ClosedQuadSphereEmbedding embedding, Config config) {
      this.topology = Objects.requireNonNull(topology, "topology");
      this.embedding = Objects.requireNonNull(embedding, "embedding");
      this.config = Objects.requireNonNull(config, "config");
      if (embedding.topology() != topology) {
         throw new IllegalArgumentException("embedding must use the same topology instance");
      }

      TreeSet<ClosedQuadSphereTopology.VertexKey> extraordinary = new TreeSet<>();
      for (var vertex : topology.vertices()) {
         int degree = topology.vertexDegree(vertex);
         if (degree == 3) extraordinary.add(vertex);
         else if (degree != 4) throw new IllegalArgumentException("unsupported vertex degree: " + degree);
      }
      if (extraordinary.size() != 8) {
         throw new IllegalArgumentException("closed cube must have eight extraordinary vertices");
      }
      this.extraordinaryVertices = Collections.unmodifiableSet(extraordinary);

      TreeMap<ClosedQuadSphereTopology.CellId, CellClassification> result = new TreeMap<>();
      for (var cell : topology.cells()) result.put(cell, classify(cell));
      this.classifications = Collections.unmodifiableMap(result);
   }

   public ClosedQuadSphereTopology topology() { return topology; }
   public ClosedQuadSphereEmbedding embedding() { return embedding; }
   public Config config() { return config; }
   public Set<ClosedQuadSphereTopology.VertexKey> extraordinaryVertices() { return extraordinaryVertices; }

   public CellStatus status(ClosedQuadSphereTopology.CellId cell) {
      return classification(cell).status();
   }

   public CellClassification classification(ClosedQuadSphereTopology.CellId cell) {
      CellClassification result = classifications.get(Objects.requireNonNull(cell, "cell"));
      if (result == null) throw new IllegalArgumentException("unknown cell: " + cell);
      return result;
   }

   public List<CellClassification> classifications() {
      return List.copyOf(classifications.values());
   }

   public Set<ClosedQuadSphereTopology.CellId> cells(CellStatus status) {
      Objects.requireNonNull(status, "status");
      TreeSet<ClosedQuadSphereTopology.CellId> result = new TreeSet<>();
      for (var entry : classifications.entrySet()) {
         if (entry.getValue().status() == status) result.add(entry.getKey());
      }
      return Collections.unmodifiableSet(result);
   }

   public Set<ClosedQuadSphereTopology.CellId> ordinaryCells() { return cells(CellStatus.ORDINARY); }
   public Set<ClosedQuadSphereTopology.CellId> transitionCells() { return cells(CellStatus.TRANSITION); }
   public Set<ClosedQuadSphereTopology.CellId> excludedCells() { return cells(CellStatus.EXCLUDED); }

   /** Ordinary mutable state is admitted only for ordinary cells. */
   public boolean admitsOrdinaryMaterialization(ClosedQuadSphereTopology.CellId cell) {
      return status(cell) == CellStatus.ORDINARY;
   }

   /** Fail before ordinary state generation or editing on a quarantined cell. */
   public void requireOrdinary(ClosedQuadSphereTopology.CellId cell) {
      if (!admitsOrdinaryMaterialization(cell)) {
         throw new IllegalStateException("cell requires an exceptional-region handler: " + cell + " " + status(cell));
      }
   }

   /** A gate for ordinary traversal; it does not implement boundary collision. */
   public ClosedQuadSphereTopology.CellId ordinaryNeighbor(ClosedQuadSphereTopology.CellId source,
      ClosedQuadSphereTopology.EdgeDirection direction) {
      requireOrdinary(source);
      var destination = topology.neighbor(source, Objects.requireNonNull(direction, "direction"));
      requireOrdinary(destination);
      return destination;
   }

   private CellClassification classify(ClosedQuadSphereTopology.CellId cell) {
      var enclosure = embedding.cellEnclosure(cell);
      Vec3 center = Vec3.from(enclosure.center());

      double nearestDistance = Double.POSITIVE_INFINITY;
      ClosedQuadSphereTopology.VertexKey nearestVertex = null;
      for (var vertex : extraordinaryVertices) {
         double distance = angularDistanceMeters(center, Vec3.from(embedding.vertex(vertex)));
         if (distance < nearestDistance) {
            nearestDistance = distance;
            nearestVertex = vertex;
         }
      }
      double cellRadius = enclosure.radiusMeters();
      double conservativeDistance = Math.max(0.0, nearestDistance - cellRadius);
      boolean incident = topology.vertices(cell).stream()
         .anyMatch(vertex -> extraordinaryVertices.contains(vertex));
      double transitionLimit = config.excludedRadiusMeters() + config.transitionWidthMeters();
      CellStatus status;
      if (config.excludedRadiusMeters() > 0.0
         && (incident || conservativeDistance <= config.excludedRadiusMeters())) {
         status = CellStatus.EXCLUDED;
      } else if (conservativeDistance <= transitionLimit || incident) {
         status = CellStatus.TRANSITION;
      } else {
         status = CellStatus.ORDINARY;
      }
      return new CellClassification(cell, status, nearestVertex, nearestDistance,
         cellRadius, conservativeDistance);
   }

   private static double angularDistanceMeters(Vec3 first, Vec3 second) {
      return SphereWorldModel.EARTH_RADIUS_METERS
         * Math.atan2(first.cross(second).length(), clamp(first.dot(second), -1.0, 1.0));
   }

   private static double clamp(double value, double low, double high) {
      return Math.max(low, Math.min(high, value));
   }

   private record Vec3(double x, double y, double z) {
      private static Vec3 from(SphereWorldModel.GeoPoint point) {
         double latitude = Math.toRadians(point.latitude());
         double longitude = Math.toRadians(point.longitude());
         double cosine = Math.cos(latitude);
         return new Vec3(cosine * Math.cos(longitude), Math.sin(latitude), cosine * Math.sin(longitude));
      }

      private Vec3 cross(Vec3 other) {
         return new Vec3(y * other.z - z * other.y,
            z * other.x - x * other.z, x * other.y - y * other.x);
      }

      private double dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }

      private double length() { return Math.sqrt(dot(this)); }
   }
}
