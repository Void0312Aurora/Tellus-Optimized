package com.yucareux.tellus.worldgen.feature;

/** Exact non-source vanilla water LEVEL. A flow placement also requires a post-processing marker. */
public record WaterFlowState(int level) {
   public WaterFlowState {
      if (level < 1 || level > 8) throw new IllegalArgumentException("flow level must be in1..8");
   }
}
