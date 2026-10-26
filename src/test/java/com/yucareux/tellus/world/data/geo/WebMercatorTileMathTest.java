package com.yucareux.tellus.world.data.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebMercatorTileMathTest {
   @Test
   void projectsEquatorAndPrimeMeridianToPyramidCenter() {
      WebMercatorTileMath.TileCoordinate coordinate = WebMercatorTileMath.project(0.0, 0.0, 0, 512);

      assertNotNull(coordinate);
      assertEquals(0, coordinate.tileX());
      assertEquals(0, coordinate.tileY());
      assertEquals(256.0, coordinate.globalPixelX(), 1.0E-12);
      assertEquals(256.0, coordinate.globalPixelY(), 1.0E-12);
   }

   @Test
   void wrapsBothAntimeridianRepresentationsToTheSameTile() {
      WebMercatorTileMath.TileCoordinate west = WebMercatorTileMath.project(-180.0, 20.0, 5, 512);
      WebMercatorTileMath.TileCoordinate east = WebMercatorTileMath.project(180.0, 20.0, 5, 512);

      assertNotNull(west);
      assertNotNull(east);
      assertEquals(west.tileX(), east.tileX());
      assertEquals(west.tileY(), east.tileY());
      assertEquals(west.globalPixelX(), east.globalPixelX(), 1.0E-12);
   }

   @Test
   void rejectsPointsOutsideFiniteMercatorLatitudeDomain() {
      assertNull(WebMercatorTileMath.project(0.0, 90.0, 3, 512));
      assertNull(WebMercatorTileMath.project(0.0, -90.0, 3, 512));
      assertNull(WebMercatorTileMath.project(Double.NaN, 0.0, 3, 512));
   }

   @Test
   void producesConsistentTileAndGlobalPixelCoordinates() {
      WebMercatorTileMath.TileCoordinate coordinate = WebMercatorTileMath.project(45.0, 30.0, 3, 512);

      assertNotNull(coordinate);
      assertEquals(5, coordinate.tileX());
      assertEquals(3, coordinate.tileY());
      assertEquals(45.0, coordinate.tileXCoordinate() / 8.0 * 360.0 - 180.0, 1.0E-12);
      assertEquals(30.0, Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - coordinate.tileYCoordinate() / 4.0)))), 1.0E-10);
      assertEquals(
         coordinate.globalPixelX() / 512.0,
         coordinate.tileXCoordinate(),
         1.0E-12
      );
      assertEquals(
         coordinate.globalPixelY() / 512.0,
         coordinate.tileYCoordinate(),
         1.0E-12
      );
   }

   @Test
   void bothLatitudeLimitsAddressValidEdgePixels() {
      for (double latitude : new double[]{WebMercatorTileMath.MIN_LATITUDE, WebMercatorTileMath.MAX_LATITUDE}) {
         var coordinate = WebMercatorTileMath.project(0.0, latitude, 18, 512);
         assertNotNull(coordinate);
         assertTrue(coordinate.tileY() >= 0 && coordinate.tileY() < (1 << 18));
      }
   }
}
