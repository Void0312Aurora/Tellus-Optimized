package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft 1.21.1 semantic-to-state mapper; no chunk or world access. */
public final class TellusMinecraft1211SemanticMapper implements SemanticMapper<BlockState> {
   @Override
   public BlockState map(SemanticMaterial material, int y, WriteContext context) {
      return block(material).defaultBlockState();
   }

   private static Block block(SemanticMaterial material) {
      return switch (java.util.Objects.requireNonNull(material, "material")) {
         case AIR -> Blocks.AIR;
         case BEDROCK -> Blocks.BEDROCK;
         case STONE -> Blocks.STONE;
         case DEEPSLATE -> Blocks.DEEPSLATE;
         case DIRT -> Blocks.DIRT;
         case COARSE_DIRT -> Blocks.COARSE_DIRT;
         case ROOTED_DIRT -> Blocks.ROOTED_DIRT;
         case PODZOL -> Blocks.PODZOL;
         case GRASS -> Blocks.GRASS_BLOCK;
         case MOSS -> Blocks.MOSS_BLOCK;
         case MYCELIUM -> Blocks.MYCELIUM;
         case SAND -> Blocks.SAND;
         case SANDSTONE -> Blocks.SANDSTONE;
         case GRAVEL -> Blocks.GRAVEL;
         case ANDESITE -> Blocks.ANDESITE;
         case DIORITE -> Blocks.DIORITE;
         case COBBLESTONE -> Blocks.COBBLESTONE;
         case CLAY -> Blocks.CLAY;
         case MUD -> Blocks.MUD;
         case PACKED_MUD -> Blocks.PACKED_MUD;
         case SNOW -> Blocks.SNOW_BLOCK;
         case POWDER_SNOW -> Blocks.POWDER_SNOW;
         case ICE -> Blocks.ICE;
         case PACKED_ICE -> Blocks.PACKED_ICE;
         case BLUE_ICE -> Blocks.BLUE_ICE;
         case WATER -> Blocks.WATER;
         case TERRACOTTA -> Blocks.TERRACOTTA;
         case RED_SAND -> Blocks.RED_SAND;
         case GRANITE -> Blocks.GRANITE;
         case CALCITE -> Blocks.CALCITE;
         case TUFF -> Blocks.TUFF;
         case BASALT -> Blocks.BASALT;
         case SMOOTH_BASALT -> Blocks.SMOOTH_BASALT;
         case BLACKSTONE -> Blocks.BLACKSTONE;
         case SMOOTH_SANDSTONE -> Blocks.SMOOTH_SANDSTONE;
         case RED_SANDSTONE -> Blocks.RED_SANDSTONE;
         case WHITE_TERRACOTTA -> Blocks.WHITE_TERRACOTTA;
         case LIGHT_GRAY_TERRACOTTA -> Blocks.LIGHT_GRAY_TERRACOTTA;
         case RED_TERRACOTTA -> Blocks.RED_TERRACOTTA;
         case ORANGE_TERRACOTTA -> Blocks.ORANGE_TERRACOTTA;
         case UNKNOWN -> throw new IllegalArgumentException("UNKNOWN material cannot be mapped");
      };
   }
}
