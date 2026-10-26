package com.yucareux.tellus.world.data.osm;

import com.google.common.cache.Cache;
import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic cached vector tiles; no download and no real-world quality claim. */
class GeodeticOsmQueryTest {
   private static final GeodeticBounds DATE_LINE = new GeodeticBounds(19.99, 179.99, 20.01, -179.99);
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
   void buildingsOnBothSidesAreReturnedWithOriginalGeometryAndMetadata() throws Exception {
      var source = new TellusOsmBuildingSource();
      prepare(source);
      var east = building(1, 179.998);
      var west = building(2, -179.999);
      put(source, 3, new OsmBuildingTile(List.of(east)));
      put(source, 0, new OsmBuildingTile(List.of(west)));
      var result = source.buildingsForGeoAreaWithStatus(DATE_LINE, OsmQueryMode.BLOCKING);
      assertEquals(List.of(east, west), result.features());
      assertFalse(result.hadCacheMiss());
      assertEquals(12.0, result.features().get(0).heightMeters());
      assertEquals(3, result.features().get(0).metadata().floorCount());
   }

   @Test
   void geodeticBuildingsKeepSourceFeaturesWhilePlanarQueriesUseReplacementLandmarks() throws Exception {
      double lon = 2.294498;
      double lat = 48.858261;
      var tower = new OsmBuildingFeature(OsmBuildingKind.FOOTPRINT, 101,
         "930b868b-1163-4f10-b7ee-d3e3bf82bf7a", false,
         new OsmBuildingMetadata("residential", null, null, null, 3, null, null, null, null, null),
         12.0, 0.0, new double[][]{{lon, lon + 0.00005, lon + 0.00005, lon, lon}},
         new double[][]{{lat, lat, lat + 0.00005, lat + 0.00005, lat}});
      assertTrue(com.yucareux.tellus.worldgen.landmark.LandmarkCatalog.suppressesBuilding(tower));
      var source = new TellusOsmBuildingSource();
      prepare(source);
      put(source, 2, new OsmBuildingTile(List.of(tower)));
      var geo = source.buildingsForGeoAreaWithStatus(
         new GeodeticBounds(lat - 0.001, lon - 0.001, lat + 0.001, lon + 0.001), OsmQueryMode.BLOCKING);
      assertEquals(List.of(tower), geo.features());
      int x = (int)Math.floor(lon * 111319.49166666667);
      int z = (int)Math.floor(com.yucareux.tellus.worldgen.EarthProjection.latToBlockZ(lat, 1.0));
      var planar = source.buildingsForAreaWithStatus(x - 64, z - 64, x + 64, z + 64, 1.0, 0, OsmQueryMode.BLOCKING);
      assertFalse(planar.hadCacheMiss());
      assertTrue(planar.features().isEmpty());
   }

   @Test
   void geodeticRoadsKeepBridgeGeometryWhilePlanarQueriesUseReplacementLandmarks() throws Exception {
      double lon = -122.4783550;
      double lat = 37.8200668;
      var bridge = new RoadFeature(102, RoadClass.NORMAL, RoadMode.BRIDGE, 1, "primary",
         new double[]{lon, lon}, new double[]{lat - 0.00005, lat + 0.00005});
      assertTrue(com.yucareux.tellus.worldgen.landmark.LandmarkCatalog.replacesRoad(bridge));
      var source = new TellusOsmRoadSource();
      prepare(source);
      put(source, 0, new OverpassRoadTile(List.of(bridge)));
      var geo = source.roadsForGeoAreaWithStatus(
         new GeodeticBounds(lat - 0.001, lon - 0.001, lat + 0.001, lon + 0.001), OsmQueryMode.BLOCKING);
      assertEquals(List.of(bridge), geo.features());
      int x = (int)Math.floor(lon * 111319.49166666667);
      int z = (int)Math.floor(com.yucareux.tellus.worldgen.EarthProjection.latToBlockZ(lat, 1.0));
      var planar = source.roadsForAreaWithStatus(x - 64, z - 64, x + 64, z + 64, 1.0, 0, OsmQueryMode.BLOCKING);
      assertFalse(planar.hadCacheMiss());
      assertTrue(planar.features().isEmpty());
   }

