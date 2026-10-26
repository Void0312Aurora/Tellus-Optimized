package com.yucareux.tellus.research;

import com.yucareux.tellus.worldgen.global.ClosedQuadSphereEmbedding;
import com.yucareux.tellus.worldgen.global.ClosedQuadSphereTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finite-difference local metric comparison for the same three mappings. */
public final class MappingLocalMetricStudy {
   private static final double R = SphereWorldModel.EARTH_RADIUS_METERS;
   private static final double REFERENCE_METERS_PER_COORDINATE = Math.PI * R / 4.0;
   private static final double[] CAPS_KM = {0.0, 100.0, 300.0, 360.0};
   private static final int GRID = 65;

   private MappingLocalMetricStudy() { }

   public static void main(String[] args) throws Exception {
      Locale.setDefault(Locale.ROOT);
      Path output = Path.of(args.length == 0
         ? "build/rfc-mapping-decision/mapping-local-metrics.csv" : args[0]);
      Files.createDirectories(output.toAbsolutePath().getParent());
      try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
         csv.println("mapping,cap_radius_km,transition_width_km,step_m,samples,min_local_scale,"
            + "max_local_scale,max_local_angle_error_degrees,max_local_aspect");
         for (double cap : CAPS_KM) {
            write(csv, "equiangular-cube", embedding(ClosedQuadSphereEmbedding.Mapping.EQUANGULAR_CUBE), cap);
            write(csv, "spherified-cube", embedding(ClosedQuadSphereEmbedding.Mapping.SPHERIFIED_CUBE), cap);
            write(csv, "conformal-cube-reference", embedding(new ConformalCubedSphere()), cap);
         }
      }
      System.out.println("Local metric study saved to " + output.toAbsolutePath());
   }

   private static ClosedQuadSphereEmbedding embedding(ClosedQuadSphereEmbedding.Mapping mapping) {
      var topology = ClosedQuadSphereTopology.create(1);
      return new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1), mapping);
   }

   private static ClosedQuadSphereEmbedding embedding(ConformalCubedSphere mapping) {
      var topology = ClosedQuadSphereTopology.create(1);
      return new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1), mapping);
   }

   private static void write(PrintWriter csv, String id, ClosedQuadSphereEmbedding embedding, double capKm) {
      List<Coordinate> points = new ArrayList<>();
      double limit = capKm * 1000.0 + 50_000.0;
      for (int face = 0; face < 6; face++) {
         for (int i = 1; i < GRID; i++) for (int j = 1; j < GRID; j++) {
            double u = 2.0 * i / GRID - 1.0;
            double v = 2.0 * j / GRID - 1.0;
            if (clearance(point(embedding, face, u, v)) > limit) points.add(new Coordinate(face, u, v));
         }
         for (int i = 0; i <= 180; i++) {
            double theta = i * Math.PI / 360.0;
            for (int cornerU : new int[]{-1, 1}) for (int cornerV : new int[]{-1, 1}) {
               var corner = point(embedding, face, cornerU, cornerV);
               double low = 0.0, high = 1.0;
               for (int iteration = 0; iteration < 55; iteration++) {
                  double mid = (low + high) * 0.5;
                  double u = cornerU * (1.0 - mid * Math.cos(theta));
                  double v = cornerV * (1.0 - mid * Math.sin(theta));
                  if (distance(corner, point(embedding, face, u, v)) < limit + 1.0) low = mid;
                  else high = mid;
               }
               double u = cornerU * (1.0 - high * Math.cos(theta));
               double v = cornerV * (1.0 - high * Math.sin(theta));
               points.add(new Coordinate(face, u, v));
            }
         }
      }
      double step = 1.0;
      double min = Double.POSITIVE_INFINITY, max = 0.0, maxAngle = 0.0, maxAspect = 1.0;
      for (Coordinate c : points) {
         Sample sample = measure(embedding, c.face, c.u, c.v, step);
         min = Math.min(min, Math.min(sample.scaleU, sample.scaleV));
         max = Math.max(max, Math.max(sample.scaleU, sample.scaleV));
         maxAngle = Math.max(maxAngle, sample.angleErrorDegrees);
         maxAspect = Math.max(maxAspect, sample.aspect);
      }
      csv.printf(Locale.ROOT, "%s,%.0f,50,%.1f,%d,%.9f,%.9f,%.9g,%.12f%n",
         id, capKm, step, points.size(), min, max, maxAngle, maxAspect);
      csv.flush();
   }

   private static Sample measure(ClosedQuadSphereEmbedding embedding, int face, double u, double v, double stepMeters) {
      double du = stepMeters / REFERENCE_METERS_PER_COORDINATE;
      Vec3 center = vector(point(embedding, face, u, v));
      double uLow = Math.max(-1.0, u - du), uHigh = Math.min(1.0, u + du);
      double vLow = Math.max(-1.0, v - du), vHigh = Math.min(1.0, v + du);
      Vec3 tangentU = tangent(center, vector(point(embedding, face, uHigh, v)),
         vector(point(embedding, face, uLow, v)),
         (uHigh - uLow) * REFERENCE_METERS_PER_COORDINATE);
      Vec3 tangentV = tangent(center, vector(point(embedding, face, u, vHigh)),
         vector(point(embedding, face, u, vLow)),
         (vHigh - vLow) * REFERENCE_METERS_PER_COORDINATE);
      double scaleU = tangentU.length(), scaleV = tangentV.length();
      double cosine = clamp(tangentU.dot(tangentV) / (scaleU * scaleV), -1.0, 1.0);
      double angleError = Math.toDegrees(Math.abs(Math.acos(cosine) - Math.PI * 0.5));
      double trace = scaleU * scaleU + scaleV * scaleV;
      double determinant = scaleU * scaleU * scaleV * scaleV * (1.0 - cosine * cosine);
      double discriminant = Math.sqrt(Math.max(0.0, trace * trace - 4.0 * determinant));
      double largest = Math.sqrt((trace + discriminant) * 0.5);
      double smallest = Math.sqrt(Math.max(1.0E-30, (trace - discriminant) * 0.5));
      return new Sample(scaleU, scaleV, angleError, largest / smallest);
   }

   private static Vec3 tangent(Vec3 center, Vec3 plus, Vec3 minus, double coordinateDistanceMeters) {
      Vec3 derivative = plus.subtract(minus).scale(R / coordinateDistanceMeters);
      return derivative.subtract(center.scale(center.dot(derivative)));
   }

   private static SphereWorldModel.GeoPoint point(ClosedQuadSphereEmbedding embedding, int face, double u, double v) {
      return embedding.cellPoint(new ClosedQuadSphereTopology.CellId(face, 0, 0), (u + 1.0) * 0.5, (v + 1.0) * 0.5);
   }

   private static double clearance(SphereWorldModel.GeoPoint point) {
      Vec3 v = vector(point);
      double nearest = (Math.abs(v.x) + Math.abs(v.y) + Math.abs(v.z)) / Math.sqrt(3.0);
      return R * Math.acos(clamp(nearest, -1.0, 1.0));
   }

   private static double distance(SphereWorldModel.GeoPoint first, SphereWorldModel.GeoPoint second) {
      Vec3 a = vector(first), b = vector(second);
      return R * Math.atan2(a.cross(b).length(), clamp(a.dot(b), -1.0, 1.0));
   }

   private static Vec3 vector(SphereWorldModel.GeoPoint point) {
      double latitude = Math.toRadians(point.latitude());
      double longitude = Math.toRadians(point.longitude());
      double cosine = Math.cos(latitude);
      return new Vec3(cosine * Math.cos(longitude), Math.sin(latitude), cosine * Math.sin(longitude));
   }

   private static double clamp(double value, double low, double high) {
      return Math.max(low, Math.min(high, value));
   }

   private record Coordinate(int face, double u, double v) { }
   private record Sample(double scaleU, double scaleV, double angleErrorDegrees, double aspect) { }
   private record Vec3(double x, double y, double z) {
      private double dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }
      private Vec3 cross(Vec3 other) { return new Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x); }
      private Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
      private Vec3 scale(double factor) { return new Vec3(x * factor, y * factor, z * factor); }
      private double length() { return Math.sqrt(dot(this)); }
   }
}
