package com.yucareux.tellus.world.data.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.cache.Cache;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.GeomType;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Real bundled GLiM and parsed vector-tile fixtures in the native Macrostrat memory cache; no remote GIS. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("tellus-worldgen-sources")
class SphericalGeologySourceTest {
   private static final String GLOBAL_FLAG = "tellus.macrostrat.enabled";
   private static final String SPHERICAL_FLAG = "tellus.sphericalGeology.macrostrat";
   private static final String[] PROPERTIES = {
      GLOBAL_FLAG, SPHERICAL_FLAG, "tellus.gameDir", "tellus.configDir", "tellus.macrostrat.tileUrl"
   };
   private static final double SCALE = 30.0;
   private static final SphereWorldModel.GeoPoint YOSEMITE = new SphereWorldModel.GeoPoint(37.746, -119.533, 0.0);

   @TempDir Path gameDir;
   private final Map<String, String> previousProperties = new HashMap<>();

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @BeforeEach
   void isolateProperties() {
      for (String property : PROPERTIES) previousProperties.put(property, System.getProperty(property));
      System.clearProperty(GLOBAL_FLAG);
      System.clearProperty(SPHERICAL_FLAG);
      System.setProperty("tellus.gameDir", gameDir.toString());
      System.setProperty("tellus.configDir", gameDir.resolve("config").toString());
      System.setProperty("tellus.macrostrat.tileUrl", MacrostratGeologySource.DEFAULT_TILE_URL);
   }

   @AfterEach
   void restoreProperties() {
      previousProperties.forEach((property, value) -> {
         if (value == null) System.clearProperty(property);
         else System.setProperty(property, value);
      });
   }

   @Test
   void defaultFollowsEarthAndExplicitSphericalFalseRollsBack() {
      assertTrue(SphericalGeologySource.configuredMacrostratEnabled(), "unset flags follow Earth's enabled default");
      System.setProperty(SPHERICAL_FLAG, "false");
      assertFalse(SphericalGeologySource.configuredMacrostratEnabled());
      System.setProperty(SPHERICAL_FLAG, "TrUe");
      assertTrue(SphericalGeologySource.configuredMacrostratEnabled());
   }

   @Test
   void globalDisableWinsEvenOverExplicitSphericalTrue() {
      System.setProperty(GLOBAL_FLAG, "false");
      assertFalse(SphericalGeologySource.configuredMacrostratEnabled());
      System.setProperty(SPHERICAL_FLAG, "true");
      assertFalse(SphericalGeologySource.configuredMacrostratEnabled());
      System.setProperty(GLOBAL_FLAG, "true");
      assertTrue(SphericalGeologySource.configuredMacrostratEnabled());
      System.setProperty(SPHERICAL_FLAG, "false");
      assertFalse(SphericalGeologySource.configuredMacrostratEnabled());
   }

