package com.yucareux.tellus.worldgen.caves;

import com.google.common.base.Preconditions;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.GeologicalStonePlacementPolicy;
import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Applies Tellus underground policies directly to canonical spherical cells.
 * No planar ChunkPos participates in the cave field; Minecraft coordinates are
 * used only for writing the already-owned target chunk.
 */
public final class CubeNetSphericalCaveSampler {
   private static final int SIDE = 16;
   private static final int AREA = SIDE * SIDE;
   private static final int SURFACE_COVER_DEPTH = 4;
   private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
   private static final BlockState WATER = Blocks.WATER.defaultBlockState();
   private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

   public void apply(
      SphereWorldModel model,
      SphereWorldModel.SphereChunkAddress address,
      CubeNetSphericalCaveField field,
      ChunkAccess chunk,
      int chunkMinY,
      int seaLevel,
      boolean applyCaves,
      boolean cavesReachSurface,
      boolean applyOreVeins,
      boolean applyGeologicalStonePatches,
      int[] surfaceYByColumn,
      IntBinaryOperator surfaceHeightSampler,
      int[] floodGuardYByColumn,
      int[] generationFloorYByColumn,
      List<UndergroundStructureExclusion.Box> structureExclusions,
      TellusVanillaNoiseCaveSampler.CaveBlockWriter blockWriter
   ) {
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(address, "address");
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(chunk, "chunk");
      Objects.requireNonNull(surfaceYByColumn, "surfaceYByColumn");
      Objects.requireNonNull(blockWriter, "blockWriter");
      if (applyGeologicalStonePatches) Objects.requireNonNull(surfaceHeightSampler, "surfaceHeightSampler");
      Preconditions.checkArgument(surfaceYByColumn.length == AREA,
         "spherical cave surface array must contain 256 columns");
      if (floodGuardYByColumn != null) Preconditions.checkArgument(floodGuardYByColumn.length == AREA,
         "spherical cave flood array must contain 256 columns");
      if (generationFloorYByColumn != null) Preconditions.checkArgument(generationFloorYByColumn.length == AREA,
         "spherical cave floor array must contain 256 columns");
      Preconditions.checkArgument(applyCaves || applyOreVeins || applyGeologicalStonePatches,
         "At least one spherical underground feature must be enabled");

      int chunkMinX = chunk.getPos().getMinBlockX();
      int chunkMinZ = chunk.getPos().getMinBlockZ();
      var placement = CubeNetLayout.standard(model.config().faceBlocks())
         .placement(CubeNetLayout.Face.of(address.face()));
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      for (int localZ = 0; localZ < SIDE; localZ++) {
         for (int localX = 0; localX < SIDE; localX++) {
            int index = localZ * SIDE + localX;
            int surfaceY = surfaceYByColumn[index];
            int bottomY = chunkMinY + 1;
            if (generationFloorYByColumn != null) {
               bottomY = Math.max(bottomY, generationFloorYByColumn[index] + 1);
            }
            int firstCarveY = Math.min(MinecraftVersionCompat.maxY(chunk),
               applyCaves && cavesReachSurface ? surfaceY : surfaceY - SURFACE_COVER_DEPTH - 1);
            if (firstCarveY < bottomY) continue;
            int worldX = chunkMinX + localX;
            int worldZ = chunkMinZ + localZ;
            int minimumNearbySurfaceY = applyGeologicalStonePatches ? minimumNearbySurface(
               surfaceYByColumn, surfaceHeightSampler, worldX, worldZ, localX, localZ, surfaceY) : surfaceY;
            SphereWorldModel.GeoPoint point = model.exactSurfacePoint(
               address.face(),
               address.blockX() + 8.0 + placement.uAxisX() * (localX - 7.5)
                  + placement.uAxisZ() * (localZ - 7.5),
               address.blockZ() + 8.0 + placement.vAxisX() * (localX - 7.5)
                  + placement.vAxisZ() * (localZ - 7.5));
            CubeNetSphericalCaveField.Column column = field.column(point, model.config().worldScale());
            for (int actualY = firstCarveY; actualY >= bottomY; actualY--) {
               int depth = Math.max(0, surfaceY - actualY);
               CubeNetSphericalCaveField.Position position = column.atHeight(actualY - seaLevel);
               boolean caveAllowed = applyCaves
                  && !UndergroundStructureExclusion.blocksCarving(structureExclusions, worldX, actualY, worldZ)
                  && (floodGuardYByColumn == null || actualY < floodGuardYByColumn[index]);
               BlockState replacement = null;
               boolean noiseFeature = false;
               if (caveAllowed) {
                  replacement = switch (field.cavity(position, actualY - seaLevel, depth, actualY, chunkMinY)) {
                     case AIR -> CAVE_AIR;
                     case WATER -> WATER;
                     case LAVA -> LAVA;
                     case SOLID -> null;
                  };
               }
               if (replacement == null && (applyOreVeins || applyGeologicalStonePatches)) {
                  CubeNetSphericalCaveField.Feature feature = field.rockFeature(
                     position, depth, applyOreVeins, applyGeologicalStonePatches);
                  replacement = featureState(feature, applyOreVeins, applyGeologicalStonePatches);
                  if (replacement != null && (feature == CubeNetSphericalCaveField.Feature.GRANITE
                     || feature == CubeNetSphericalCaveField.Feature.TUFF)) {
                     if (!GeologicalStonePlacementPolicy.isNoiseStoneBuried(actualY, minimumNearbySurfaceY)) {
                        replacement = null;
                     }
                  }
                  noiseFeature = replacement != null;
               }
               if (replacement == null) continue;

               cursor.set(worldX, actualY, worldZ);
               BlockState current = chunk.getBlockState(cursor);
               boolean replaceable = noiseFeature
                  ? current.is(BlockTags.BASE_STONE_OVERWORLD)
                  : VanillaNoiseFieldSampler.isCarvable(current);
               if (!replaceable) continue;
               blockWriter.set(chunk, cursor, replacement, !replacement.getFluidState().isEmpty());
            }
         }
      }
   }

