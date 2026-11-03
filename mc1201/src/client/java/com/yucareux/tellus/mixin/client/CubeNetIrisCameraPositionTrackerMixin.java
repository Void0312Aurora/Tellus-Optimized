package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetIrisHistoryReset;
import com.yucareux.tellus.TellusCommon;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris keeps previousCameraPosition for shader temporal reprojection. A cube
 * seam is a discontinuous move, so the old position must not be reused for
 * the first frame in the new chart.
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms$CameraPositionTracker", remap = false)
public abstract class CubeNetIrisCameraPositionTrackerMixin {
   @Shadow private Vector3d previousCameraPosition;
   @Shadow private Vector3d currentCameraPosition;
   @Shadow private Vector3d previousCameraPositionUnshifted;
   @Shadow private Vector3d currentCameraPositionUnshifted;

   @Inject(method = "update", at = @At("TAIL"), remap = false)
   private void tellus$resetAfterCubeNetRebase(CallbackInfo callbackInfo) {
      if (!CubeNetIrisHistoryReset.consume()) return;
      this.previousCameraPosition.set(this.currentCameraPosition);
      this.previousCameraPositionUnshifted.set(this.currentCameraPositionUnshifted);
      TellusCommon.LOGGER.info("Cube-net Iris temporal camera history reset");
   }
}
