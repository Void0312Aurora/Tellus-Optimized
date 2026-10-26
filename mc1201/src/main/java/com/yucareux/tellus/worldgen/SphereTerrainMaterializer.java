package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import java.util.EnumSet;
import java.util.Objects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Radial column storage for spherical terrain, before chart-aware rendering and movement. */
public final class SphereTerrainMaterializer {
   private final double worldScale;
   private final int seaLevel;

   public SphereTerrainMaterializer(double worldScale, int seaLevel) {
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) throw new IllegalArgumentException("worldScale");
      this.worldScale = worldScale;
      this.seaLevel = seaLevel;
   }

   /** Preflight every column before writing so missing/out-of-range data cannot leave half a chunk. */
   public FillResult fill(ChunkAccess chunk, SphereChunkSampler.TerrainGrid terrain) {
      Objects.requireNonNull(chunk, "chunk");
      Objects.requireNonNull(terrain, "terrain");
      int minY = chunk.getMinBuildHeight();
      int maxY = chunk.getMaxBuildHeight();
      for (var section : chunk.getSections()) {
         if (!section.hasOnlyAir()) throw new IllegalArgumentException("spherical fill requires a fresh terrain chunk");
      }
      ColumnPlan[] plans = new ColumnPlan[256];
      int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE, wet = 0;
      for (int i = 0; i < plans.length; i++) {
         ColumnPlan plan = this.plan(terrain.columns().get(i).data(), minY, maxY);
         plans[i] = plan;
         lowest = Math.min(lowest, plan.surfaceY());
         highest = Math.max(highest, plan.topY());
         if (plan.waterY() > plan.surfaceY()) wet++;
      }
      int dirty;
      var writer = new EarthChunkGenerator.ChunkSectionWriter(chunk.getSections(), minY);
      try {
         for (int i = 0; i < plans.length; i++) {
            int x = i % 16, z = i / 16;
            ColumnPlan plan = plans[i];
            writer.fillColumnConstant(x, z, minY, plan.surfaceY(), Blocks.STONE.defaultBlockState());
            writer.fillSurfaceColumn(x, z, plan.surfaceY(), Math.max(minY + 1, plan.surfaceY() - 3),
               plan.top(), plan.filler());
            writer.setBlock(x, z, minY, Blocks.BEDROCK.defaultBlockState());
            writer.fillColumnConstant(x, z, plan.surfaceY() + 1, plan.waterY(), Blocks.WATER.defaultBlockState());
         }
         dirty = writer.finish().dirtySectionCount();
      } finally {
         writer.close();
      }
      // Direct section writes bypass ProtoChunk's per-block heightmap updates.
      Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
      chunk.setUnsaved(true);
      return new FillResult(lowest, highest, wet, dirty);
   }

   ColumnPlan plan(SphereChunkSampler.SurfaceData data, int minY, int maxY) {
      Objects.requireNonNull(data, "data");
      if (!data.elevationAvailable()) throw new IllegalStateException("spherical terrain elevation is unavailable");
      int surfaceY = this.height(data.elevationMeters());
      int waterY = data.waterAvailable() ? this.height(data.waterSurfaceMeters()) : surfaceY;
      if (surfaceY < minY + 1 || surfaceY >= maxY || waterY >= maxY) {
         throw new IllegalStateException("spherical terrain needs vertical range [" + surfaceY + ", " + waterY
            + "]; chunk supports [" + (minY + 1) + ", " + (maxY - 1) + "]");
      }
      Palette palette = palette(data.coverClass());
      return new ColumnPlan(surfaceY, waterY, palette.top(), palette.filler());
   }

   /** No Mercator sec(latitude) correction: these are radial physical metres. */
   public int height(double elevationMeters) {
      if (!Double.isFinite(elevationMeters)) throw new IllegalArgumentException("elevation must be finite");
      double blocks = elevationMeters / this.worldScale;
      double height = this.seaLevel + (elevationMeters >= 0.0 ? Math.ceil(blocks) : Math.floor(blocks));
      if (height < Integer.MIN_VALUE || height > Integer.MAX_VALUE || !Double.isFinite(height)) {
         throw new IllegalArgumentException("elevation exceeds supported block heights");
      }
      return (int)height;
   }

   static Palette palette(int coverClass) {
      return switch (coverClass) {
         case 10, 20, 30, 40, 100 -> new Palette(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
         case 60, 80 -> new Palette(Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState());
         case 70 -> new Palette(Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.SNOW_BLOCK.defaultBlockState());
         case 90, 95 -> new Palette(Blocks.MUD.defaultBlockState(), Blocks.MUD.defaultBlockState());
         default -> new Palette(Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
      };
   }

   record Palette(BlockState top, BlockState filler) { }
   record ColumnPlan(int surfaceY, int waterY, BlockState top, BlockState filler) {
      int topY() { return Math.max(this.surfaceY, this.waterY); }
      BlockState stateAt(int y, int minY) {
         if (y < minY || y > this.topY()) return Blocks.AIR.defaultBlockState();
         if (y == minY) return Blocks.BEDROCK.defaultBlockState();
         if (y > this.surfaceY) return Blocks.WATER.defaultBlockState();
         if (y == this.surfaceY) return this.top;
         if (y >= this.surfaceY - 3) return this.filler;
         return Blocks.STONE.defaultBlockState();
      }
   }
   public record FillResult(int minimumSurfaceY, int maximumOccupiedY, int waterColumns, int dirtySections) { }
}
