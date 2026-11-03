package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CubeNetInlandWaterResolverTest {
   @Test
   void reusesTellusLakePlannerThroughGeodeticBounds() {
      SphereWorldModel model = SphereWorldModel.forWorldScale(1000.0);
      OsmWaterFeature lake = new OsmWaterFeature(17L, false, false, OsmWaterKind.LAKE,
         new double[][]{{-0.1, 0.1, 0.1, -0.1, -0.1}},
         new double[][]{{-0.1, -0.1, 0.1, 0.1, -0.1}});
      AtomicReference<com.yucareux.tellus.world.data.geo.GeodeticBounds> queried = new AtomicReference<>();
      CubeNetInlandWaterResolver.GeodeticWaterInput source = new CubeNetInlandWaterResolver.GeodeticWaterInput() {
         @Override
         public TellusOsmWaterSource.WaterQueryResult waterForGeoAreaWithStatus(
            com.yucareux.tellus.world.data.geo.GeodeticBounds bounds, double scale, OsmQueryMode mode
         ) {
            queried.set(bounds);
            return new TellusOsmWaterSource.WaterQueryResult(List.of(lake),
               TellusOsmWaterSource.CoverageStatus.COMPLETE, 1);
         }

         @Override
         public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double scale) {
            return new TellusOsmWaterSource.WaterQueryResult(List.of(lake),
               TellusOsmWaterSource.CoverageStatus.COMPLETE, 1);
         }

         @Override
         public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double scale) {
            return new TellusOsmWaterSource.WaterQueryResult(List.of(),
               TellusOsmWaterSource.CoverageStatus.COMPLETE, 1);
         }
      };
      SphereChunkSampler terrain = new SphereChunkSampler(model,
         (point, resolution) -> new SphereChunkSampler.SurfaceData(-2000.0, 80));
      var resolver = new CubeNetInlandWaterResolver(model, 64, terrain, source);
      var point = new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0);
      var result = resolver.sample(point, new SphereChunkSampler.SurfaceData(-2000.0, 80));

      assertTrue(result.hasWater());
      assertFalse(result.ocean());
      assertEquals(-6000.0, result.terrainSurfaceMeters(), 0.001);
      assertEquals(-3000.0, result.waterSurfaceMeters(), 0.001);
      assertNotNull(queried.get());
      assertTrue(queried.get().contains(point));
      var applied = resolver.apply(point, new SphereChunkSampler.SurfaceData(-2000.0, 80));
      assertTrue(applied.inlandWater());
      assertEquals(-6000.0, applied.elevationMeters(), 0.001);
      assertEquals(-3000.0, applied.waterSurfaceMeters(), 0.001);
   }

   @Test
   void skipsNonWaterCoverWithoutTouchingHydrologyInput() {
      SphereWorldModel model = SphereWorldModel.forWorldScale(1000.0);
      AtomicReference<Boolean> called = new AtomicReference<>(false);
      CubeNetInlandWaterResolver.GeodeticWaterInput source = new CubeNetInlandWaterResolver.GeodeticWaterInput() {
         @Override
         public TellusOsmWaterSource.WaterQueryResult waterForGeoAreaWithStatus(
            com.yucareux.tellus.world.data.geo.GeodeticBounds bounds, double scale, OsmQueryMode mode
         ) {
            called.set(true);
            return new TellusOsmWaterSource.WaterQueryResult(List.of(),
               TellusOsmWaterSource.CoverageStatus.COMPLETE, 1);
         }

         @Override
         public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double scale) {
            return new TellusOsmWaterSource.WaterQueryResult(List.of(),
               TellusOsmWaterSource.CoverageStatus.COMPLETE, 1);
         }

         @Override
         public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double scale) {
            return new TellusOsmWaterSource.WaterQueryResult(List.of(),
               TellusOsmWaterSource.CoverageStatus.COMPLETE, 1);
         }
      };
      SphereChunkSampler terrain = new SphereChunkSampler(model,
         (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 30));
      var resolver = new CubeNetInlandWaterResolver(model, 64, terrain, source);
      var result = resolver.sample(new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0),
         new SphereChunkSampler.SurfaceData(10.0, 30));

      assertFalse(result.hasWater());
      assertFalse(called.get());
   }
}
