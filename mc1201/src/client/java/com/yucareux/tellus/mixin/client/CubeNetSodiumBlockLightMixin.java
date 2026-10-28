package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shared shade sampler for Sodium flat/AO lighting and Indium's model pipeline. */
@Mixin(WorldSlice.class)
public abstract class CubeNetSodiumBlockLightMixin {
   @Shadow @Final private ClientLevel world;
   @Shadow private int originX;
   @Shadow private int originZ;

   @Inject(method = "getShade", at = @At("HEAD"), cancellable = true)
   private void tellus$applyChartRelativeShade(Direction direction, boolean shaded,
      CallbackInfoReturnable<Float> callback) {
      // Sodium's slice starts one section before the section being compiled.
      Direction mapped = CubeNetClientTransition.sodiumShadeDirection(direction,
         (this.originX >> 4) + 1, (this.originZ >> 4) + 1);
      callback.setReturnValue(this.world.getShade(mapped, shaded));
   }
}
