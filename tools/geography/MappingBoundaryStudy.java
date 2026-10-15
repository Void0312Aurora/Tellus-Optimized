/* SPDX-License-Identifier: GPL-3.0-or-later. Research tools only; see COPYING. */
package com.yucareux.tellus.research;

import com.yucareux.tellus.worldgen.global.ClosedQuadSphereEmbedding;
import com.yucareux.tellus.worldgen.global.ClosedQuadSphereTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/** Exports the same geographic quarantine policy for all mapping candidates. */
public final class MappingBoundaryStudy {
   private static final double R = SphereWorldModel.EARTH_RADIUS_METERS;
   private static final double TRANSITION_KM = 50.0;
   private static final double[] CAPS_KM = {50, 100, 150, 175, 200, 250, 300, 360};

   private MappingBoundaryStudy() { }

   public static void main(String[] args) throws Exception {
      if (args.length < 2) throw new IllegalArgumentException("rotation CSV and output directory required");
      Path rotation = Path.of(args[0]);
      Path output = Path.of(args[1]);
      int n = args.length > 2 ? Integer.parseInt(args[2]) : 128;
      Files.createDirectories(output);
      var topology = ClosedQuadSphereTopology.create(n);
      var model = SphereWorldModel.forWorldScale(1.0);
      List<Candidate> candidates = List.of(
         new Candidate("equiangular-cube", new ClosedQuadSphereEmbedding(topology, model,
            ClosedQuadSphereEmbedding.Mapping.EQUANGULAR_CUBE)),
         new Candidate("spherified-cube", new ClosedQuadSphereEmbedding(topology, model,
            ClosedQuadSphereEmbedding.Mapping.SPHERIFIED_CUBE)),
         new Candidate("conformal-cube-reference", new ClosedQuadSphereEmbedding(topology, model,
            new ConformalCubedSphere()))
      );
      double[][] matrix = readRotation(rotation);
      for (Candidate candidate : candidates) {
         Path directory = output.resolve(candidate.id());
         Files.createDirectories(directory);
         var embedding = new RotatedEmbedding(candidate.base(), matrix);
         writeBoundaries(directory, topology, embedding, n, candidate.id());
      }
      System.out.println("Mapping boundaries saved to " + output.toAbsolutePath());
   }

