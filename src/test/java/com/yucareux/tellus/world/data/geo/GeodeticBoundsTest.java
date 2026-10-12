package com.yucareux.tellus.world.data.geo;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeodeticBoundsTest {
   @Test
   void dateLineCapSplitsIntoTwoSmallWindows() {
      var center = new SphereWorldModel.GeoPoint(20.0, 179.999, 0.0);
      var bounds = GeodeticBounds.around(center, 1_000.0);
      var windows = bounds.mercatorWindows();
      assertEquals(2, windows.size());
      assertTrue(windows.stream().allMatch(b -> b.east() - b.west() < 0.02));
      assertFalse(bounds.contains(new SphereWorldModel.GeoPoint(20.0, 0.0, 0.0)));
   }

   @Test
   void envelopeContainsEveryBearingOfTheSphericalCap() {
      for (double lat : new double[]{-80.0, 0.0, 45.0, 80.0}) {
         for (double lon : new double[]{-179.99, -10.0, 179.99}) {
            var center = new SphereWorldModel.GeoPoint(lat, lon, 0.0);
            var bounds = GeodeticBounds.around(center, 1_001.0);
            for (int bearing = 0; bearing < 360; bearing += 3) {
               double angle = Math.toRadians(bearing);
               var edge = SphereWorldModel.expandFrom(center,
                  new SphereWorldModel.LocalOffset(1_000.0 * Math.cos(angle), 1_000.0 * Math.sin(angle)), 12);
               assertTrue(bounds.contains(edge), () -> "cap excluded " + edge);
            }
         }
      }
   }

   @Test
   void poleHasValidGeometryButNoMercatorCoverage() {
      var bounds = GeodeticBounds.around(new SphereWorldModel.GeoPoint(90.0, 0.0, 0.0), 1_000.0);
      assertEquals(-180.0, bounds.west());
      assertEquals(180.0, bounds.east());
      assertTrue(bounds.mercatorWindows().isEmpty());
   }

   @Test
   void invalidBoundsAndRadiusFailBeforeAQuery() {
      assertThrows(IllegalArgumentException.class, () -> new GeodeticBounds(5.0, 0.0, -5.0, 0.0));
      assertThrows(IllegalArgumentException.class,
         () -> GeodeticBounds.around(new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0), Double.NaN));
   }
}
