package com.yucareux.tellus.world.data.elevation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.google.common.cache.Cache;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.geo.WebMercatorTileMath;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the actual single-land-provider contract, not a hypothetical selectable DEM API. */
class TellusCanonicalDemSelectionTest {
   private static final double SCALE = 30.0;
   private static final SphereWorldModel.GeoPoint POINT = new SphereWorldModel.GeoPoint(1.0, 1.0, 0.0);

   @TempDir
   Path gameDirectory;

   @BeforeAll
   static void bootstrapMinecraft() {
      assumeFalse(TellusCanonicalDemSelectionTest.class.getClassLoader()
         .getResource("net/minecraftforge/fml/ModList.class") != null,
         "Forge's raw JUnit bootstrap cannot initialize vanilla registries");
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @Test
   void allSelectionInputsAndUnknownProviderIdsRetainTheOnlySupportedLandProvider() {
      assertEquals(EarthGeneratorSettings.DemProvider.AUTO,
         EarthGeneratorSettings.DemProvider.fromId("not-a-real-dem"));
      assertEquals(List.of(EarthGeneratorSettings.DemProvider.TERRARIUM),
         EarthGeneratorSettings.DemSelection.userSelectableProviders());
      for (var selection : selections()) {
         assertEquals(EarthGeneratorSettings.DEFAULT.demSelection(), selection);
         assertFalse(selection.automatic());
         assertTrue(selection.terrainTilesEnabled());
         assertEquals(List.of("terrarium"), selection.enabledProviderIds());
         assertEquals("mapterhorn", selection.fingerprint());
      }
      assertEquals(EarthGeneratorSettings.DemSelection.fullUserSelectableMask(),
         EarthGeneratorSettings.DemSelection.maskFromProviderIds(List.of("not-a-real-dem")));
   }

   @Test
   void earthAndGeoPointSamplingKeepMapterhornLandAndOpenWatersOceanForEverySelectionInput() throws Exception {
      String previousGameDir = System.getProperty("tellus.gameDir");
      System.setProperty("tellus.gameDir", this.gameDirectory.toString());
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         TellusElevationSource source = new TellusElevationSource();
         seed(source, "cache", (short)125);
         seed(source, "oceanCache", (short)-340);
         for (var selection : selections()) {
            // Positive Mapterhorn remains land even when the coarse ocean mask says ocean.
            assertSelected(source, selection, true, 125.0, TellusElevationSource.DemUsage.TERRAIN_TILES);
         }
         seed(source, "cache", (short)-8);
         for (var selection : selections()) {
            assertSelected(source, selection, false, -8.0, TellusElevationSource.DemUsage.TERRAIN_TILES);
            assertSelected(source, selection, true, -340.0, TellusElevationSource.DemUsage.OPENWATERS);
         }
      } finally {
         restoreGameDir(previousGameDir);
      }
   }

   @Test
   void memoryMissStaysUnknownInBothCoordinateLanes() {
      String previousGameDir = System.getProperty("tellus.gameDir");
      System.setProperty("tellus.gameDir", this.gameDirectory.toString());
      try {
         TellusElevationSource source = new TellusElevationSource();
         var spherical = source.sampleResolvedPreviewElevationMetersMemoryOnly(POINT, SCALE, true, SCALE);
         assertTrue(Double.isNaN(spherical.elevationMeters()));
         assertFalse(spherical.mapterhornAvailable());
         for (var selection : selections()) {
            var earth = source.sampleResolvedPreviewElevationMetersMemoryOnly(
               EarthProjection.longitudeToBlockX(POINT.longitude(), SCALE),
               EarthProjection.latToBlockZ(POINT.latitude(), SCALE), SCALE, true, selection, SCALE);
            assertTrue(Double.isNaN(earth.elevationMeters()));
            assertFalse(earth.mapterhornAvailable());
         }
      } finally {
         restoreGameDir(previousGameDir);
      }
   }

   private static List<EarthGeneratorSettings.DemSelection> selections() {
      return List.of(
         EarthGeneratorSettings.DemSelection.automaticSelection(),
         EarthGeneratorSettings.DemSelection.manual(0),
         EarthGeneratorSettings.DemSelection.manual(-1),
         EarthGeneratorSettings.DemSelection.manual(List.of()),
         new EarthGeneratorSettings.DemSelection(true, 0),
         EarthGeneratorSettings.DemSelection.fromLegacyProvider(
            EarthGeneratorSettings.DemProvider.fromId("not-a-real-dem")));
   }

   private static void assertSelected(TellusElevationSource source,
      EarthGeneratorSettings.DemSelection selection, boolean ocean, double expected,
      TellusElevationSource.DemUsage provider) {
      var spherical = source.sampleResolvedPreviewElevationMeters(POINT, SCALE, ocean, selection, SCALE);
      var earth = source.sampleResolvedPreviewElevationMeters(
         EarthProjection.longitudeToBlockX(POINT.longitude(), SCALE),
         EarthProjection.latToBlockZ(POINT.latitude(), SCALE), SCALE, ocean, selection, SCALE);
      for (var sample : List.of(spherical, earth)) {
         assertEquals(expected, sample.elevationMeters(), 1.0E-9);
         assertEquals(provider, sample.primaryProvider());
         assertEquals(provider.bit(), sample.providerMask());
         assertTrue(sample.mapterhornAvailable());
      }
   }

   /** Seed existing provider caches; no loader, file download, or network fixture is involved. */
   @SuppressWarnings("unchecked")
   private static void seed(TellusElevationSource source, String cacheName, short height) throws Exception {
      Field field = TellusElevationSource.class.getDeclaredField(cacheName);
      field.setAccessible(true);
      Cache<Object, ShortRaster> cache = (Cache<Object, ShortRaster>)field.get(source);
      Class<?> keyClass = Class.forName(TellusElevationSource.class.getName() + "$TileKey");
      Constructor<?> key = keyClass.getDeclaredConstructor(int.class, int.class, int.class);
      key.setAccessible(true);
      int zoom = TellusElevationSource.selectPreviewZoom(SCALE, SCALE, 17);
      var coordinate = WebMercatorTileMath.project(POINT.longitude(), POINT.latitude(), zoom, 512);
      ShortRaster raster = ShortRaster.create(512, 512);
      raster.fill(height);
      // Include a halo to keep both interpolation implementations within the seeded fixture.
      for (int z = -1; z <= 1; z++) {
         for (int x = -1; x <= 1; x++) {
            cache.put(key.newInstance(zoom, coordinate.tileX() + x, coordinate.tileY() + z), raster);
         }
      }
   }

   private static void restoreGameDir(String previous) {
      if (previous == null) System.clearProperty("tellus.gameDir");
      else System.setProperty("tellus.gameDir", previous);
   }
}
