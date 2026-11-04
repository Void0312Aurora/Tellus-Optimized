package com.yucareux.tellus.worldgen;

/** Water and depth limits shared by every canonical Cube-Net cave column. */
public final class CubeNetUndergroundPolicy {
   public static final int WATER_HALO = 2;
   private static final int WATER_COVER = 4;
   private static final int OCEAN_FLOOR_COVER = 3;

   private CubeNetUndergroundPolicy() { }

   /** The guard itself and every block above it remain protected. */
   public static int floodGuardY(int surfaceY, int waterY, boolean ocean) {
      int guard = waterY - WATER_COVER;
      return ocean ? Math.min(guard, surfaceY - OCEAN_FLOOR_COVER) : guard;
   }

   public static int generationFloorY(int surfaceY, int undergroundDepth, int minimumY) {
      return Math.max(minimumY, UndergroundGenerationDepthPolicy.generationFloorY(surfaceY, undergroundDepth));
   }
}
