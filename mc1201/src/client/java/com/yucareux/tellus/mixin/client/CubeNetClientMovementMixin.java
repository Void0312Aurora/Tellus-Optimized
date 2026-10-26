package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends the first handoff movement sample before vanilla movement and records both boundaries. */
@Mixin(LocalPlayer.class)
public abstract class CubeNetClientMovementMixin {
   @Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
   private void tellus$traceMovementSendStart(CallbackInfo ci) {
      if (CubeNetClientTransition.holdsPlayer()) {
         ci.cancel();
         return;
      }
      CubeNetClientTransition.outboundMovementTrace(Minecraft.getInstance(), "head");
   }

   @Inject(method = "sendPosition", at = @At("TAIL"))
   private void tellus$traceMovementSendEnd(CallbackInfo ci) {
      CubeNetClientTransition.outboundMovementTrace(Minecraft.getInstance(), "tail");
   }
}
