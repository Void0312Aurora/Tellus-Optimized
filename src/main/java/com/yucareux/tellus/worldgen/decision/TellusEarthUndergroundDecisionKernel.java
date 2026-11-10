package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.GeologicalStonePlacementPolicy;

/** Resolved projected vanilla-field rules; no source queries or native writes. */
public final class TellusEarthUndergroundDecisionKernel {
   public enum Sample { SOLID, AIR, LAVA, FLUID, ORE_VEIN, GEOLOGICAL_STONE }
   public enum Decision {
      NONE, CAVE_AIR, WATER, LAVA, SAMPLED_FEATURE;
      public boolean noiseFeature() { return this == SAMPLED_FEATURE; }
   }

   private TellusEarthUndergroundDecisionKernel() { }

   public static Decision resolve(Sample sample, int y, int seaLevel,
      boolean applyCaves, boolean carvingBlocked, boolean floodBlocked,
      boolean applyOreVeins, boolean applyGeology, int minimumNearbySurfaceY) {
      if (applyCaves && !carvingBlocked && !floodBlocked) {
         switch (sample) {
            case AIR: return Decision.CAVE_AIR;
            case LAVA: return Decision.LAVA;
            case FLUID: return y <= seaLevel ? Decision.WATER : Decision.CAVE_AIR;
            default: break;
         }
      }
      if (sample == Sample.ORE_VEIN && applyOreVeins) return Decision.SAMPLED_FEATURE;
      if (sample == Sample.GEOLOGICAL_STONE && applyGeology
         && GeologicalStonePlacementPolicy.isNoiseStoneBuried(y, minimumNearbySurfaceY))
         return Decision.SAMPLED_FEATURE;
      return Decision.NONE;
   }
}
