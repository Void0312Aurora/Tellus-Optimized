package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CubeNetOceanSurfaceResolverTest {
   @Test
   void appliesOpenWatersFloorToAnOceanColumn() {
      AtomicReference<Double> resolution = new AtomicReference<>();
      var resolver = new CubeNetOceanSurfaceResolver(1000.0, (point, scale, requested) -> {
         resolution.set(requested);
         return -5000.0;
      });
      var input = new SphereChunkSampler.SurfaceData(
         -100.0, 80, 0.0, SphereChunkSampler.SurfaceData.WaterKind.OCEAN
      );
      var result = resolver.apply(new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0), input, 4000.0);
      assertEquals(-5000.0, result.elevationMeters());
      assertEquals(0.0, result.waterSurfaceMeters());
      assertEquals(4000.0, resolution.get());
   }

   @Test
   void missingBathymetryKeepsTheExistingFloorAndDoesNotInventOceanWater() {
      var resolver = new CubeNetOceanSurfaceResolver(1000.0, (point, scale, resolution) -> Double.NaN);
      var ocean = new SphereChunkSampler.SurfaceData(-100.0, 80, 0.0, SphereChunkSampler.SurfaceData.WaterKind.OCEAN);
      var unchangedOcean = resolver.apply(new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0), ocean, 1000.0);
      assertEquals(-100.0, unchangedOcean.elevationMeters());

      var land = new SphereChunkSampler.SurfaceData(20.0, 30);
      assertSame(land, resolver.apply(new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0), land, 1000.0));
   }
}
