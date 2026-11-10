package com.yucareux.tellus.worldgen.decision;

/** Shared resolved realtime-snow placement; geographic/weather sampling stays with each generator. */
public final class TellusRealtimeSnowDecisionKernel {
   public enum Placement { NONE, SNOW_LAYER, SNOW_LAYER_AND_SNOWY_GROUND }
   private TellusRealtimeSnowDecisionKernel() { }

   public static Placement resolve(boolean fluidGround, boolean airAbove, boolean canSurvive,
                                   boolean hasSnowyProperty, boolean alreadySnowy) {
      if (fluidGround || !airAbove || !canSurvive) return Placement.NONE;
      return hasSnowyProperty && !alreadySnowy
         ? Placement.SNOW_LAYER_AND_SNOWY_GROUND : Placement.SNOW_LAYER;
   }
}
