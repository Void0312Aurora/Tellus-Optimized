package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.SphereFaceRuntime;
import com.yucareux.tellus.worldgen.CubeNetRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a spherical player from being committed to the flat engine position
 * outside its face chart. Ordinary movement and collision remain vanilla.
 */
@Mixin(Entity.class)
public abstract class SphericalEntityMoveMixin {
   @Inject(method = "move", at = @At("HEAD"), cancellable = true)
   private void tellus$interceptSphericalBoundary(MoverType type, Vec3 movement, CallbackInfo ci) {
      if ((Object)this instanceof ServerPlayer player
         && (CubeNetRuntime.interceptBoundaryMove(player, movement)
            || SphereFaceRuntime.interceptBoundaryMove(player, movement))) {
         ci.cancel();
      }
   }
}
