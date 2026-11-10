package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.GeologicalStonePlacementPolicy;
import com.yucareux.tellus.worldgen.caves.CubeNetSphericalCaveField.Cavity;
import com.yucareux.tellus.worldgen.caves.CubeNetSphericalCaveField.Feature;

/** Resolved spherical-field material selection; spherical field and source order remain native. */
public final class TellusCubeUndergroundDecisionKernel {
   public enum Material { NONE, CAVE_AIR, WATER, LAVA, COPPER_ORE, DEEPSLATE_IRON_ORE, GRANITE, TUFF;
      public boolean noiseFeature() { return ordinal() >= COPPER_ORE.ordinal(); }
   }
   private TellusCubeUndergroundDecisionKernel() { }

   public static Material cavity(Cavity cavity) {
      return switch(cavity) { case AIR -> Material.CAVE_AIR; case WATER -> Material.WATER;
         case LAVA -> Material.LAVA; case SOLID -> Material.NONE; };
   }

   public static Material feature(Feature feature, boolean ores, boolean geology, int y, int minimumNearbySurfaceY) {
      return switch(feature) {
         case COPPER_ORE -> ores ? Material.COPPER_ORE : Material.NONE;
         case DEEPSLATE_IRON_ORE -> ores ? Material.DEEPSLATE_IRON_ORE : Material.NONE;
         case GRANITE -> geology && GeologicalStonePlacementPolicy.isNoiseStoneBuried(y, minimumNearbySurfaceY) ? Material.GRANITE : Material.NONE;
         case TUFF -> geology && GeologicalStonePlacementPolicy.isNoiseStoneBuried(y, minimumNearbySurfaceY) ? Material.TUFF : Material.NONE;
         default -> Material.NONE;
      };
   }
}
