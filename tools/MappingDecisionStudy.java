package com.yucareux.tellus.research;

import com.yucareux.tellus.worldgen.global.ClosedQuadSphereEmbedding;
import com.yucareux.tellus.worldgen.global.ClosedQuadSphereTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphericalIncisionPolicy;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Same-condition geometry comparison for the research mapping candidates.
 *
 * <p>This deliberately lives outside the Fabric source sets. It compares
 * finite cells, conservative quarantine, and seams with one harness so a
 * runtime implementation cannot decide the geometry question by itself.</p>
 */
public final class MappingDecisionStudy {
   private static final double R = SphereWorldModel.EARTH_RADIUS_METERS;
   private static final String GRID_VERSION = "tellus:earth-cubed-sphere-rfc-1";
   private static final double TRANSITION_WIDTH_KM = 50.0;
   private static final double[] CAPS_KM = {100.0, 150.0, 300.0, 360.0};
   private static final int[] SUBDIVISIONS = {64, 128};

   private MappingDecisionStudy() { }

   public static void main(String[] args) throws Exception {
      Locale.setDefault(Locale.ROOT);
      Path output = Path.of(args.length == 0
         ? "build/rfc-mapping-decision/mapping-decision-baseline.csv" : args[0]);
      Files.createDirectories(output.toAbsolutePath().getParent());
      try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
         csv.println("mapping,subdivision,nominal_edge_km,cap_radius_km,transition_width_km,"
            + "ordinary_cells,transition_cells,excluded_cells,ordinary_polygon_fraction,"
            + "transition_polygon_fraction,excluded_polygon_fraction,ideal_eight_cap_fraction,"
            + "min_ordinary_edge_scale,max_ordinary_edge_scale,max_ordinary_angle_error_degrees,"
            + "min_ordinary_area_scale,max_ordinary_area_scale,max_enclosure_radius_km,"
            + "seam_vertex_mismatch_m,seam_interior_mismatch_m");
         for (int n : SUBDIVISIONS) {
            run(csv, n, "equiangular-cube", ClosedQuadSphereEmbedding.Mapping.EQUANGULAR_CUBE);
            run(csv, n, "spherified-cube", ClosedQuadSphereEmbedding.Mapping.SPHERIFIED_CUBE);
            runConformal(csv, n);
         }
      }
      Path metadata = output.resolveSibling(metadataName(output));
      Files.writeString(metadata, metadataJson(), StandardCharsets.UTF_8);
      System.out.println("Mapping decision study saved to " + output.toAbsolutePath());
      System.out.println("Mapping study metadata saved to " + metadata.toAbsolutePath());
   }

   private static String metadataName(Path output) {
      String name = output.getFileName().toString();
      int dot = name.lastIndexOf('.');
      return (dot > 0 ? name.substring(0, dot) : name) + "-metadata.json";
   }

   private static String metadataJson() {
      return """
         {
           "schema_version": 1,
           "grid_version": "tellus:earth-cubed-sphere-rfc-1",
           "planet": "tellus:earth",
           "embedding_scale": 1.0,
           "earth_radius_m": 6371008.8,
           "topology": {
             "face_count": 6,
             "ordinary_neighbor_count": 4,
             "closed_surface": true,
             "cell_family": "quadrilateral"
           },
           "subdivisions": [64, 128],
           "cap_radii_km": [100.0, 150.0, 300.0, 360.0],
           "transition_width_km": 50.0,
           "mappings": [
             "equiangular-cube",
             "spherified-cube",
             "conformal-cube-reference"
           ],
           "provisional_thresholds": {
             "seam_vertex_mismatch_m_max": 1.0e-7,
             "seam_interior_mismatch_m_max": 1.0e-7,
             "local_angle_error_degrees_max": 1.0e-4,
             "local_tangent_aspect_max": 1.00001,
             "relative_analytic_scale_error_max": 1.0e-5,
             "surface_inverse_error_m_max": 1.0e-6
           },
           "threshold_status": "provisional",
           "measurement_contract": {
             "finite_cell_metrics": [
               "ordinary_polygon_fraction",
               "ordinary_edge_scale",
               "ordinary_area_scale",
               "max_ordinary_angle_error_degrees",
               "max_enclosure_radius_km"
             ],
             "local_differential_metrics": [
               "local_scale",
               "local_angle_error_degrees",
               "local_aspect"
             ],
             "geographic_loss_is_separate": true
           }
         }
         """;
   }

   private static void run(PrintWriter csv, int n, String id, ClosedQuadSphereEmbedding.Mapping mapping) {
      var topology = ClosedQuadSphereTopology.create(n);
      var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0), mapping);
      writeRows(csv, id, topology, embedding);
   }

   private static void runConformal(PrintWriter csv, int n) {
      var topology = ClosedQuadSphereTopology.create(n);
      var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0),
         new ConformalCubedSphere());
      writeRows(csv, "conformal-cube-reference", topology, embedding);
   }

   private static void writeRows(PrintWriter csv, String id, ClosedQuadSphereTopology topology,
      ClosedQuadSphereEmbedding embedding) {
      double[] polygonAreas = topology.cells().stream().mapToDouble(embedding::cellAreaMetersSquared).toArray();
      double sphereArea = 4.0 * Math.PI * R * R;
      var seam = embedding.auditSeams(17);
      for (double capKm : CAPS_KM) {
         var policy = new SphericalIncisionPolicy(topology, embedding,
            new SphericalIncisionPolicy.Config(capKm * 1000.0, TRANSITION_WIDTH_KM * 1000.0));
         int[] counts = new int[SphericalIncisionPolicy.CellStatus.values().length];
         double[] areas = new double[counts.length];
         double maxEnclosure = 0.0;
         for (int index = 0; index < topology.cells().size(); index++) {
            var classification = policy.classification(topology.cells().get(index));
            int status = classification.status().ordinal();
            counts[status]++;
            areas[status] += polygonAreas[index];
            maxEnclosure = Math.max(maxEnclosure, classification.cellRadiusMeters());
         }
         var metrics = embedding.measureMetrics(policy::admitsOrdinaryMaterialization);
         double capRadians = capKm * 1000.0 / R;
         double idealCapFraction = 8.0 * Math.pow(Math.sin(capRadians * 0.5), 2.0);
         csv.printf(Locale.ROOT,
            "%s,%d,%.6f,%.0f,%.0f,%d,%d,%d,%.9f,%.9f,%.9f,%.9f,"
               + "%.9f,%.9f,%.9f,%.9f,%.9f,%.6f,%.9g,%.9g%n",
            id, topology.subdivision(), metrics.nominalEdgeMeters() / 1000.0, capKm,
            TRANSITION_WIDTH_KM, counts[0], counts[1], counts[2],
            areas[0] / sphereArea, areas[1] / sphereArea, areas[2] / sphereArea,
            idealCapFraction, metrics.minEdgeScale(), metrics.maxEdgeScale(),
            Math.toDegrees(metrics.maxAngleErrorRadians()), metrics.minAreaScale(),
            metrics.maxAreaScale(), maxEnclosure / 1000.0,
            seam.maxVertexMismatchMeters(), seam.maxInteriorMismatchMeters());
         csv.flush();
      }
   }
}
