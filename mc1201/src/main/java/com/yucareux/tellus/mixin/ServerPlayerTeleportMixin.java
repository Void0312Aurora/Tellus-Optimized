package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.CubeNetRuntime;
import com.yucareux.tellus.worldgen.SphereFaceRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Applies the spherical ordinary-area admission rule to server teleports. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTeleportMixin {
   @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
      at = @At("HEAD"), cancellable = true)
   private void tellus$blockSingularityTeleport(ServerLevel level, double x, double y, double z,
      float yaw, float pitch, CallbackInfo info) {
      if (CubeNetRuntime.admitsPlayerDestination(level, x, z)
         && SphereFaceRuntime.admitsPlayerDestination(level, x, z)) {
         CubeNetRuntime.clearBoundaryHint((ServerPlayer)(Object)this);
         return;
      }
      CubeNetRuntime.notifyBoundaryBlocked((ServerPlayer)(Object)this);
      info.cancel();
   }

   @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
      at = @At("HEAD"), cancellable = true)
   private void tellus$blockRelativeTeleport(ServerLevel level, double x, double y, double z,
      Set<RelativeMovement> relativeMovement, float yaw, float pitch,
      CallbackInfoReturnable<Boolean> info) {
      if (CubeNetRuntime.admitsPlayerDestination(level, x, z)
         && SphereFaceRuntime.admitsPlayerDestination(level, x, z)) {
         CubeNetRuntime.clearBoundaryHint((ServerPlayer)(Object)this);
         return;
      }
      CubeNetRuntime.notifyBoundaryBlocked((ServerPlayer)(Object)this);
      info.setReturnValue(false);
   }
}
