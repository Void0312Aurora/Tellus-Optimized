package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Iris' shadow projection in the same chart as its celestial vectors. */
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowMatrices", remap = false)
public abstract class CubeNetIrisShadowMatricesMixin {
   @Inject(method = "createBaselineModelViewMatrix", at = @At("RETURN"))
   private static void tellus$rotateShadowChart(PoseStack poseStack, float shadowAngle,
      float sunPathRotation, CallbackInfo callback) {
      int turns = CubeNetClientTransition.shadeQuarterTurns();
      if (turns == 0) return;
      // The matrix maps world coordinates into the shadow view. The active
      // chart is source->target by -shadeQuarterTurns, so its inverse is the
      // post-multiplied target->source rotation below.
      poseStack.mulPose(new Quaternionf().rotateY((float) (-Math.PI * 0.5 * turns)));
   }
}