   @Test
   void roadsAndWaterUseBothDateLineWindows() throws Exception {
      var roads = new TellusOsmRoadSource();
      prepare(roads);
      var east = new RoadFeature(1, RoadClass.NORMAL, RoadMode.NORMAL, 0, "residential",
         new double[]{179.998, 179.999}, new double[]{20.0, 20.001});
      var west = new RoadFeature(2, RoadClass.NORMAL, RoadMode.NORMAL, 0, "residential",
         new double[]{-179.999, -179.998}, new double[]{20.0, 20.001});
      put(roads, 3, new OverpassRoadTile(List.of(east)));
      put(roads, 0, new OverpassRoadTile(List.of(west)));
      var roadResult = roads.roadsForGeoAreaWithStatus(DATE_LINE, OsmQueryMode.BLOCKING);
      assertEquals(List.of(east, west), roadResult.features());
      assertFalse(roadResult.hadCacheMiss());

      var water = new TellusOsmWaterSource();
      prepare(water);
      set(water, "minZoom", 2);
      set(water, "maxZoom", 2);
      var eastWater = new OsmWaterFeature(1, false, false, polygon(179.998), latitudePolygon());
      var westWater = new OsmWaterFeature(2, false, false, polygon(-179.999), latitudePolygon());
      put(water, 3, new OsmWaterTile(List.of(eastWater)));
      put(water, 0, new OsmWaterTile(List.of(westWater)));
      var waterResult = water.waterForGeoAreaWithStatus(DATE_LINE, 1.0, OsmQueryMode.BLOCKING);
      assertEquals(List.of(eastWater, westWater), waterResult.features());
      assertTrue(waterResult.complete());
   }

   @Test
   void unsupportedPolarCoverageIsNeverReportedComplete() throws Exception {
      var source = new TellusOsmWaterSource();
      prepare(source);
      var result = source.waterForGeoAreaWithStatus(new GeodeticBounds(89.0, -1.0, 90.0, 1.0), 1.0, OsmQueryMode.BLOCKING);
      assertTrue(result.features().isEmpty());
      assertFalse(result.complete());
   }

   private static OsmBuildingFeature building(long id, double lon) {
      return new OsmBuildingFeature(OsmBuildingKind.FOOTPRINT, id, "b" + id, false,
         new OsmBuildingMetadata("residential", null, null, null, 3, null, null, null, null, null),
         12.0, 0.0, polygon(lon), latitudePolygon());
   }

   private static double[][] polygon(double lon) {
      return new double[][]{{lon, lon + 0.0005, lon + 0.0005, lon, lon}};
   }

   private static double[][] latitudePolygon() {
      return new double[][]{{20.0, 20.0, 20.0005, 20.0005, 20.0}};
   }

   private static void prepare(Object source) throws Exception {
      set(source, "initialized", true);
      set(source, "available", true);
      if (!(source instanceof TellusOsmWaterSource)) set(source, "queryZoom", 2);
   }

   private static void set(Object target, String name, Object value) throws Exception {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
   }

   @SuppressWarnings("unchecked")
   private static void put(Object source, int tileX, Object tile) throws Exception {
      Class<?> key = Class.forName(source.getClass().getName() + "$TileKey");
      Constructor<?> constructor = key.getDeclaredConstructor(int.class, int.class, int.class);
      constructor.setAccessible(true);
      var field = source.getClass().getDeclaredField("cache");
      field.setAccessible(true);
      ((Cache<Object, Object>)field.get(source)).put(constructor.newInstance(2, tileX, 1), tile);
   }
}