   private static BlockState featureState(
      CubeNetSphericalCaveField.Feature feature, boolean applyOreVeins, boolean applyGeologicalStonePatches
   ) {
      return switch (feature) {
         case COPPER_ORE -> applyOreVeins ? Blocks.COPPER_ORE.defaultBlockState() : null;
         case DEEPSLATE_IRON_ORE -> applyOreVeins ? Blocks.DEEPSLATE_IRON_ORE.defaultBlockState() : null;
         case GRANITE -> applyGeologicalStonePatches ? Blocks.GRANITE.defaultBlockState() : null;
         case TUFF -> applyGeologicalStonePatches ? Blocks.TUFF.defaultBlockState() : null;
         default -> null;
      };
   }

   private static int minimumNearbySurface(
      int[] surfaceY,
      IntBinaryOperator surfaceSampler,
      int worldX,
      int worldZ,
      int localX,
      int localZ,
      int fallback
   ) {
      int minimum = fallback;
      for (int dz = -GeologicalStonePlacementPolicy.NOISE_SURFACE_SAMPLE_RADIUS;
         dz <= GeologicalStonePlacementPolicy.NOISE_SURFACE_SAMPLE_RADIUS; dz++) {
         for (int dx = -GeologicalStonePlacementPolicy.NOISE_SURFACE_SAMPLE_RADIUS;
            dx <= GeologicalStonePlacementPolicy.NOISE_SURFACE_SAMPLE_RADIUS; dx++) {
            int x = localX + dx, z = localZ + dz;
            if (x >= 0 && x < SIDE && z >= 0 && z < SIDE) {
               minimum = Math.min(minimum, surfaceY[z * SIDE + x]);
            } else if (surfaceSampler != null) {
               minimum = Math.min(minimum, surfaceSampler.applyAsInt(worldX + dx, worldZ + dz));
            }
         }
      }
      return minimum;
   }
}
