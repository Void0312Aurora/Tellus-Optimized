package com.yucareux.tellus.worldgen;

import net.minecraft.world.phys.Vec3;

/** Access to vanilla's active player-position correction id for seam handling. */
public interface CubeNetTeleportTracker {
   int tellus$awaitingTeleportId();
   Vec3 tellus$awaitingPositionFromClient();
   void tellus$clearAwaitingTeleport();
}
