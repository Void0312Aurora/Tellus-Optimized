package com.yucareux.tellus.worldgen.caves;

import com.google.common.base.Preconditions;
import com.yucareux.tellus.worldgen.GeologicalStonePlacementPolicy;
import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import com.yucareux.tellus.worldgen.decision.TellusEarthUndergroundDecisionKernel;
import com.yucareux.tellus.worldgen.decision.TellusEarthUndergroundDecisionKernel.Decision;
import com.yucareux.tellus.worldgen.decision.TellusEarthUndergroundDecisionKernel.Sample;
import java.util.List;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Samples the real vanilla Overworld density router into a temporary standard
 * height field, then projects its caves into Tellus terrain by depth below the
 * local surface. The temporary field never writes vanilla terrain into the
 * target chunk.
 */
public final class TellusVanillaNoiseCaveSampler {
   private static final int CHUNK_SIDE = 16;
   private static final int CHUNK_AREA = CHUNK_SIDE * CHUNK_SIDE;
   private static final int SURFACE_COVER_DEPTH = 4;
   private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
   private static final BlockState WATER = Blocks.WATER.defaultBlockState();
   private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

   private final VanillaNoiseFieldSampler fieldSampler;
   private volatile SeededRandomState cachedRandomState;

   public TellusVanillaNoiseCaveSampler(NoiseGeneratorSettings vanillaSettings) {
      this.fieldSampler = new VanillaNoiseFieldSampler(vanillaSettings);
   }

   public void apply(
      RegistryAccess registryAccess,
      long worldSeed,
      ChunkAccess chunk,
      int chunkMinY,
      int tellusSeaLevel,
      boolean applyCaves,
      boolean cavesReachSurface,
      boolean applyOreVeins,
      boolean applyGeologicalStonePatches,
      int[] surfaceYByColumn,
      IntBinaryOperator surfaceHeightSampler,
      int[] floodGuardYByColumn,
      int[] generationFloorYByColumn,
      List<UndergroundStructureExclusion.Box> structureExclusions,
      CaveBlockWriter blockWriter
   ) {
      Preconditions.checkArgument(
         applyCaves || applyOreVeins || applyGeologicalStonePatches,
         "At least one underground noise feature must be enabled"
      );
      Preconditions.checkArgument(surfaceYByColumn.length == CHUNK_AREA, "Tellus cave surface array must contain 256 columns");
      if (applyGeologicalStonePatches) {
         Objects.requireNonNull(surfaceHeightSampler, "surfaceHeightSampler");
      }
       RandomState randomState = this.randomStateFor(registryAccess, worldSeed);
       VanillaField field = this.fieldSampler.sample(registryAccess, randomState, chunk.getPos());
       applyField(field, chunk, chunkMinY, tellusSeaLevel, applyCaves, cavesReachSurface,
          applyOreVeins, applyGeologicalStonePatches, surfaceYByColumn, surfaceHeightSampler,
          floodGuardYByColumn, generationFloorYByColumn, structureExclusions, blockWriter);
    }

