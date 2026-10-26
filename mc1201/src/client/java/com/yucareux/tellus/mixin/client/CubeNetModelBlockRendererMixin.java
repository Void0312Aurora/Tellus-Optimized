package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Provides the staged block position to the chart-relative shade hook. */
@Mixin(ModelBlockRenderer.class)
public abstract class CubeNetModelBlockRendererMixin {
   @Inject(method = "tesselateBlock", at = @At("HEAD"))
   private void tellus$beginShadeContext(BlockAndTintGetter level, BakedModel model, BlockState state,
      BlockPos position, PoseStack poseStack, VertexConsumer consumer, boolean cull, RandomSource random,
      long seed, int overlay, CallbackInfo callback) {
      CubeNetClientTransition.beginShadeRender(position);
   }

   @Inject(method = "tesselateBlock", at = @At("RETURN"))
   private void tellus$endShadeContext(BlockAndTintGetter level, BakedModel model, BlockState state,
      BlockPos position, PoseStack poseStack, VertexConsumer consumer, boolean cull, RandomSource random,
      long seed, int overlay, CallbackInfo callback) {
      CubeNetClientTransition.endShadeRender();
   }
}
