package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.ClosedQuadSphereEmbedding;
import com.yucareux.tellus.worldgen.global.ClosedQuadSphereTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SeamGeometryRegressionTest {
   private static final double EARTH_R = SphereWorldModel.EARTH_RADIUS_METERS;
   private static final SphericalLatticePrototype.GridVersion GRID =
      new SphericalLatticePrototype.GridVersion("tellus:earth", 1);

   @Test
   void independentAdjacentStereographicChartsExposeTheReportedEndpointMismatch() {
      var west = patch(0, -25_000);
      var east = patch(1, 25_000);
      double maxMismatch = 0.0;
      for (int sample = 0; sample <= 100; sample++) {
         double north = -25_000.0 + 50_000.0 * sample / 100.0;
         var source = west.pointAt(25_000, north);
         var target = east.pointAt(-25_000, north);
         maxMismatch = Math.max(maxMismatch, SphereWorldModel.greatCircleDistanceMeters(source, target));
      }
      assertTrue(maxMismatch > 0.19 && maxMismatch < 0.20,
         "expected the independent-chart seam mismatch, got " + maxMismatch + " m");
   }

   @Test
   void canonicalClosedCubeEmbeddingMatchesEverySharedEdgeInterior() {
      var embedding = new ClosedQuadSphereEmbedding(ClosedQuadSphereTopology.create(5),
         SphereWorldModel.forWorldScale(1.0));
      var report = embedding.auditSeams(65);

      assertTrue(report.edges() > 0);
      assertTrue(report.maxVertexMismatchMeters() < 1.0E-7,
         () -> "vertex mismatch=" + report.maxVertexMismatchMeters());
      assertTrue(report.maxInteriorMismatchMeters() < 1.0E-7,
         () -> "edge mismatch=" + report.maxInteriorMismatchMeters());
   }

   private static SphericalLatticePrototype.PatchDescriptor patch(long x, double east) {
      return new SphericalLatticePrototype.PatchDescriptor(GRID,
         new SphericalLatticePrototype.PatchKey(0, x, 0),
         new SphereWorldModel.GeoPoint(0, Math.toDegrees(2.0 * Math.atan(east / (2.0 * EARTH_R))), 0),
         50_000, 1024, SphericalLatticePrototype.ChartKind.STEREOGRAPHIC);
   }
}
