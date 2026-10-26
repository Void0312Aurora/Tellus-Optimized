package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.CubeNetClientViewDistance;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class CubeNetPlayerOptionsMixin implements CubeNetClientViewDistance {
   @Unique private int tellus$viewDistance = 10;

   @Inject(method = "updateOptions", at = @At("TAIL"))
   private void tellus$rememberViewDistance(ServerboundClientInformationPacket packet, CallbackInfo ci) {
      this.tellus$viewDistance = Math.max(2, Math.min(32, packet.viewDistance()));
   }

   @Override
   public int tellus$requestedViewDistance() {
      return this.tellus$viewDistance;
   }
}
