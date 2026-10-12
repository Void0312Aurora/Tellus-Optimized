package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.Cache;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.geo.WebMercatorTileMath;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual source cache and interpolation using synthetic raster fixtures. */
class TellusGeodeticElevationTest {
   @TempDir
   static Path gameDir;
   private static String previousRelease;
   private static String previousGameDir;
   private static String previousConfigDir;

   @BeforeAll
   static void bootstrapOffline() {
      previousRelease = System.getProperty("tellus.overture.release");
      previousGameDir = System.getProperty("tellus.gameDir");
      previousConfigDir = System.getProperty("tellus.configDir");
      System.setProperty("tellus.overture.release", "2026-06-17.0");
      System.setProperty("tellus.gameDir", gameDir.toString());
      System.setProperty("tellus.configDir", gameDir.resolve("config").toString());
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @AfterAll
   static void restoreRelease() {
      if (previousRelease == null) System.clearProperty("tellus.overture.release");
      else System.setProperty("tellus.overture.release", previousRelease);
      if (previousGameDir == null) System.clearProperty("tellus.gameDir");
      else System.setProperty("tellus.gameDir", previousGameDir);
      if (previousConfigDir == null) System.clearProperty("tellus.configDir");
      else System.setProperty("tellus.configDir", previousConfigDir);
   }

   @Test
   void matchesLegacySamplingAtScaleOneInFiveRegions() throws Exception {
      var source = new TellusElevationSource();
      double[][] regions = {{46.5775, 8.0058}, {48.8584, 2.2945}, {37.8199, -122.4783}, {24.0, 13.0}, {-33.8568, 151.2153}};
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         for (double[] region : regions) {
            var point = new SphereWorldModel.GeoPoint(region[0], region[1], 0.0);
            int zoom = TellusElevationSource.selectPreviewZoom(1.0, 1.0, 18);
            var coordinate = WebMercatorTileMath.project(point.longitude(), point.latitude(), zoom, 512);
            seedNeighbors(source, "cache", zoom, coordinate, (short)400, true);
            double blockX = point.longitude() * EarthProjection.blocksPerDegree(1.0);
            double blockZ = EarthProjection.latToBlockZ(point.latitude(), 1.0);
            double legacy = source.sampleElevationMeters(blockX, blockZ, 1.0);
            double geodetic = source.sampleElevationMeters(point, 1.0);
            assertEquals(legacy, geodetic, 0.0001, "scale=1 must not change interior DEM interpolation");
         }
      }
   }

   @Test
   void interpolatesAcrossTheAntimeridianInsteadOfClampingLastPixel() throws Exception {
      var source = new TellusElevationSource();
      double scale = 40_000.0;
      int zoom = TellusElevationSource.selectPreviewZoom(scale, scale, 18);
      int pixels = (1 << zoom) * 512;
      double longitude = 180.0 - 180.0 / pixels; // Halfway between the last and first global pixel.
      var point = new SphereWorldModel.GeoPoint(20.0, longitude, 0.0);
      var coordinate = WebMercatorTileMath.project(longitude, 20.0, zoom, 512);
      put(source, "cache", zoom, coordinate.tileX(), coordinate.tileY(), constant((short)100));
      put(source, "cache", zoom, 0, coordinate.tileY(), constant((short)200));
      var sample = source.sampleResolvedPreviewElevationMetersMemoryOnly(point, scale, false, scale);
      assertEquals(150.0, sample.elevationMeters(), 1.0E-9);
   }

   @Test
   void resolvesBathymetryAndTerrainFallbackWithoutNetwork() throws Exception {
      var source = new TellusElevationSource();
      var point = new SphereWorldModel.GeoPoint(20.0, 10.0, 0.0);
      int landZoom = TellusElevationSource.selectPreviewZoom(1.0, 1.0, 18);
      seedNeighbors(source, "cache", landZoom, WebMercatorTileMath.project(10.0, 20.0, landZoom, 512), (short)-5, false);
      int oceanZoom = TellusElevationSource.selectPreviewZoom(1.0, 1.0, 17);
      seedNeighbors(source, "oceanCache", oceanZoom, WebMercatorTileMath.project(10.0, 20.0, oceanZoom, 512), (short)-80, false);
      var ocean = source.sampleResolvedPreviewElevationMetersMemoryOnly(point, 1.0, true, 1.0);
      assertEquals(-80.0, ocean.elevationMeters());
      assertEquals(TellusElevationSource.DemUsage.OPENWATERS, ocean.primaryProvider());

      var fallbackSource = new TellusElevationSource();
      seedNeighbors(fallbackSource, "cache", 12, WebMercatorTileMath.project(10.0, 20.0, 12, 512), (short)123, false);
      assertEquals(123.0, fallbackSource.sampleResolvedPreviewElevationMetersMemoryOnly(point, 1.0, false, 1.0).elevationMeters());
   }

   @Test
   void polesAndMissingTilesRemainUnavailable() {
      var source = new TellusElevationSource();
      for (var point : new SphereWorldModel.GeoPoint[]{
         new SphereWorldModel.GeoPoint(90.0, 0.0, 0.0), new SphereWorldModel.GeoPoint(20.0, 10.0, 0.0)
      }) {
         var sample = source.sampleResolvedPreviewElevationMetersMemoryOnly(point, 1.0, false, 1.0);
         assertTrue(Double.isNaN(sample.elevationMeters()));
         assertFalse(sample.mapterhornAvailable());
      }
   }

   private static void seedNeighbors(
      TellusElevationSource source, String cache, int zoom, WebMercatorTileMath.TileCoordinate c, short height, boolean ramp
   ) throws Exception {
      for (int dz = 0; dz <= 1; dz++) {
         for (int dx = 0; dx <= 1; dx++) {
            ShortRaster raster = constant(height);
            if (ramp) {
               for (int y = 0; y < 512; y++) {
                  for (int x = 0; x < 512; x++) raster.set(x, y, (short)(height + x + y));
               }
            }
            put(source, cache, zoom, c.tileX() + dx, c.tileY() + dz, raster);
         }
      }
   }

   private static ShortRaster constant(short height) {
      ShortRaster raster = ShortRaster.create(512, 512);
      raster.fill(height);
      return raster;
   }

   @SuppressWarnings("unchecked")
   private static void put(TellusElevationSource source, String cacheName, int zoom, int x, int y, ShortRaster raster) throws Exception {
      Class<?> keyClass = Class.forName(TellusElevationSource.class.getName() + "$TileKey");
      Constructor<?> constructor = keyClass.getDeclaredConstructor(int.class, int.class, int.class);
      constructor.setAccessible(true);
      var field = TellusElevationSource.class.getDeclaredField(cacheName);
      field.setAccessible(true);
      ((Cache<Object, ShortRaster>)field.get(source)).put(constructor.newInstance(zoom, x, y), raster);
   }
}
