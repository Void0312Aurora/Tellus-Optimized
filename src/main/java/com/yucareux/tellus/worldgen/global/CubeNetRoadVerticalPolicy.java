package com.yucareux.tellus.worldgen.global;

/** Shared vertical placement contract for projected Cube-Net road modes. */
public final class CubeNetRoadVerticalPolicy {
   public static final int TUNNEL_INTERNAL_HEIGHT = 7;
   public static final int BRIDGE_MIN_CLEARANCE = 3;
   public static final int BRIDGE_MAX_RISE = 10;
   public static final int BRIDGE_LEVEL_HEIGHT = 3;

   private CubeNetRoadVerticalPolicy() { }

   public static int bridgeDeckY(int terrainSurfaceY, int bridgeLevel) {
      int requestedRise = Math.max(0, bridgeLevel) * BRIDGE_LEVEL_HEIGHT;
      return terrainSurfaceY + Math.min(BRIDGE_MAX_RISE,
         Math.max(BRIDGE_MIN_CLEARANCE, requestedRise));
   }

   public static int tunnelDeckY(int terrainSurfaceY) {
      return terrainSurfaceY - TUNNEL_INTERNAL_HEIGHT;
   }
}
