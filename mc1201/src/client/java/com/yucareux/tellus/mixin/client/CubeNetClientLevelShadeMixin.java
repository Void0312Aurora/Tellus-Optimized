package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Samples vanilla directional shade factors in the active cube-net chart. */
@Mixin(ClientLevel.class)
public abstract class CubeNetClientLevelShadeMixin {
   @Inject(method = "getShade", at = @At("HEAD"), cancellable = true)
   private void tellus$shadeInChart(Direction direction, boolean shade,
      CallbackInfoReturnable<Float> callback) {
      Direction mapped = CubeNetClientTransition.shadeDirection(direction);
      if (mapped == direction) return;
      ClientLevel level = (ClientLevel)(Object)this;
      boolean constantAmbient = level.effects().constantAmbientLight();
      float result;
      if (!shade) {
         result = constantAmbient ? 0.9F : 1.0F;
      } else if (mapped == Direction.DOWN) {
         result = constantAmbient ? 0.9F : 0.5F;
      } else if (mapped == Direction.UP) {
         result = 1.0F;
      } else {
         result = switch (mapped) {
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
            default -> 1.0F;
         };
      }
      callback.setReturnValue(result);
   }
}