   private static void writeBoundaries(Path directory, ClosedQuadSphereTopology topology,
      RotatedEmbedding embedding, int n, String mapping) throws Exception {
      try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(
            directory.resolve("quarantine-mesh-summary.csv"), StandardCharsets.UTF_8));
         PrintWriter coarse = new PrintWriter(Files.newBufferedWriter(
            directory.resolve("quarantine-boundaries-8.geojson"), StandardCharsets.UTF_8));
         PrintWriter fine = new PrintWriter(Files.newBufferedWriter(
            directory.resolve("quarantine-boundaries-32.geojson"), StandardCharsets.UTF_8))) {
         csv.println("mapping,subdivision,cap_radius_km,transition_width_km,ordinary_cells,transition_cells,excluded_cells,max_enclosure_radius_km");
         coarse.print("{\"type\":\"FeatureCollection\",\"features\":[");
         fine.print("{\"type\":\"FeatureCollection\",\"features\":[");
         boolean first = true;
         for (double cap : CAPS_KM) {
            for (String zone : List.of("excluded", "quarantined")) {
               Predicate<ClosedQuadSphereTopology.CellId> include = cell -> {
                  Status status = embedding.status(cell, cap * 1000.0, TRANSITION_KM * 1000.0);
                  return zone.equals("excluded") ? status == Status.EXCLUDED : status != Status.ORDINARY;
               };
               var loops = boundaryLoops(topology, embedding, include);
               for (var loop : loops) {
                  var firstVertex = embedding.point(loop.get(0).start());
                  var key = embedding.extraordinary().stream()
                     .min(Comparator.comparingDouble(k -> distance(firstVertex, embedding.point(k)))).orElseThrow();
                  if (!first) { coarse.print(','); fine.print(','); }
                  first = false;
                  writeFeature(coarse, embedding, loop, key, cap, zone, n, mapping, 8);
                  writeFeature(fine, embedding, loop, key, cap, zone, n, mapping, 32);
               }
            }
            int ordinary = 0, transition = 0, excluded = 0;
            double maxRadius = 0.0;
            for (var cell : topology.cells()) {
               Status status = embedding.status(cell, cap * 1000.0, TRANSITION_KM * 1000.0);
               if (status == Status.ORDINARY) ordinary++;
               else if (status == Status.TRANSITION) transition++;
               else excluded++;
               maxRadius = Math.max(maxRadius, embedding.cellEnclosure(cell).radiusMeters());
            }
            csv.printf(Locale.ROOT, "%s,%d,%.0f,50,%d,%d,%d,%.6f%n",
               mapping, n, cap, ordinary, transition, excluded, maxRadius / 1000.0);
         }
         coarse.println("]}");
         fine.println("]}");
      }
   }

   private static List<List<Arc>> boundaryLoops(ClosedQuadSphereTopology topology,
      RotatedEmbedding embedding, Predicate<ClosedQuadSphereTopology.CellId> include) {
      var arcs = new LinkedHashMap<CellEdge, Arc>();
      var directions = ClosedQuadSphereTopology.EdgeDirection.values();
      for (var cell : topology.cells()) if (include.test(cell)) {
         var keys = topology.vertices(cell);
         for (int i = 0; i < 4; i++) if (!include.test(topology.neighbor(cell, directions[i]))) {
            arcs.put(new CellEdge(cell, i), new Arc(cell, i, keys.get(i), keys.get((i + 1) % 4)));
         }
      }
      List<List<Arc>> loops = new ArrayList<>();
      while (!arcs.isEmpty()) {
         Arc first = arcs.values().iterator().next(), current = first;
         List<Arc> loop = new ArrayList<>();
         do {
            Arc arc = arcs.remove(new CellEdge(current.cell(), current.index()));
            if (arc == null) throw new IllegalStateException("unclosed mapping boundary");
            loop.add(arc);
            var cell = arc.cell();
            var vertex = arc.end();
            Set<ClosedQuadSphereTopology.CellId> visited = new HashSet<>();
            while (true) {
               if (!visited.add(cell)) throw new IllegalStateException("boundary circulation lost exterior");
               int index = topology.vertices(cell).indexOf(vertex);
               if (index < 0) throw new IllegalStateException("boundary vertex not incident to cell");
               var other = topology.neighbor(cell, directions[index]);
               if (!include.test(other)) {
                  current = new Arc(cell, index, vertex, topology.vertices(cell).get((index + 1) % 4));
                  break;
               }
               cell = other;
            }
         } while (!current.equals(first));
         loops.add(List.copyOf(loop));
      }
      return loops;
   }

   private static void writeFeature(PrintWriter out, RotatedEmbedding embedding, List<Arc> loop,
      ClosedQuadSphereTopology.VertexKey key, double cap, String zone, int n, String mapping, int samples) {
      out.printf(Locale.ROOT,
         "{\"type\":\"Feature\",\"properties\":{\"mapping\":\"%s\",\"cap_radius_km\":%.0f,\"transition_width_km\":50,\"subdivision\":%d,\"samples_per_edge\":%d,\"zone\":\"%s\",\"canonical_signs\":[%d,%d,%d],\"topology_edges\":%d,\"fill_rule\":\"even_odd\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[",
         mapping, cap, n, samples, zone, Integer.signum(key.x()), Integer.signum(key.y()), Integer.signum(key.z()), loop.size());
      SphereWorldModel.GeoPoint first = null;
      for (var arc : loop) for (int index = 0; index < samples; index++) {
         double t = index / (double)samples;
         var point = switch (arc.index()) {
            case 0 -> embedding.cellPoint(arc.cell(), t, 0);
            case 1 -> embedding.cellPoint(arc.cell(), 1, t);
            case 2 -> embedding.cellPoint(arc.cell(), 1 - t, 1);
            case 3 -> embedding.cellPoint(arc.cell(), 0, 1 - t);
            default -> throw new AssertionError();
         };
         if (first == null) first = point; else out.print(',');
         out.printf(Locale.ROOT, "[%.12f,%.12f]", point.longitude(), point.latitude());
      }
      out.printf(Locale.ROOT, ",[%.12f,%.12f]]}}", first.longitude(), first.latitude());
   }

   private static double[][] readRotation(Path path) throws Exception {
      List<String> lines = Files.readAllLines(path);
      if (lines.size() != 3) throw new IllegalArgumentException("rotation needs three rows");
      double[][] matrix = new double[3][3];
      for (int i = 0; i < 3; i++) {
         String[] values = lines.get(i).split(",");
         if (values.length != 3) throw new IllegalArgumentException("rotation needs three columns");
         for (int j = 0; j < 3; j++) matrix[i][j] = Double.parseDouble(values[j]);
      }
      return matrix;
   }

   private static double distance(SphereWorldModel.GeoPoint first, SphereWorldModel.GeoPoint second) {
      Vec3 a = Vec3.from(first), b = Vec3.from(second);
      return R * Math.atan2(a.cross(b).length(), clamp(a.dot(b), -1, 1));
   }

   private static double clamp(double value, double low, double high) {
      return Math.max(low, Math.min(high, value));
   }

   private record Candidate(String id, ClosedQuadSphereEmbedding base) { }
   private record CellEdge(ClosedQuadSphereTopology.CellId cell, int index) { }
   private record Arc(ClosedQuadSphereTopology.CellId cell, int index,
      ClosedQuadSphereTopology.VertexKey start, ClosedQuadSphereTopology.VertexKey end) { }
   private enum Status { ORDINARY, TRANSITION, EXCLUDED }

   private static final class RotatedEmbedding {
      private final ClosedQuadSphereEmbedding base;
      private final double[][] rotation;
      private final Set<ClosedQuadSphereTopology.VertexKey> extraordinary;

      private RotatedEmbedding(ClosedQuadSphereEmbedding base, double[][] rotation) {
         this.base = base;
         this.rotation = rotation;
         TreeSet<ClosedQuadSphereTopology.VertexKey> keys = new TreeSet<>();
         for (var key : base.topology().vertices()) if (base.topology().vertexDegree(key) == 3) keys.add(key);
         if (keys.size() != 8) throw new IllegalArgumentException("expected eight extraordinary vertices");
         this.extraordinary = Collections.unmodifiableSet(keys);
      }

      private Set<ClosedQuadSphereTopology.VertexKey> extraordinary() { return extraordinary; }
      private SphereWorldModel.GeoPoint point(int face, double u, double v) {
         return rotate(base.cellPoint(new ClosedQuadSphereTopology.CellId(face, 0, 0), (u + 1) * 0.5, (v + 1) * 0.5));
      }
      private SphereWorldModel.GeoPoint point(ClosedQuadSphereTopology.FaceVertex vertex) {
         double n = base.topology().subdivision();
         return point(vertex.face(), 2.0 * vertex.i() / n - 1.0, 2.0 * vertex.j() / n - 1.0);
      }
      private SphereWorldModel.GeoPoint point(ClosedQuadSphereTopology.VertexKey key) {
         return rotate(base.vertex(key));
      }
      private SphereWorldModel.GeoPoint cellPoint(ClosedQuadSphereTopology.CellId cell, double i, double j) {
         return rotate(base.cellPoint(cell, i, j));
      }
      private ClosedQuadSphereEmbedding.CellEnclosure cellEnclosure(ClosedQuadSphereTopology.CellId cell) {
         var enclosure = base.cellEnclosure(cell);
         return new ClosedQuadSphereEmbedding.CellEnclosure(rotate(enclosure.center()), enclosure.radiusMeters());
      }
      private Status status(ClosedQuadSphereTopology.CellId cell, double cap, double transition) {
         var enclosure = cellEnclosure(cell);
         double nearest = Double.POSITIVE_INFINITY;
         for (var key : extraordinary) nearest = Math.min(nearest, distance(enclosure.center(), point(key)));
         double conservative = Math.max(0, nearest - enclosure.radiusMeters());
         boolean incident = base.topology().vertices(cell).stream().anyMatch(extraordinary::contains);
         if (cap > 0 && (incident || conservative <= cap)) return Status.EXCLUDED;
         return conservative <= cap + transition || incident ? Status.TRANSITION : Status.ORDINARY;
      }
      private SphereWorldModel.GeoPoint rotate(SphereWorldModel.GeoPoint point) {
         Vec3 p = Vec3.from(point);
         double x = rotation[0][0] * p.x + rotation[0][1] * p.y + rotation[0][2] * p.z;
         double y = rotation[1][0] * p.x + rotation[1][1] * p.y + rotation[1][2] * p.z;
         double z = rotation[2][0] * p.x + rotation[2][1] * p.y + rotation[2][2] * p.z;
         return new SphereWorldModel.GeoPoint(Math.toDegrees(Math.atan2(y, Math.hypot(x, z))),
            Math.toDegrees(Math.atan2(z, x)), point.elevationMeters());
      }
   }

   private record Vec3(double x, double y, double z) {
      private static Vec3 from(SphereWorldModel.GeoPoint p) {
         double lat = Math.toRadians(p.latitude()), lon = Math.toRadians(p.longitude());
         double c = Math.cos(lat);
         return new Vec3(c * Math.cos(lon), Math.sin(lat), c * Math.sin(lon));
      }
      private double dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }
      private Vec3 cross(Vec3 other) { return new Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x); }
      private double length() { return Math.sqrt(dot(this)); }
   }
}
