package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubeNetLodSurfaceTest {
   private static Holder<Biome> plains;
   @BeforeAll static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      plains = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
   }

   private static CubeNetChunkGenerator generator(SphereChunkSampler.PointSampler sampler) {
      return new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1000, 64, 0, 256, sampler, 7);
   }

   @Test void seamColumnsExistOnlyWithinTheConfiguredNativeApron() {
      var generator = generator((point, resolution) -> new SphereChunkSampler.SurfaceData(
         Math.floor(point.latitude() + 90) * 1000.0, 30));
      var layout = generator.adapter().layout();
      int count = 0;
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(face, side)) continue;
         double edge = layout.faceSize() / 2.0 + 0.5, depth = 64.5;
         double u = switch (side) { case WEST -> -depth; case EAST -> layout.faceSize() + depth; default -> edge; };
         double v = switch (side) { case NORTH -> -depth; case SOUTH -> layout.faceSize() + depth; default -> edge; };
         var source = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(face, u, v));
         long x = (long)Math.floor(source.x()), z = (long)Math.floor(source.z());
         assertTrue(generator.apronResolver().resolve(source.x(), source.z()).isPresent(), face + " " + side);
         var target = layout.mapAcross(face, side, edge, depth);
         var canonical = generator.adapter().planarCenter(new SphereWorldModel.SphereBlockAddress(
            target.face().index(), (long)Math.floor(target.u()), (long)Math.floor(target.v())));
         var actual = generator.sampleLodColumn(x, z, 1).orElseThrow();
         assertEquals(generator.sampleLodColumn((long)Math.floor(canonical.x()),
            (long)Math.floor(canonical.z()), 1).orElseThrow(), actual, face + " " + side);
         assertTrue(actual.stream().anyMatch(layer -> !layer.state().isAir()));
         count++;
      }
      assertEquals(14, count);

      // The same cut farther than the seven-chunk native apron is not a
      // second remote face request.
      var face = CubeNetLayout.Face.POS_X;
      var side = CubeNetLayout.Side.WEST;
      double edge = layout.faceSize() / 2.0 + 0.5, depth = 512.5;
      var outside = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(face,
         -depth, edge));
      assertTrue(generator.sampleLodColumn((long)Math.floor(outside.x()),
         (long)Math.floor(outside.z()), 1).isEmpty(), "outside " + outside);
   }

   @Test void nativeAndLodColumnsHaveExactlyTheSameBlocksIncludingWaterAndBedrock() {
      for (int cover : new int[]{10, 50, 60, 70, 80, 90}) {
         var generator = generator((point, resolution) -> new SphereChunkSampler.SurfaceData(-10000, cover, 0));
         for (var face : CubeNetLayout.Face.values()) {
            var layout = generator.adapter().layout();
            var p = layout.toPlanar(new CubeNetLayout.FacePoint(face, layout.faceSize() / 2.0 + 0.5,
               layout.faceSize() / 2.0 + 0.5));
            int x = (int)Math.floor(p.x()), z = (int)Math.floor(p.z());
            var expected = generator.getBaseColumn(x, z, LevelHeightAccessor.create(0, 256), null);
            var layers = generator.sampleLodColumn(x, z, 1).orElseThrow();
            int next = 0;
            for (var layer : layers) {
               assertEquals(next, layer.bottom());
               for (int y = layer.bottom(); y < layer.top(); y++) assertEquals(expected.getBlock(y), layer.state());
               next = layer.top();
            }
            assertEquals(256, next);
            assertEquals(Blocks.BEDROCK.defaultBlockState(), layers.get(0).state());
         }
      }
   }

   @Test void coarseLodSamplesOnePointAndStillMatchesTheFineTerrain() {
      AtomicInteger calls = new AtomicInteger();
      var lastResolution = new java.util.concurrent.atomic.AtomicReference<Double>();
      var generator = generator((point, resolution) -> {
         calls.incrementAndGet();
         lastResolution.set(resolution);
         return new SphereChunkSampler.SurfaceData(-10000, 80, 0);
      });
      List<CubeNetLodSurface.Layer> coarse = generator.sampleLodColumn(15360, -5120, 16).orElseThrow();
      assertEquals(1, calls.get());
      assertEquals(16000.0, lastResolution.get());
      assertEquals(coarse, generator.sampleLodColumn(15368, -5112, 1).orElseThrow());
      assertEquals(257, calls.get());
      assertEquals(1000.0, lastResolution.get());
   }

   @Test void missingElevationAndOutOfRangeDataFailInsteadOfBecomingCachedAir() {
      assertThrows(IllegalStateException.class, () -> generator((point, resolution) ->
         new SphereChunkSampler.SurfaceData(Double.NaN, 80)).sampleLodColumn(15360, -5120, 16));
      assertThrows(IllegalStateException.class, () -> generator((point, resolution) ->
         new SphereChunkSampler.SurfaceData(500000, 30)).sampleLodColumn(15360, -5120, 16));
   }

   @Test void coarseCellCrossingCutIsQuarantinedWithoutSampling() {
      AtomicInteger calls = new AtomicInteger();
      var generator = generator((point, resolution) -> {
         calls.incrementAndGet();
         return new SphereChunkSampler.SurfaceData(0, 30);
      });
      assertTrue(generator.sampleLodColumn(15360, -8, 16).isEmpty());
      assertEquals(0, calls.get());
      assertTrue(generator.sampleLodColumn(1000000, 1000000, 16).isEmpty());
      assertEquals(0, calls.get());
   }
}
