package com.yucareux.tellus.worldgen;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Shared rock palette without initializing a generator or geographic data sources. */
public final class StrataMaterials {
   private static final BlockState[] STATES = buildStates();

   private StrataMaterials() {
   }

   public static BlockState state(int material) {
      return STATES[material];
   }

   private static BlockState[] buildStates() {
      BlockState[] states = new BlockState[StrataPolicy.MATERIAL_COUNT];
      for (int material = 0; material < states.length; material++) {
         states[material] = switch (material) {
            case StrataPolicy.ANDESITE -> Blocks.ANDESITE.defaultBlockState();
            case StrataPolicy.DIORITE -> Blocks.DIORITE.defaultBlockState();
            case StrataPolicy.GRANITE -> Blocks.GRANITE.defaultBlockState();
            case StrataPolicy.CALCITE -> Blocks.CALCITE.defaultBlockState();
            case StrataPolicy.DEEPSLATE -> Blocks.DEEPSLATE.defaultBlockState();
            case StrataPolicy.TUFF -> Blocks.TUFF.defaultBlockState();
            case StrataPolicy.BASALT -> Blocks.BASALT.defaultBlockState();
            case StrataPolicy.SMOOTH_BASALT -> Blocks.SMOOTH_BASALT.defaultBlockState();
            case StrataPolicy.BLACKSTONE -> Blocks.BLACKSTONE.defaultBlockState();
            case StrataPolicy.SANDSTONE -> Blocks.SANDSTONE.defaultBlockState();
            case StrataPolicy.SMOOTH_SANDSTONE -> Blocks.SMOOTH_SANDSTONE.defaultBlockState();
            case StrataPolicy.RED_SANDSTONE -> Blocks.RED_SANDSTONE.defaultBlockState();
            case StrataPolicy.TERRACOTTA -> Blocks.TERRACOTTA.defaultBlockState();
            case StrataPolicy.WHITE_TERRACOTTA -> TellusBlockReferences.terracottaState("WHITE");
            case StrataPolicy.LIGHT_GRAY_TERRACOTTA -> TellusBlockReferences.terracottaState("LIGHT_GRAY");
            case StrataPolicy.RED_TERRACOTTA -> TellusBlockReferences.terracottaState("RED");
            case StrataPolicy.ORANGE_TERRACOTTA -> TellusBlockReferences.terracottaState("ORANGE");
            case StrataPolicy.PACKED_MUD -> Blocks.PACKED_MUD.defaultBlockState();
            case StrataPolicy.COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
            case StrataPolicy.CLAY -> Blocks.CLAY.defaultBlockState();
            case StrataPolicy.COBBLESTONE -> Blocks.COBBLESTONE.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
         };
      }
      return states;
   }
}