    // The production call above and whole-chunk regression use the same resolved
    // native field. Sampling, iteration order, depth mapping and writer effects stay native.
    static void applyField(VanillaField field, ChunkAccess chunk, int chunkMinY, int tellusSeaLevel,
       boolean applyCaves, boolean cavesReachSurface, boolean applyOreVeins,
       boolean applyGeologicalStonePatches, int[] surfaceYByColumn,
       IntBinaryOperator surfaceHeightSampler, int[] floodGuardYByColumn,
       int[] generationFloorYByColumn, List<UndergroundStructureExclusion.Box> structureExclusions,
       CaveBlockWriter blockWriter) {
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      ChunkPos chunkPos = chunk.getPos();
      int chunkMinX = chunkPos.getMinBlockX();
      int chunkMinZ = chunkPos.getMinBlockZ();
      int[] minimumGeologySurfaceYByColumn = applyGeologicalStonePatches
         ? minimumNearbySurfaceYByColumn(
            surfaceHeightSampler,
            chunkMinX,
            chunkMinZ,
            GeologicalStonePlacementPolicy.NOISE_SURFACE_SAMPLE_RADIUS
         )
         : null;
      boolean exposeSurfaceEntrances = applyCaves && cavesReachSurface;

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int columnIndex = localZ * CHUNK_SIDE + localX;
            int actualSurfaceY = surfaceYByColumn[columnIndex];
            int virtualSurfaceY = field.surfaceReferenceY(localX, localZ, exposeSurfaceEntrances);
            if (virtualSurfaceY <= field.minY()) {
               continue;
            }

            int actualBottomY = chunkMinY + 1;
            if (generationFloorYByColumn != null) {
               actualBottomY = Math.max(actualBottomY, generationFloorYByColumn[columnIndex] + 1);
            }
            int firstCarveY = exposeSurfaceEntrances ? actualSurfaceY : actualSurfaceY - SURFACE_COVER_DEPTH - 1;
            if (firstCarveY < actualBottomY) {
               continue;
            }

            int worldX = chunkMinX + localX;
            int worldZ = chunkMinZ + localZ;
            int minimumNearbySurfaceY = minimumGeologySurfaceYByColumn != null
               ? minimumGeologySurfaceYByColumn[columnIndex]
               : Integer.MAX_VALUE;
            for (int actualY = firstCarveY; actualY >= actualBottomY; actualY--) {
               boolean carvingBlocked =
                  UndergroundStructureExclusion.blocksCarving(structureExclusions, worldX, actualY, worldZ);
               int virtualY = TellusCaveDepthMapper.virtualYForActualY(
                  actualY, actualSurfaceY, actualBottomY, virtualSurfaceY, field.minY()
               );
               BlockState vanillaState = field.state(localX, virtualY, localZ);
                Decision decision = TellusEarthUndergroundDecisionKernel.resolve(classify(vanillaState),
                   actualY, tellusSeaLevel, applyCaves, carvingBlocked,
                   floodGuardYByColumn != null && actualY >= floodGuardYByColumn[columnIndex],
                   applyOreVeins, applyGeologicalStonePatches, minimumNearbySurfaceY);
                BlockState replacement = materialize(decision, vanillaState);
               if (replacement == null) {
                  continue;
               }

               cursor.set(worldX, actualY, worldZ);
               BlockState current = chunk.getBlockState(cursor);
                boolean replaceable = decision.noiseFeature()
                  ? current.is(BlockTags.BASE_STONE_OVERWORLD)
                  : VanillaNoiseFieldSampler.isCarvable(current);
               if (!replaceable) {
                  continue;
               }

               blockWriter.set(chunk, cursor, replacement, !replacement.getFluidState().isEmpty());
            }
         }
      }
   }

   RandomState randomStateFor(RegistryAccess registryAccess, long worldSeed) {
      SeededRandomState cached = this.cachedRandomState;
      if (cached != null && cached.seed() == worldSeed) {
         return cached.state();
      }

      synchronized (this) {
         cached = this.cachedRandomState;
         if (cached == null || cached.seed() != worldSeed) {
            RandomState state = this.fieldSampler.createRandomState(registryAccess, worldSeed);
            cached = new SeededRandomState(worldSeed, state);
            this.cachedRandomState = cached;
         }
         return cached.state();
      }
   }

    static Sample classify(BlockState state) {
       if (state.isAir()) return Sample.AIR;
       if (state.is(Blocks.LAVA)) return Sample.LAVA;
       if (!state.getFluidState().isEmpty()) return Sample.FLUID;
       if (state.is(Blocks.COPPER_ORE) || state.is(Blocks.RAW_COPPER_BLOCK)
          || state.is(Blocks.DEEPSLATE_IRON_ORE) || state.is(Blocks.RAW_IRON_BLOCK)) return Sample.ORE_VEIN;
       if (state.is(Blocks.GRANITE) || state.is(Blocks.TUFF)) return Sample.GEOLOGICAL_STONE;
       return Sample.SOLID;
    }

    static BlockState materialize(Decision decision, BlockState sampled) {
       return switch (decision) {
          case NONE -> null;
          case CAVE_AIR -> CAVE_AIR;
          case WATER -> WATER;
          case LAVA -> LAVA;
          case SAMPLED_FEATURE -> sampled;
       };
   }

   static BlockState oreVeinReplacement(
      BlockState vanillaState, boolean applyOreVeins, boolean applyGeologicalStonePatches
   ) {
      if (applyOreVeins && (vanillaState.is(Blocks.COPPER_ORE)
         || vanillaState.is(Blocks.RAW_COPPER_BLOCK)
         || vanillaState.is(Blocks.DEEPSLATE_IRON_ORE)
         || vanillaState.is(Blocks.RAW_IRON_BLOCK))) {
         return vanillaState;
      }
      if (applyGeologicalStonePatches && (vanillaState.is(Blocks.GRANITE) || vanillaState.is(Blocks.TUFF))) {
         return vanillaState;
      }
      return null;
   }

   private static int[] minimumNearbySurfaceYByColumn(
      IntBinaryOperator surfaceHeightSampler, int chunkMinX, int chunkMinZ, int radius
   ) {
      int sampleSide = CHUNK_SIDE + radius * 2;
      int[] sampledSurfaceY = new int[sampleSide * sampleSide];
      for (int sampleZ = 0; sampleZ < sampleSide; sampleZ++) {
         for (int sampleX = 0; sampleX < sampleSide; sampleX++) {
            sampledSurfaceY[sampleZ * sampleSide + sampleX] = surfaceHeightSampler.applyAsInt(
               chunkMinX + sampleX - radius, chunkMinZ + sampleZ - radius
            );
         }
      }

      int[] minimumSurfaceYByColumn = new int[CHUNK_AREA];
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int minimumSurfaceY = Integer.MAX_VALUE;
            for (int dz = 0; dz <= radius * 2; dz++) {
               for (int dx = 0; dx <= radius * 2; dx++) {
                  minimumSurfaceY = Math.min(
                     minimumSurfaceY, sampledSurfaceY[(localZ + dz) * sampleSide + localX + dx]
                  );
               }
            }
            minimumSurfaceYByColumn[localZ * CHUNK_SIDE + localX] = minimumSurfaceY;
         }
      }
      return minimumSurfaceYByColumn;
   }

   static int surfaceReferenceY(int highestSolidY, int preliminarySurfaceY, boolean cavesReachSurface) {
      return cavesReachSurface && preliminarySurfaceY - highestSolidY > SURFACE_COVER_DEPTH
         ? preliminarySurfaceY
         : highestSolidY;
   }

   private record SeededRandomState(long seed, RandomState state) {
   }

   @FunctionalInterface
   public interface CaveBlockWriter {
      void set(ChunkAccess chunk, BlockPos pos, BlockState state, boolean fluid);
   }

   record VanillaField(int minY, int height, BlockState[] states, int[] preliminarySurfaceY) {
      BlockState state(int localX, int y, int localZ) {
         return this.states[index(localX, y, localZ, this.minY)];
      }

      int highestSolidY(int localX, int localZ) {
         for (int y = this.minY + this.height - 1; y >= this.minY; y--) {
            BlockState state = this.state(localX, y, localZ);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
               return y;
            }
         }
         return this.minY;
      }

      int surfaceReferenceY(int localX, int localZ, boolean cavesReachSurface) {
         int highestSolidY = this.highestSolidY(localX, localZ);
         int preliminaryY = this.preliminarySurfaceY[localZ * CHUNK_SIDE + localX];
         return TellusVanillaNoiseCaveSampler.surfaceReferenceY(highestSolidY, preliminaryY, cavesReachSurface);
      }

      static int index(int localX, int y, int localZ, int minY) {
         return (y - minY) * CHUNK_AREA + localZ * CHUNK_SIDE + localX;
      }
   }

}
