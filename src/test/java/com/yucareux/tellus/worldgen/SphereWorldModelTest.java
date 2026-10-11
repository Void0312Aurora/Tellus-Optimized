package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SphereWorldModelTest {
   private static final double TEST_WORLD_SCALE = Math.PI * SphereWorldModel.EARTH_RADIUS_METERS * 0.5 / 4096.0;
   private static final SphereWorldModel MODEL = new SphereWorldModel(
      new SphereWorldModel.Config(TEST_WORLD_SCALE, 4096, 16, 3, 0.25)
   );

   @Test
   void cubedSphereCoversAllSixFacesWithoutMercatorPoleClamp() {
      double[][] points = {
         {0.0, 0.0}, {0.0, 90.0}, {0.0, 179.9}, {0.0, -179.9},
         {89.9, 0.0}, {-89.9, 0.0}, {45.0, 45.0}, {-45.0, -135.0}
      };
      for (double[] point : points) {
         SphereWorldModel.GeoPoint geo = new SphereWorldModel.GeoPoint(point[0], point[1], 0.0);
         SphereWorldModel.SphereBlockAddress address = MODEL.blockForGeo(geo);
         SphereWorldModel.GeoPoint roundTrip = MODEL.exactSurfacePoint(address.face(), address.blockX(), address.blockZ());
         assertTrue(Math.abs(roundTrip.latitude() - geo.latitude()) < 1.0,
            () -> "latitude round trip failed for " + geo);
         assertTrue(Math.abs(longitudeDelta(roundTrip.longitude(), geo.longitude())) < 1.0,
            () -> "longitude round trip failed for " + geo);
      }
   }

   @Test
   void adjacentFaceEdgesMapToTheSameSphere() {
      for (int sample = 0; sample <= 16; sample++) {
         double v = -1.0 + sample / 8.0;
         SphereWorldModel.GeoPoint zRight = MODEL.exactSurfacePoint(4, MODEL.config().faceBlocks(), (v + 1.0) * 0.5 * MODEL.config().faceBlocks());
         SphereWorldModel.GeoPoint xLeft = MODEL.exactSurfacePoint(0, 0.0, (v + 1.0) * 0.5 * MODEL.config().faceBlocks());
         assertEquals(0.0, SphereWorldModel.greatCircleDistanceMeters(zRight, xLeft), 0.25);
      }
   }

   @Test
   void cardinalNeighborCrossesCubedSphereFaceEdge() {
      int edge = MODEL.config().faceBlocks() - 1;
      SphereWorldModel.SphereBlockAddress source = new SphereWorldModel.SphereBlockAddress(4, edge, 2048);
      SphereWorldModel.SphereBlockAddress neighbor = MODEL.neighbor(source, 1, 0);
      assertEquals(0, neighbor.face());
      assertTrue(neighbor.blockX() < 2);
      assertTrue(Math.abs(neighbor.blockZ() - source.blockZ()) <= 1);

      for (int face = 0; face < SphereWorldModel.FACE_COUNT; face++) {
         assertTrue(MODEL.neighbor(new SphereWorldModel.SphereBlockAddress(face, 0, 2048), -1, 0).face() != face);
         assertTrue(MODEL.neighbor(new SphereWorldModel.SphereBlockAddress(face, edge, 2048), 1, 0).face() != face);
         assertTrue(MODEL.neighbor(new SphereWorldModel.SphereBlockAddress(face, 2048, 0), 0, -1).face() != face);
         assertTrue(MODEL.neighbor(new SphereWorldModel.SphereBlockAddress(face, 2048, edge), 0, 1).face() != face);
      }
   }

   @Test
   void chartAndChunkMappingAreFiniteAndProgressive() {
      SphereWorldModel.SphereChunkAddress address = new SphereWorldModel.SphereChunkAddress(4, 32, 32);
      SphereWorldModel.ChartKey chart = MODEL.chartForChunk(address);
      SphereWorldModel.GeoPoint exact = MODEL.exactSurfacePoint(address);
      SphereWorldModel.GeoPoint orderOne = MODEL.surfacePoint(address, 1);
      SphereWorldModel.GeoPoint orderThree = MODEL.surfacePoint(address, 3);
      assertEquals(4, chart.face());
      assertTrue(MODEL.chartHalfDiagonalMeters(chart) > 0.0);
      assertTrue(SphereWorldModel.greatCircleDistanceMeters(orderThree, exact)
         <= SphereWorldModel.greatCircleDistanceMeters(orderOne, exact));
      assertTrue(MODEL.recommendedExpansionOrder(chart) >= 1);
   }

   @Test
   void localExpansionRoundTripsAtTheChartAnchor() {
      SphereWorldModel.ChartKey chart = new SphereWorldModel.ChartKey(2, 1, 1);
      SphereWorldModel.GeoPoint anchor = MODEL.chartCenter(chart);
      SphereWorldModel.GeoPoint expanded = MODEL.expand(chart, new SphereWorldModel.LocalOffset(0.0, 0.0));
      assertEquals(0.0, SphereWorldModel.greatCircleDistanceMeters(anchor, expanded), 1.0E-9);
   }

   private static double longitudeDelta(double first, double second) {
      double delta = (first - second) % 360.0;
      if (delta > 180.0) delta -= 360.0;
      if (delta <= -180.0) delta += 360.0;
      return delta;
   }
}
