package com.yucareux.tellus.worldgen.feature;

import com.yucareux.tellus.worldgen.decision.TellusRealtimeSnowDecisionKernel;
import com.yucareux.tellus.worldgen.decision.TellusRealtimeSnowDecisionKernel.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Production sink shared by Earth and Cube; preserves native read/write order and update flags. */
public final class TellusNativeRealtimeSnowPlacement {
   private TellusNativeRealtimeSnowPlacement() { }

   public static void place(WorldGenLevel level, BlockPos ground, int updateFlags) {
      BlockState surface = level.getBlockState(ground);
      if (!surface.getFluidState().isEmpty()) return;
      BlockPos above = ground.above();
      boolean airAbove = level.getBlockState(above).isAir();
      BlockState snow = Blocks.SNOW.defaultBlockState();
      boolean canSurvive = airAbove && snow.canSurvive(level, above);
      boolean hasSnowy = surface.hasProperty(BlockStateProperties.SNOWY);
      Placement placement = TellusRealtimeSnowDecisionKernel.resolve(false, airAbove, canSurvive,
         hasSnowy, hasSnowy && surface.getValue(BlockStateProperties.SNOWY));
      if (placement == Placement.NONE) return;
      level.setBlock(above, snow, updateFlags);
      if (placement == Placement.SNOW_LAYER_AND_SNOWY_GROUND)
         level.setBlock(ground, surface.setValue(BlockStateProperties.SNOWY, true), updateFlags);
   }
}
