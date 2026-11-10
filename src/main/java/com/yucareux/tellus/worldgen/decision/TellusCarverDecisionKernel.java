package com.yucareux.tellus.worldgen.decision;

/** Native configured-carver bounds and cell guards, without Minecraft objects. */
public final class TellusCarverDecisionKernel {
   private TellusCarverDecisionKernel() { }

   public record Bounds(int floorY, int ceilingY, int extraCeilingY, int canyonFloorY, int lavaLevelY) { }

   public static Bounds bounds(int minY, int maxY, int undergroundDepth, int surfaceY) {
      int surface = Math.max(minY, Math.min(maxY, surfaceY));
      int floor = Math.max(minY, surface - undergroundDepth + 1);
      int ceiling = Math.max(floor, Math.min(maxY, surface - 1));
      return new Bounds(floor, ceiling, Math.max(floor, ceiling - 16),
         Math.max(floor, ceiling - 96), Math.min(ceiling, floor + 8));
   }

   /** No per-cell allocation or boxing in the native mask's hot path. */
   public static boolean blocksCarving(int y, int index, boolean cavesReachSurface,
      int[] surfaces, int[] floodGuards, int[] generationFloors) {
      return !cavesReachSurface && surfaces != null && y >= surfaces[index] - 4
         || floodGuards != null && y >= floodGuards[index]
         || generationFloors != null && y <= generationFloors[index];
   }
}