   @Test
   void defaultOverlayReadsTheActualNativeCacheByGeodeticLonLat() throws Exception {
      MacrostratGeologySource nativeSource = nativeSource();
      seed(nativeSource, YOSEMITE, limestoneTile());
      var source = configuredSource(nativeSource);
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         assertEquals(MapUnit.of(Rock.LIMESTONE),
            nativeSource.sampleUnitAtLonLat(YOSEMITE.longitude(), YOSEMITE.latitude(), SCALE, SCALE));
         var sample = source.sample(YOSEMITE, SCALE, SCALE);
         assertEquals(MapUnit.of(Rock.LIMESTONE), sample.mapUnit());
         assertEquals(Lithology.ACID_PLUTONIC, sample.lithology(), "bundled Yosemite GLiM remains intact below the overlay");
         assertTrue(sample.available());
         var wrapped = new SphereWorldModel.GeoPoint(YOSEMITE.latitude(), YOSEMITE.longitude() + 360.0, 0.0);
         assertEquals(sample, source.sample(wrapped, SCALE, SCALE), "longitude wrapping belongs to the native readers");
      }
   }

   @Test
   void explicitFalseKeepsBundledGlimEvenWithACachedOverlay() throws Exception {
      MacrostratGeologySource nativeSource = nativeSource();
      seed(nativeSource, YOSEMITE, limestoneTile());
      System.setProperty(SPHERICAL_FLAG, "false");
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         assertEquals(MapUnit.of(Rock.LIMESTONE),
            nativeSource.sampleUnitAtLonLat(YOSEMITE.longitude(), YOSEMITE.latitude(), SCALE, SCALE));
         var sample = configuredSource(nativeSource).sample(YOSEMITE, SCALE, SCALE);
         assertNull(sample.mapUnit());
         assertEquals(Lithology.ACID_PLUTONIC, sample.lithology());
         assertTrue(sample.available());
      }
   }

   @Test
   void globalDisableKeepsBundledGlimWithSphericalOptInAndCachedOverlay() throws Exception {
      MacrostratGeologySource nativeSource = nativeSource();
      seed(nativeSource, YOSEMITE, limestoneTile());
      System.setProperty(GLOBAL_FLAG, "false");
      System.setProperty(SPHERICAL_FLAG, "true");
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         var sample = configuredSource(nativeSource).sample(YOSEMITE, SCALE, SCALE);
         assertNull(sample.mapUnit());
         assertEquals(Lithology.ACID_PLUTONIC, sample.lithology());
      }
   }

   @Test
   void cacheOnlyMissKeepsGlimWithoutEnteringTheNativeNetworkRequestGate() throws Exception {
      MacrostratGeologySource nativeSource = nativeSource();
      assertEquals(Lithology.ACID_PLUTONIC, TellusLithologySource.shared().sampleAtLonLat(YOSEMITE.longitude(), YOSEMITE.latitude()),
         "warm the local GLiM raster before timing only the missing Macrostrat lookup");
      Field requestsField = MacrostratGeologySource.class.getDeclaredField("requests");
      requestsField.setAccessible(true);
      Semaphore requests = (Semaphore)requestsField.get(nativeSource);
      int permits = requests.drainPermits();
      try {
         // Enter cacheOnly inside the timeout worker: the policy is thread-local. A fetch would wait on the drained gate.
         assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
               var sample = configuredSource(nativeSource).sample(YOSEMITE, SCALE, SCALE);
               assertNull(sample.mapUnit());
               assertEquals(Lithology.ACID_PLUTONIC, sample.lithology());
               assertTrue(sample.available());
            }
         });
         assertFalse(Files.exists(gameDir.resolve("tellus/cache/geology/macrostrat-carto")), "cache miss writes no tile");
      } finally {
         requests.release(permits);
      }
   }

   @Test
   void emptyCoverageAndCoarseScaleKeepGlimRatherThanInventingMapUnits() throws Exception {
      MacrostratGeologySource nativeSource = nativeSource();
      seed(nativeSource, YOSEMITE, MacrostratTile.EMPTY);
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         var source = configuredSource(nativeSource);
         assertEquals(new SphericalGeologySource.Sample(Lithology.ACID_PLUTONIC, null), source.sample(YOSEMITE, SCALE, SCALE));
         assertEquals(new SphericalGeologySource.Sample(Lithology.ACID_PLUTONIC, null), source.sample(YOSEMITE, 5000.0, 5000.0));
         var ocean = source.sample(new SphereWorldModel.GeoPoint(0.0, -150.0, 0.0), SCALE, SCALE);
         assertEquals(Lithology.UNDEFINED, ocean.lithology(), "unmapped GLiM is still undefined, not a fabricated rock");
         assertNull(ocean.mapUnit());
         assertFalse(ocean.available());
      }
   }

   @Test
   void invalidScaleOrResolutionStaysUnknownEvenWithACachedOverlay() throws Exception {
      MacrostratGeologySource nativeSource = nativeSource();
      seed(nativeSource, YOSEMITE, limestoneTile());
      var source = configuredSource(nativeSource);
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         for (double invalid : new double[]{0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertUnknown(source.sample(YOSEMITE, invalid, SCALE));
            assertUnknown(source.sample(YOSEMITE, SCALE, invalid));
         }
      }
   }

   private static void assertUnknown(SphericalGeologySource.Sample sample) {
      assertEquals(Lithology.UNKNOWN, sample.lithology());
      assertNull(sample.mapUnit());
      assertFalse(sample.available());
   }

   private static SphericalGeologySource configuredSource(MacrostratGeologySource nativeSource) {
      return new SphericalGeologySource(TellusLithologySource.shared(), nativeSource,
         SphericalGeologySource.configuredMacrostratEnabled());
   }

   private static MacrostratGeologySource nativeSource() throws Exception {
      Constructor<MacrostratGeologySource> constructor = MacrostratGeologySource.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
   }

   @SuppressWarnings("unchecked")
   private static void seed(MacrostratGeologySource source, SphereWorldModel.GeoPoint point, MacrostratTile tile) throws Exception {
      int zoom = MacrostratGeologySource.zoomFor(SCALE, SCALE);
      double tiles = 1 << zoom;
      int x = (int)Math.floor((point.longitude() + 180.0) / 360.0 * tiles);
      double latitude = Math.toRadians(point.latitude());
      int y = (int)Math.floor((1.0 - Math.log(Math.tan(latitude) + 1.0 / Math.cos(latitude)) / Math.PI) / 2.0 * tiles);
      Class<?> key = Class.forName(MacrostratGeologySource.class.getName() + "$TileKey");
      Constructor<?> constructor = key.getDeclaredConstructor(int.class, int.class, int.class);
      constructor.setAccessible(true);
      Field cacheField = MacrostratGeologySource.class.getDeclaredField("cache");
      cacheField.setAccessible(true);
      ((Cache<Object, MacrostratTile>)cacheField.get(source)).put(constructor.newInstance(zoom, x, y), tile);
   }

   /** Synthetic MVT polygon, parsed/rasterized/classified by the production reader, not claimed as a downloaded GIS tile. */
   private static MacrostratTile limestoneTile() throws Exception {
      Layer.Builder layer = Layer.newBuilder().setVersion(2).setName("units").setExtent(4096)
         .addKeys("source_id").addKeys("legend_id").addKeys("name")
         .addValues(Value.newBuilder().setIntValue(34))
         .addValues(Value.newBuilder().setIntValue(1))
         .addValues(Value.newBuilder().setStringValue("Limestone"));
      layer.addFeatures(Feature.newBuilder().setType(GeomType.POLYGON)
         .addTags(0).addTags(0).addTags(1).addTags(1).addTags(2).addTags(2)
         .addGeometry(9).addGeometry(0).addGeometry(0)
         .addGeometry(26).addGeometry(8192).addGeometry(0)
         .addGeometry(0).addGeometry(8192).addGeometry(8191).addGeometry(0).addGeometry(15));
      return MacrostratTile.parse(Tile.newBuilder().addLayers(layer).build().toByteArray(), MacrostratGeologySource.GRID_SIZE, source -> false);
   }
}
