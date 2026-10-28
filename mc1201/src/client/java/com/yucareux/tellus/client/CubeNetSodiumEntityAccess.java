package com.yucareux.tellus.client;

import net.minecraft.world.level.block.entity.BlockEntity;

/** Block-entity references must follow the promoted client chunks. */
public interface CubeNetSodiumEntityAccess {
   void tellus$setCulledBlockEntities(BlockEntity[] entities);

   void tellus$setGlobalBlockEntities(BlockEntity[] entities);
}
