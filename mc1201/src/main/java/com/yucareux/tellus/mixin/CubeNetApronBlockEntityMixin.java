package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.CubeNetApronBlockEntitySync;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Propagates mutable block-entity payloads from whichever seam view is edited. */
@Mixin(BlockEntity.class)
public abstract class CubeNetApronBlockEntityMixin {
   @Inject(method = "setChanged", at = @At("TAIL"))
   private void tellus$syncChanged(CallbackInfo ci) {
      CubeNetApronBlockEntitySync.onChanged((BlockEntity)(Object)this);
   }
}
