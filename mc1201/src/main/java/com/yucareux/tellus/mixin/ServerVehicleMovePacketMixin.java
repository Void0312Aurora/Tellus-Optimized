package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.CubeNetRuntime;
import com.yucareux.tellus.worldgen.CubeNetVehicleMovementBaseline;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops stale source-chart vehicle packets during a mounted cube-net rebase. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerVehicleMovePacketMixin implements CubeNetVehicleMovementBaseline {
   @Shadow private Entity lastVehicle;
   @Shadow private double vehicleFirstGoodX;
   @Shadow private double vehicleFirstGoodY;
   @Shadow private double vehicleFirstGoodZ;
   @Shadow private double vehicleLastGoodX;
   @Shadow private double vehicleLastGoodY;
   @Shadow private double vehicleLastGoodZ;

   @Override
   public void tellus$resetVehiclePosition(Entity vehicle) {
      ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl)(Object)this;
      if (vehicle == listener.player || vehicle.getControllingPassenger() != listener.player) return;
      this.lastVehicle = vehicle;
      this.vehicleFirstGoodX = this.vehicleLastGoodX = vehicle.getX();
      this.vehicleFirstGoodY = this.vehicleLastGoodY = vehicle.getY();
      this.vehicleFirstGoodZ = this.vehicleLastGoodZ = vehicle.getZ();
   }

   // Holding a staged vehicle mutates entities, so this must run after the
   // packet's ordinary handoff from Netty to the server thread.
   @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
      shift = At.Shift.AFTER), cancellable = true)
   private void tellus$filterStaleVehiclePacket(ServerboundMoveVehiclePacket packet, CallbackInfo info) {
      ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl)(Object)this;
      if (CubeNetRuntime.interceptVehicleMovePacket(listener.player, packet)) info.cancel();
   }
}
