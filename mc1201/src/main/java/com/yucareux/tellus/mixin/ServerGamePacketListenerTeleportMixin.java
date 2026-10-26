package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.CubeNetRuntime;
import com.yucareux.tellus.worldgen.SphereFaceRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers same-level command and connection teleports after player movement checks. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerTeleportMixin {
   @Inject(method = "teleport(DDDFF)V", at = @At("HEAD"), cancellable = true)
   private void tellus$blockSingularityTeleport(double x, double y, double z,
      float yaw, float pitch, CallbackInfo info) {
      ServerGamePacketListenerImpl connection = (ServerGamePacketListenerImpl)(Object)this;
      ServerPlayer player = connection.player;
      if (CubeNetRuntime.admitsPlayerDestination(player.serverLevel(), x, z)
         && SphereFaceRuntime.admitsPlayerDestination(player.serverLevel(), x, z)) return;
      CubeNetRuntime.notifyBoundaryBlocked(player);
      info.cancel();
   }
}
