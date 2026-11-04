package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubeNetSurfacePolicyTest {
   private static final SphereWorldModel.GeoPoint POINT = new SphereWorldModel.GeoPoint(46.0, 12.0, 0.0);

   @Test
   void missingSlopeKeepsTheExistingCoverPalette() {
      var decision = CubeNetSurfacePolicy.resolve(POINT,
         new SphereChunkSampler.SurfaceData(20.0, MountainSurfaceRules.ESA_GRASSLAND), 10.0, 7L);
      assertEquals(CubeNetSurfacePolicy.Material.GRASS, decision.top());
      assertEquals(3, decision.depth());
   }

   @Test
   void steepCanonicalSlopeReusesTheBareRockRule() {
      var gradient = new TerrainSlopePolicy.Gradient(2000.0, 65.0, 0.0, 1.0);
      var data = new SphereChunkSampler.SurfaceData(2000.0, MountainSurfaceRules.ESA_GRASSLAND,
         Double.NaN, SphereChunkSampler.SurfaceData.WaterKind.NONE, null,
         new TerrainSlopePolicy.Neighbourhood(gradient, 0.5));
      var decision = CubeNetSurfacePolicy.resolve(POINT, data, 10.0, 7L);
      assertEquals(CubeNetSurfacePolicy.Material.STONE, decision.top());
      assertTrue(decision.depth() >= 12);
   }

   @Test
   void snowUsesTheSharedVariableSnowDepth() {
      var gradient = new TerrainSlopePolicy.Gradient(2500.0, 0.0, 0.0, 0.0);
      var data = new SphereChunkSampler.SurfaceData(2500.0, MountainSurfaceRules.ESA_SNOW_ICE,
         Double.NaN, SphereChunkSampler.SurfaceData.WaterKind.NONE, null,
         new TerrainSlopePolicy.Neighbourhood(gradient, 0.0));
      var decision = CubeNetSurfacePolicy.resolve(POINT, data, 10.0, 7L);
      assertTrue(decision.top() == CubeNetSurfacePolicy.Material.SNOW
         || decision.top() == CubeNetSurfacePolicy.Material.POWDER_SNOW);
      assertTrue(decision.depth() >= SurfaceMaterialDepthPolicy.MIN_FULL_SNOW_DEPTH);
   }

   @Test
   void canonicalNoiseCoordinatesDoNotJumpAtTheDateLine() {
      var first = new SphereWorldModel.GeoPoint(20.0, 179.999, 0.0);
      var second = new SphereWorldModel.GeoPoint(20.0, -180.001, 0.0);
      var firstData = new SphereChunkSampler.SurfaceData(2000.0, MountainSurfaceRules.ESA_SNOW_ICE,
         Double.NaN, SphereChunkSampler.SurfaceData.WaterKind.NONE, null,
         new TerrainSlopePolicy.Neighbourhood(
            new TerrainSlopePolicy.Gradient(2000.0, 0.0, 0.0, 0.0), 0.0));
      var secondData = new SphereChunkSampler.SurfaceData(2000.0, MountainSurfaceRules.ESA_SNOW_ICE,
         Double.NaN, SphereChunkSampler.SurfaceData.WaterKind.NONE, null,
         new TerrainSlopePolicy.Neighbourhood(
            new TerrainSlopePolicy.Gradient(2000.0, 0.0, 0.0, 0.0), 0.0));
      assertEquals(CubeNetSurfacePolicy.resolve(first, firstData, 10.0, 7L),
         CubeNetSurfacePolicy.resolve(second, secondData, 10.0, 7L));
   }
}
