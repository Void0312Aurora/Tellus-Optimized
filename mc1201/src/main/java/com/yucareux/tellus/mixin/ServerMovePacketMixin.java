package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.CubeNetRuntime;
import com.yucareux.tellus.worldgen.CubeNetTeleportTracker;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops stale source-face movement packets during a cube-net rebase. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMovePacketMixin implements CubeNetTeleportTracker {
   @Unique private int tellus$lastIssuedTeleportId;
   @Unique private boolean tellus$hasIssuedTeleportId;

   @Override
   @Accessor("awaitingTeleport")
   public abstract int tellus$awaitingTeleportId();

   @Accessor("awaitingTeleport")
   public abstract void tellus$setAwaitingTeleportId(int id);

   @Accessor("awaitingPositionFromClient")
   public abstract void tellus$setAwaitingPositionFromClient(Vec3 position);

   @Override
   @Accessor("awaitingPositionFromClient")
   public abstract Vec3 tellus$awaitingPositionFromClient();

   @Override
   public void tellus$clearAwaitingTeleport() {
      this.tellus$lastIssuedTeleportId = this.tellus$awaitingTeleportId();
      this.tellus$hasIssuedTeleportId = true;
      this.tellus$setAwaitingTeleportId(-1);
      this.tellus$setAwaitingPositionFromClient(null);
   }

   @Inject(method = "teleport(DDDFF)V", at = @At("HEAD"))
   private void tellus$restoreTeleportSequence(double x, double y, double z, float yaw, float pitch,
      CallbackInfo info) {
      this.tellus$restoreTeleportSequence();
   }

   @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"))
   private void tellus$restoreTeleportSequenceWithRelativeMovement(double x, double y, double z,
      float yaw, float pitch, java.util.Set<RelativeMovement> relativeMovement, CallbackInfo info) {
      this.tellus$restoreTeleportSequence();
   }

   @Unique
   private void tellus$restoreTeleportSequence() {
      if (this.tellus$hasIssuedTeleportId && this.tellus$awaitingTeleportId() == -1) {
         this.tellus$setAwaitingTeleportId(this.tellus$lastIssuedTeleportId);
      }
   }

   // The first invocation runs on Netty. Never hold or move the passenger tree
   // until vanilla has scheduled this packet on the server thread.
   @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
      shift = At.Shift.AFTER), cancellable = true)
   private void tellus$filterStaleSeamPacket(ServerboundMovePlayerPacket packet, CallbackInfo info) {
      ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl)(Object)this;
      if (CubeNetRuntime.interceptMovePacket(listener.player, packet)) info.cancel();
   }

   @Inject(method = "handleAcceptTeleportPacket", at = @At("HEAD"))
   private void tellus$traceSeamTeleportConfirmation(ServerboundAcceptTeleportationPacket packet,
      CallbackInfo info) {
      ServerGamePacketListenerImpl listener = (ServerGamePacketListenerImpl)(Object)this;
      // Packet handlers can first be entered on Netty, then re-entered on the
      // server thread after PacketUtils schedules them. Record only the latter.
      if (!listener.player.server.isSameThread()) return;
      CubeNetRuntime.observeTeleportConfirmation(listener.player, packet.getId(),
         this.tellus$awaitingTeleportId());
   }

}
