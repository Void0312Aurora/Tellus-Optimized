package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.ClosedQuadSphereEmbedding;
import com.yucareux.tellus.worldgen.global.ClosedQuadSphereTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

   @Test
   void connectedEmbeddingReportsGlobalMetricsAndExtraordinaryRegions() {
      var topology = ClosedQuadSphereTopology.create(5);
      var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0));
      var metrics = embedding.measureMetrics();

      assertTrue(metrics.cells() == topology.cells().size());
      assertTrue(metrics.edges() == topology.edges().size());
      assertTrue(metrics.minEdgeScale() > 0.0);
      assertTrue(metrics.maxEdgeScale() > metrics.minEdgeScale());
      assertTrue(metrics.maxAngleErrorRadians() > 0.0);
      assertTrue(metrics.minAreaScale() > 0.0);
      assertTrue(metrics.maxAreaScale() > metrics.minAreaScale());
      assertEquals(0.7100217862, metrics.minEdgeScale(), 1.0E-9);
      assertEquals(0.9961768416, metrics.maxEdgeScale(), 1.0E-9);
      assertEquals(0.5235987756, metrics.maxAngleErrorRadians(), 1.0E-9);
      assertEquals(0.7860693612, metrics.minAreaScale(), 1.0E-9);
      assertEquals(0.9919013596, metrics.maxAreaScale(), 1.0E-9);
      assertTrue(metrics.extraordinaryCells() == 24,
         () -> "unexpected extraordinary cell count=" + metrics.extraordinaryCells());
      assertEquals(0.8583811673, metrics.extraordinaryMinEdgeScale(), 1.0E-9);
      assertEquals(0.7860693612, metrics.extraordinaryMinAreaScale(), 1.0E-9);
      assertTrue(metrics.extraordinaryMaxEdgeScale() >= metrics.extraordinaryMinEdgeScale());
      assertTrue(metrics.extraordinaryMaxAngleErrorRadians() >= 0.0);
      assertTrue(metrics.extraordinaryMaxAreaScale() >= metrics.extraordinaryMinAreaScale());
   }

   private static SphericalLatticePrototype.PatchDescriptor patch(long x, double east) {
      return new SphericalLatticePrototype.PatchDescriptor(GRID,
         new SphericalLatticePrototype.PatchKey(0, x, 0),
         new SphereWorldModel.GeoPoint(0, Math.toDegrees(2.0 * Math.atan(east / (2.0 * EARTH_R))), 0),
         50_000, 1024, SphericalLatticePrototype.ChartKind.STEREOGRAPHIC);
   }
}
