package com.yucareux.tellus.worldgen;

import net.minecraft.world.entity.Entity;

/** Resets vanilla's separate vehicle movement baseline after a chart rebase. */
public interface CubeNetVehicleMovementBaseline {
   void tellus$resetVehiclePosition(Entity vehicle);
}
