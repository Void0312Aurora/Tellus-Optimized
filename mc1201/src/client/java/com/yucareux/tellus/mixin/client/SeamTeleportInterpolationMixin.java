package com.yucareux.tellus.mixin.client;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents a same-Level seam transfer from being interpolated across the
 * disconnected part of the cube-net chart. The server still owns the
 * destination; this only resets the local render history after an explicit
 * teleport correction.
 */
@Mixin(Entity.class)
public abstract class SeamTeleportInterpolationMixin {
   @Inject(method = "lerpTo", at = @At("TAIL"))
   private void tellus$resetTeleportInterpolation(double x, double y, double z,
      float yRot, float xRot, int steps, boolean teleport, CallbackInfo ci) {
      if (teleport) {
         ((Entity)(Object)this).setOldPosAndRot();
      }
   }
}
