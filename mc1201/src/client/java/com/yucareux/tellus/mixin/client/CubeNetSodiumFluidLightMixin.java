package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import me.jellysquid.mods.sodium.client.model.color.ColorProvider;
import me.jellysquid.mods.sodium.client.model.light.LightPipeline;
import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rotates Sodium's hard-coded horizontal fluid brightness into the active chart. */
@Mixin(FluidRenderer.class)
public abstract class CubeNetSodiumFluidLightMixin {
   @Shadow private int[] quadColors;

   @Inject(method = "updateQuad", at = @At("RETURN"))
   private void tellus$rotateFluidBrightness(ModelQuadView quad, WorldSlice world, BlockPos position,
      LightPipeline pipeline, Direction face, float brightness, ColorProvider<FluidState> colorProvider,
      FluidState fluidState, CallbackInfo callback) {
      float rotated = CubeNetClientTransition.sodiumFluidBrightness(face, position, brightness);
      if (rotated == brightness || brightness == 0.0F) return;
      float ratio = rotated / brightness;
      for (int i = 0; i < this.quadColors.length; i++) {
         int color = this.quadColors[i];
         float red = ColorABGR.unpackRed(color) / 255.0F;
         float green = ColorABGR.unpackGreen(color) / 255.0F;
         float blue = ColorABGR.unpackBlue(color) / 255.0F;
         float alpha = ColorABGR.unpackAlpha(color) / 255.0F;
         // Fluid brightness is an RGB multiplier. Writing the ratio to alpha
         // makes water translucent, and ColorABGR's byte conversion wraps
         // values above 1 instead of clamping them.
         this.quadColors[i] = ColorABGR.pack(
            clamp(red * ratio), clamp(green * ratio), clamp(blue * ratio), alpha);
      }
   }

   private static float clamp(float value) {
      return Math.max(0.0F, Math.min(1.0F, value));
   }
}
