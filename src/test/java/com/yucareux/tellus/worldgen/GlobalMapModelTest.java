package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.GlobalMapModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalMapModelTest {
   private static final GlobalMapModel MODEL = new GlobalMapModel(
      new GlobalMapModel.Config(1.0, 1024, 3, 0.25)
   );

   @Test
   void chartCoordinatesAreContinuousAcrossPositiveAndNegativeBoundaries() {
      assertEquals(new GlobalMapModel.ChartKey(0, -1), MODEL.chartForBlock(1023.99, -0.01));
      assertEquals(new GlobalMapModel.ChartKey(1, -1), MODEL.chartForBlock(1024.0, -1024.0));
      assertEquals(new GlobalMapModel.ChartKey(-1, 0), MODEL.chartForBlock(-0.01, 0.0));
      GlobalMapModel.ChartCoordinate coordinate = MODEL.toChartCoordinates(-0.01, 0.0);
      assertTrue(coordinate.localX() >= 0.0 && coordinate.localX() < 1024.0);
   }

   @Test
   void projectionRoundTripsGeodeticCoordinatesWithinMercatorDomain() {
      GlobalMapModel.GeoPoint point = new GlobalMapModel.GeoPoint(47.3769, 8.5417, 408.0);
      GlobalMapModel.BlockPoint block = MODEL.project(point);
      GlobalMapModel.GeoPoint roundTrip = MODEL.unproject(block.x(), block.z());
      assertEquals(point.latitude(), roundTrip.latitude(), 1.0E-10);
      assertEquals(point.longitude(), roundTrip.longitude(), 1.0E-10);
   }

   @Test
   void higherExpansionOrderReducesLocalSphericalError() {
      GlobalMapModel.GeoPoint anchor = new GlobalMapModel.GeoPoint(47.0, 8.0, 500.0);
      GlobalMapModel.LocalOffset offset = new GlobalMapModel.LocalOffset(12_000.0, 9_000.0);
      GlobalMapModel.GeoPoint order1 = GlobalMapModel.expandFrom(anchor, offset, 1);
      GlobalMapModel.GeoPoint order3 = GlobalMapModel.expandFrom(anchor, offset, 3);
      GlobalMapModel.GeoPoint order8 = GlobalMapModel.expandFrom(anchor, offset, 8);
      GlobalMapModel.GeoPoint exact = GlobalMapModel.expandFrom(anchor, offset, 12);
      assertTrue(distance(order3, exact) < distance(order1, exact));
      assertTrue(distance(order8, exact) <= distance(order3, exact));
      assertEquals(anchor.elevationMeters(), order3.elevationMeters(), 0.0);
   }

   @Test
   void chartErrorBoundAndSafeFootprintImproveWithExpansionOrder() {
      double error1 = MODEL.truncationErrorBoundMeters(50_000.0, 1);
      double error3 = MODEL.truncationErrorBoundMeters(50_000.0, 3);
      assertTrue(error3 < error1);
      assertTrue(MODEL.maxFootprintMeters(0.25, 3) > MODEL.maxFootprintMeters(0.25, 1));
   }

   @Test
   void chartSelectsAnOrderFromItsGroundFootprint() {
      GlobalMapModel.ChartKey key = new GlobalMapModel.ChartKey(0, 0);
      assertTrue(MODEL.chartHalfDiagonalMeters(key) > 0.0);
      int order = MODEL.recommendedExpansionOrder(key);
      assertTrue(order >= 1 && order <= 12);
      assertTrue(MODEL.truncationErrorBoundMeters(MODEL.chartHalfDiagonalMeters(key), order)
         <= MODEL.config().truncationToleranceMeters() || order == 12);
   }

   private static double distance(GlobalMapModel.GeoPoint first, GlobalMapModel.GeoPoint second) {
      double dLat = Math.toRadians(first.latitude() - second.latitude());
      double dLon = Math.toRadians(GlobalMapModel.shortestLongitudeDelta(first.longitude(), second.longitude()));
      double meanLat = Math.toRadians((first.latitude() + second.latitude()) * 0.5);
      return GlobalMapModel.EARTH_RADIUS_METERS * Math.hypot(dLat, Math.cos(meanLat) * dLon);
   }
}
