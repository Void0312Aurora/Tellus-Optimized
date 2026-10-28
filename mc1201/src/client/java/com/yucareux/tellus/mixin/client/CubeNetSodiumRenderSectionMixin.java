package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetSodiumEntityAccess;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSection.class)
public interface CubeNetSodiumRenderSectionMixin extends CubeNetSodiumEntityAccess {
   @Override
   @Accessor("culledBlockEntities")
   void tellus$setCulledBlockEntities(BlockEntity[] entities);

   @Override
   @Accessor("globalBlockEntities")
   void tellus$setGlobalBlockEntities(BlockEntity[] entities);
}
