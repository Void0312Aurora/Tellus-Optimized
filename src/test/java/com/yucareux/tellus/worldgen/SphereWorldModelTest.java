package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

   @Test
   void chunkCenterIsEightBlocksFromItsOrigin() {
      var chunk = new SphereWorldModel.SphereChunkAddress(0, 100, 100);
      var expected = MODEL.exactSurfacePoint(0, 1608.0, 1608.0);
      assertEquals(0.0, SphereWorldModel.greatCircleDistanceMeters(expected, MODEL.exactSurfacePoint(chunk)), 1.0E-9);
   }

   @Test
   void logarithmicMapPreservesSubMeterOffsets() {
      for (double latitude : new double[]{0.0, 45.0, 85.0}) {
         var anchor = new SphereWorldModel.GeoPoint(latitude, 179.99, 0.0);
         var offset = new SphereWorldModel.LocalOffset(0.1, 0.2);
         var point = SphereWorldModel.expandFrom(anchor, offset, 12);
         var recovered = SphereWorldModel.localOffset(anchor, point);
         assertEquals(offset.eastMeters(), recovered.eastMeters(), 1.0E-7);
         assertEquals(offset.northMeters(), recovered.northMeters(), 1.0E-7);
      }
   }

   @Test
   void preparedChartsRaiseTheOrderWhenLargeScaleNeedsIt() {
      for (double scale : new double[]{1.0, 30.0, 1000.0}) {
         var model = SphereWorldModel.forWorldScale(scale);
         for (int face = 0; face < 6; face++) {
            long last = model.config().faceBlocks() - 1L;
            for (long x : new long[]{0, last / 2, last}) {
               var block = new SphereWorldModel.SphereBlockAddress(face, x, last);
               var chart = model.prepareChart(model.chartForBlock(face, x, last));
               assertTrue(chart.errorBoundMeters() <= model.config().truncationToleranceMeters());
               if (scale == 1000.0) assertTrue(chart.expansionOrder() > 3);
               assertTrue(SphereWorldModel.greatCircleDistanceMeters(model.surfacePoint(chart, block),
                  model.exactSurfacePoint(face, x + 0.5, last + 0.5)) <= model.config().truncationToleranceMeters());
               var exact = model.exactSurfacePoint(face, x + 0.5, last + 0.5);
               assertTrue(SphereWorldModel.greatCircleDistanceMeters(model.expand(chart.key(),
                  model.localOffset(chart.key(), exact)), exact) <= model.config().truncationToleranceMeters());
            }
         }
      }
   }

   @Test
   void faceTopologyMapsEngineCoordinatesAndEveryFaceEdge() {
      var topology = new com.yucareux.tellus.worldgen.global.SphereFaceTopology(MODEL);
      long last = MODEL.chunksPerFace() - 1L;
      long half = MODEL.chunksPerFace() / 2L;
      for (int face = 0; face < SphereWorldModel.FACE_COUNT; face++) {
         var center = topology.address(face, 0, 0);
         assertEquals(face, topology.engineCoordinate(center).face());
         assertEquals(0, topology.engineCoordinate(center).chunkX());
         assertEquals(0, topology.engineCoordinate(center).chunkZ());
         assertEquals(new SphereWorldModel.SphereChunkAddress(face, half + 1, half), topology.neighbor(center, 1, 0));
         assertEquals(new SphereWorldModel.SphereChunkAddress(face, half - 1, half), topology.neighbor(center, -1, 0));
         assertEquals(new SphereWorldModel.SphereChunkAddress(face, half, half + 1), topology.neighbor(center, 0, 1));
         assertEquals(new SphereWorldModel.SphereChunkAddress(face, half, half - 1), topology.neighbor(center, 0, -1));
         for (var edge : new SphereWorldModel.SphereChunkAddress[]{
            new SphereWorldModel.SphereChunkAddress(face, 0, half),
            new SphereWorldModel.SphereChunkAddress(face, last, half),
            new SphereWorldModel.SphereChunkAddress(face, half, 0),
            new SphereWorldModel.SphereChunkAddress(face, half, last)
         }) {
            int dx = edge.chunkX() == 0 ? -1 : edge.chunkX() == last ? 1 : 0;
            int dz = edge.chunkZ() == 0 ? -1 : edge.chunkZ() == last ? 1 : 0;
            var neighbor = topology.neighbor(edge, dx, dz);
            assertNotEquals(face, neighbor.face(), "edge must transition to another face");
            assertTrue(neighbor.chunkX() >= 0 && neighbor.chunkX() <= last);
            assertTrue(neighbor.chunkZ() >= 0 && neighbor.chunkZ() <= last);
         }
      }
      assertThrows(IllegalArgumentException.class, () -> topology.address(0, half, 0));
   }

   private static double longitudeDelta(double first, double second) {
      double delta = (first - second) % 360.0;
      if (delta > 180.0) delta -= 360.0;
      if (delta <= -180.0) delta += 360.0;
      return delta;
   }
}
