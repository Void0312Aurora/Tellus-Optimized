import com.yucareux.tellus.worldgen.global.ClosedQuadSphereEmbedding;
import com.yucareux.tellus.worldgen.global.ClosedQuadSphereTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphericalIncisionPolicy;
import java.util.Locale;

/** Reproducible coarse cap sweep; writes CSV to stdout, no Minecraft dependency. */
public final class SphericalIncisionStudy {
   public static void main(String[] args) {
      int[] resolutions = args.length == 0 ? new int[]{64, 128} : new int[args.length];
      for (int index = 0; index < args.length; index++) resolutions[index] = Integer.parseInt(args[index]);
      System.out.println("mapping,subdivision,nominal_edge_km,cap_radius_km,transition_width_km,ordinary_cells,transition_cells,excluded_cells,ordinary_polygon_fraction,transition_polygon_fraction,excluded_polygon_fraction,ideal_eight_cap_fraction,min_ordinary_edge_scale,max_ordinary_edge_scale,max_ordinary_angle_error_degrees,min_ordinary_area_scale,max_ordinary_area_scale,max_enclosure_radius_km");
      for (int n : resolutions) {
         var topology = ClosedQuadSphereTopology.create(n);
         for (var mapping : ClosedQuadSphereEmbedding.Mapping.values()) {
            var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0), mapping);
            // Geometry is invariant across cap policies; cache polygon area only
            // for this bounded study, not in the demand-driven world runtime.
            double[] polygonAreas = topology.cells().stream().mapToDouble(embedding::cellAreaMetersSquared).toArray();
            for (double capKm : new double[]{0, 100, 300, 360}) {
               var policy = new SphericalIncisionPolicy(topology, embedding,
                  new SphericalIncisionPolicy.Config(capKm * 1000, 50_000));
               int[] counts = new int[SphericalIncisionPolicy.CellStatus.values().length];
               double[] areas = new double[counts.length];
               double maxEnclosure = 0;
               for (int index = 0; index < topology.cells().size(); index++) {
                  var evidence = policy.classification(topology.cells().get(index));
                  int status = evidence.status().ordinal();
                  counts[status]++;
                  areas[status] += polygonAreas[index];
                  maxEnclosure = Math.max(maxEnclosure, evidence.cellRadiusMeters());
               }
               double sphereArea = 4 * Math.PI * SphereWorldModel.EARTH_RADIUS_METERS * SphereWorldModel.EARTH_RADIUS_METERS;
               // All radii here are smaller than half the corner separation;
               // the eight ideal spherical caps do not overlap.
               double alpha = capKm * 1000 / SphereWorldModel.EARTH_RADIUS_METERS;
               double idealCapFraction = 8 * Math.pow(Math.sin(alpha * 0.5), 2);
               if (counts[0] == 0) throw new IllegalStateException("study has no ordinary cells");
               var metrics = embedding.measureMetrics(policy::admitsOrdinaryMaterialization);
               System.out.printf(Locale.ROOT,
                  "%s,%d,%.6f,%.0f,50,%d,%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.6f%n",
                  mapping, n, metrics.nominalEdgeMeters() / 1000, capKm,
                  counts[0], counts[1], counts[2], areas[0] / sphereArea, areas[1] / sphereArea, areas[2] / sphereArea,
                  idealCapFraction, metrics.minEdgeScale(), metrics.maxEdgeScale(),
                  Math.toDegrees(metrics.maxAngleErrorRadians()), metrics.minAreaScale(), metrics.maxAreaScale(),
                  maxEnclosure / 1000);
            }
         }
      }
   }
}
