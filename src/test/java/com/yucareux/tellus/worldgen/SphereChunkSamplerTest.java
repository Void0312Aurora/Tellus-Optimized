package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SphereChunkSamplerTest {
   private final SphereWorldModel model = SphereWorldModel.forWorldScale(1.0);

   @Test
   void samplesOnlyOneRequestedChunkInFiveGeographicRegions() {
      double[][] regions = {{46.5775, 8.0058}, {48.8584, 2.2945}, {37.8199, -122.4783}, {24.0, 13.0}, {89.99, 179.99}};
      for (double[] region : regions) {
         AtomicInteger calls = new AtomicInteger();
         var sampler = new SphereChunkSampler(this.model, (point, resolution) -> {
            calls.incrementAndGet();
            assertEquals(1.0, resolution);
            // Synthetic analytic field: verifies coordinate/data wiring, not real scenery quality.
            return new SphereChunkSampler.SurfaceData(point.latitude() * 10.0 + point.longitude(), 30);
         });
         var address = this.model.chunkForGeo(new SphereWorldModel.GeoPoint(region[0], region[1], 0.0));
         assertEquals(0, calls.get());
         var grid = sampler.sampleTerrain(address);
         assertEquals(256, calls.get());
         for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
               var column = grid.column(x, z);
               var exact = this.model.exactSurfacePoint(address.face(), address.blockX() + x + 0.5, address.blockZ() + z + 0.5);
               assertTrue(SphereWorldModel.greatCircleDistanceMeters(column.point(), exact) < 0.001);
               assertEquals(column.point().latitude() * 10.0 + column.point().longitude(), column.data().elevationMeters());
            }
         }
      }
   }

   @Test
   void queryEnvelopeCoversAllCornersAtFaceEdgesAndPoles() {
      var sampler = new SphereChunkSampler(this.model, (point, resolution) -> new SphereChunkSampler.SurfaceData(Double.NaN, 0));
      long last = this.model.chunksPerFace() - 1;
      for (int face = 0; face < 6; face++) {
         for (long cx : new long[]{0, last / 2, last}) {
            for (long cz : new long[]{0, last / 2, last}) {
               var chunk = new SphereWorldModel.SphereChunkAddress(face, cx, cz);
               var bounds = sampler.featureBounds(chunk, 0.0);
               for (int x : new int[]{0, 16}) {
                  for (int z : new int[]{0, 16}) {
                     assertTrue(bounds.contains(this.model.exactSurfacePoint(face, chunk.blockX() + x, chunk.blockZ() + z)));
                  }
               }
            }
         }
      }
   }

   @Test
   void unavailableDataIsKeptAsMissingInsteadOfInventingSeaLevel() {
      var sampler = new SphereChunkSampler(this.model, (point, resolution) -> new SphereChunkSampler.SurfaceData(Double.NaN, 0));
      var grid = sampler.sampleTerrain(new SphereWorldModel.SphereChunkAddress(0, 0, 0));
      assertFalse(grid.column(0, 0).data().elevationAvailable());
      assertTrue(Double.isNaN(grid.column(0, 0).data().elevationMeters()));
   }
}
