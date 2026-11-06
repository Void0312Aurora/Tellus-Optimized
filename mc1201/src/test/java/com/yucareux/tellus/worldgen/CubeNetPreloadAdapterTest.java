package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.preload.TerrainPreloadPackage;
import com.yucareux.tellus.preload.TerrainPreloadView;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubeNetPreloadAdapterTest {
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1000.0);
   private static final SphereChunkSampler.SurfaceData FALLBACK = new SphereChunkSampler.SurfaceData(42.0, 30);

   @Test
   void mapsGeodeticPointAndUsesPreloadHitBeforeFallback() {
      TerrainPreloadView view = viewReturning(70, 0, 0);
      AtomicInteger fallbackCalls = new AtomicInteger();
      var sample = CubeNetPreloadAdapter.sample(view, new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0),
         1000.0, 64, 1000.0, () -> {
            fallbackCalls.incrementAndGet();
            return FALLBACK;
         });

      assertEquals(6000.0, sample.elevationMeters());
      assertEquals(30, sample.coverClass());
      assertEquals(0, fallbackCalls.get());
      assertEquals(new CubeNetPreloadAdapter.PlanarPoint(0, 0),
         CubeNetPreloadAdapter.earthBlock(new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0), 1000.0).orElseThrow());
   }

   @Test
   void fallsBackOnPreloadMiss() {
      TerrainPreloadView view = viewReturning(70, 100, 100);
      AtomicInteger fallbackCalls = new AtomicInteger();
      var sample = CubeNetPreloadAdapter.sample(view, new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0),
         1000.0, 64, 1000.0, () -> {
            fallbackCalls.incrementAndGet();
            return FALLBACK;
         });

      assertSame(FALLBACK, sample);
      assertEquals(1, fallbackCalls.get());
   }

   @Test
   void leavesMercatorPolarBoundaryOutsidePreloadCoverage() {
      TerrainPreloadView view = viewReturning(70, 0, 0);
      AtomicInteger fallbackCalls = new AtomicInteger();
      var sample = CubeNetPreloadAdapter.sample(view, new SphereWorldModel.GeoPoint(90.0, 0.0, 0.0),
         MODEL.config().worldScale(), 64, 1000.0, () -> {
            fallbackCalls.incrementAndGet();
            return FALLBACK;
         });

      assertSame(FALLBACK, sample);
      assertEquals(1, fallbackCalls.get());
      assertTrue(CubeNetPreloadAdapter.earthBlock(new SphereWorldModel.GeoPoint(90.0, 0.0, 0.0),
         MODEL.config().worldScale()).isEmpty());
   }

   private static TerrainPreloadView viewReturning(int height, int expectedX, int expectedZ) {
      return new TerrainPreloadView() {
         @Override
         public TerrainPreloadPackage.Sample sample(int blockX, int blockZ) {
            return this.sample(blockX, blockZ, Double.POSITIVE_INFINITY);
         }

         @Override
         public TerrainPreloadPackage.Sample sample(int blockX, int blockZ, double resolutionMeters) {
            return blockX == expectedX && blockZ == expectedZ
               ? new TerrainPreloadPackage.Sample(height, 30, true, true, false, false, false)
               : null;
         }

         @Override
         public boolean contains(int blockX, int blockZ, double resolutionMeters) {
            return blockX == expectedX && blockZ == expectedZ;
         }
      };
   }
}
