package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class CubeNetClientPacketMixin {
   @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/entity/Entity;absMoveTo(DDDFF)V"))
   private void tellus$captureVehicleRotation(ClientboundMoveVehiclePacket packet, CallbackInfo ci) {
      CubeNetClientTransition.vehicleMoveStarting(Minecraft.getInstance(), packet);
   }

   @Inject(method = "handleMoveVehicle", at = @At("TAIL"))
   private void tellus$movePreparedPassengers(ClientboundMoveVehiclePacket packet, CallbackInfo ci) {
      CubeNetClientTransition.vehiclePositionApplied(Minecraft.getInstance(), packet);
   }

   @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
   private void tellus$rememberStagedChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
      CubeNetClientTransition.receivedChunk(packet);
   }

   @Inject(method = "handleMovePlayer", at = @At("TAIL"))
   private void tellus$installPreparedChart(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
      CubeNetClientTransition.playerPositionApplied(Minecraft.getInstance());
   }
}
