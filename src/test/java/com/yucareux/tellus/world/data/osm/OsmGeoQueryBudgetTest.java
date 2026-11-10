package com.yucareux.tellus.world.data.osm;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class OsmGeoQueryBudgetTest {
   @Test
   void smallGeographicQueryFitsWithoutChangingItsRectangleOrZoom() {
      var bounds = GeodeticBounds.around(new SphereWorldModel.GeoPoint(20.0, 10.0, 0.0), 96.0);
      assertTrue(OsmGeoQueryBudget.withinBudget(bounds, 14, 256));
   }

   @Test
   void coarseScaleStructureCapIsRejectedWithoutEnumeratingItsTiles() {
      double worldScale = 1000.0;
      double structureMarginMeters = 4.0 * 392.0 * worldScale;
      var bounds = GeodeticBounds.around(new SphereWorldModel.GeoPoint(0.0, 20.0, 0.0),
         32.0 * worldScale + structureMarginMeters);
      assertFalse(OsmGeoQueryBudget.withinBudget(bounds, 14, 256));
      // Admission must use the caller's actual resolved zoom, not a fixed z14 estimate.
      assertTrue(OsmGeoQueryBudget.withinBudget(bounds, 0, 1));
   }

   @Test
   void dateLineWindowsUseOneCombinedBudgetEvenWhenTheirZoomZeroKeysCoincide() {
      var bounds = new GeodeticBounds(19.99, 179.99, 20.01, -179.99);
      assertEquals(2, bounds.mercatorWindows().size());
      for (var window : bounds.mercatorWindows()) {
         assertTrue(OsmGeoQueryBudget.withinBudget(window, 0, 1));
      }
      assertFalse(OsmGeoQueryBudget.withinBudget(bounds, 0, 1));
      assertTrue(OsmGeoQueryBudget.withinBudget(bounds, 0, 2));
   }

   @Test
   void inclusiveTileBudgetBoundaryAdmitsEqualityButRejectsOneLess() {
      // At z1 this rectangle spans both X tiles and both Y tiles.
      var bounds = new GeodeticBounds(-0.1, -0.1, 0.1, 0.1);
      assertFalse(OsmGeoQueryBudget.withinBudget(bounds, 1, 3));
      assertTrue(OsmGeoQueryBudget.withinBudget(bounds, 1, 4));
      assertTrue(OsmGeoQueryBudget.withinBudget(bounds, 1, 5));
      assertTrue(OsmGeoQueryBudget.withinBudget(new GeodeticBounds(0.0, 0.0, 0.0, 0.0), 1, 1));
   }

   @Test
   void fullWorldCountsUseLongAtZoomSixteenAndTwenty() {
      var world = new GeodeticBounds(-90.0, -180.0, 90.0, 180.0);
      // 2^32 and 2^40 visits respectively: int multiplication would overflow to zero.
      assertFalse(OsmGeoQueryBudget.withinBudget(world, 16, Integer.MAX_VALUE));
      assertFalse(OsmGeoQueryBudget.withinBudget(world, 20, Integer.MAX_VALUE));
      assertTrue(OsmGeoQueryBudget.withinBudget(world, 0, 1));
   }

   @Test
   void polarCapCountIsBoundedAndUnsupportedPolarCoverageIsNotKnownEmpty() {
      // This supported Mercator sliver still has far more than int-max z20 visits.
      var cap = new GeodeticBounds(84.0, -180.0, 90.0, 180.0);
      assertFalse(OsmGeoQueryBudget.withinBudget(cap, 20, Integer.MAX_VALUE));
      assertFalse(OsmGeoQueryBudget.withinBudget(new GeodeticBounds(89.0, -1.0, 90.0, 1.0), 20, 256));
      assertFalse(OsmGeoQueryBudget.withinBudget(new GeodeticBounds(-90.0, -1.0, -89.0, 1.0), 20, 256));
   }

   @Test
   void invalidArgumentsAreRejectedRatherThanInterpretedAsFreeQueries() {
      var bounds = new GeodeticBounds(0.0, 0.0, 0.0, 0.0);
      assertThrows(NullPointerException.class, () -> OsmGeoQueryBudget.withinBudget(null, 14, 256));
      for (int zoom : new int[]{-1, 21, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
         assertThrows(IllegalArgumentException.class, () -> OsmGeoQueryBudget.withinBudget(bounds, zoom, 256));
      }
      for (int budget : new int[]{0, -1, Integer.MIN_VALUE}) {
         assertThrows(IllegalArgumentException.class, () -> OsmGeoQueryBudget.withinBudget(bounds, 14, budget));
      }
   }

   @Test
   void smallCountsMatchBothActualSourceTileEnumeratorsIncludingClampsAndDateLine() throws Exception {
      List<GeodeticBounds> samples = List.of(
         new GeodeticBounds(20.0, 10.0, 20.00001, 10.00001),
         new GeodeticBounds(-0.00001, -0.00001, 0.00001, 0.00001),
         new GeodeticBounds(19.99999, 179.99999, 20.00001, -179.99999),
         new GeodeticBounds(85.05112877, 179.99999, 85.05112878, 180.0),
         new GeodeticBounds(-85.05112878, -180.0, -85.05112877, -179.99999)
      );
      for (Class<?> source : new Class<?>[]{TellusOsmBuildingSource.class, TellusOsmRoadSource.class}) {
         for (int zoom : new int[]{0, 1, 2, 8, 14, 20}) {
            for (var bounds : samples) {
               int actualVisits = actualSmallTileVisits(source, bounds, zoom);
               String context = source.getSimpleName() + " z=" + zoom + " " + bounds;
               assertTrue(actualVisits > 0 && actualVisits <= 16, "small-only reflection fixture: " + context);
               assertTrue(OsmGeoQueryBudget.withinBudget(bounds, zoom, actualVisits), context);
               if (actualVisits > 1) {
                  assertFalse(OsmGeoQueryBudget.withinBudget(bounds, zoom, actualVisits - 1), context);
               }
            }
         }
      }
   }

   /** Only tiny rectangles reach the real source enumerators; large-cap tests call the guard alone. */
   private static int actualSmallTileVisits(Class<?> source, GeodeticBounds bounds, int zoom) throws Exception {
      Class<?> nativeBounds = Class.forName(source.getName() + "$GeoBounds");
      Constructor<?> constructor = nativeBounds.getDeclaredConstructor(double.class, double.class, double.class, double.class);
      constructor.setAccessible(true);
      Method tiles = source.getDeclaredMethod("tileKeysForBounds", nativeBounds, int.class);
      tiles.setAccessible(true);
      int count = 0;
      for (var window : bounds.mercatorWindows()) {
         Object rectangle = constructor.newInstance(window.south(), window.west(), window.north(), window.east());
         count += ((List<?>)tiles.invoke(null, rectangle, zoom)).size();
      }
      return count;
   }
}
