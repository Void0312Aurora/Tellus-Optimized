package com.yucareux.tellus.worldgen.decision;

import java.util.Optional;

/** Pure native Cube inland-flow range/level rules over already-resolved block heights. */
public final class TellusCubeInlandFlowDecisionKernel {
   private TellusCubeInlandFlowDecisionKernel() { }

   /** Inclusive ascending write band; marking each written cell is a downstream transaction requirement. */
   public record Band(int bottomY, int topY, int level) {
      public Band {
         if (bottomY > topY) throw new IllegalArgumentException("empty inland-flow band");
         if (level < 1 || level > 8) throw new IllegalArgumentException("inland-flow level must be 1..8");
      }
   }

   /** Retains native early no-flow behavior, falling-only bottom and exact inclusive clipping. */
   public static Optional<Band> resolve(int minY, int maxYExclusive, boolean hasWater, int flowLevel,
                                        boolean falling, int waterY, int terrainY) {
      if (!hasWater || flowLevel <= 0 || maxYExclusive <= minY) return Optional.empty();
      int bottom = falling ? terrainY + 1 : waterY;
      int first = Math.max(minY, bottom);
      int last = Math.min(maxYExclusive - 1, waterY);
      if (first > last) return Optional.empty();
      return Optional.of(new Band(first, last, Math.max(1, Math.min(8, flowLevel))));
   }
}
