package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rotates Iris' solar/lunar direction together with the active cube-net chart. */
@Mixin(targets = "net.irisshaders.iris.uniforms.CelestialUniforms", remap = false)
public abstract class CubeNetIrisCelestialLightingMixin {
   @Unique
   private static final String CAPTURED_RENDERING_STATE =
      "net.irisshaders.iris.uniforms.CapturedRenderingState";
   @Unique
   private static volatile boolean reflectionWarning;
   @Unique
   private static volatile Field capturedStateInstance;
   @Unique
   private static volatile Method capturedStateModelView;

   @Inject(method = "getCelestialPosition", at = @At("RETURN"), cancellable = true)
   private void tellus$rotateViewCelestial(float distance, CallbackInfoReturnable<Vector4f> callback) {
      callback.setReturnValue(rotateViewVector(callback.getReturnValue()));
   }

   @Inject(method = "getCelestialPositionInWorldSpace", at = @At("RETURN"), cancellable = true)
   private void tellus$rotateWorldCelestial(float distance, CallbackInfoReturnable<Vector4f> callback) {
      callback.setReturnValue(CubeNetClientTransition.rotateCelestialWorld(callback.getReturnValue()));
   }

   private static Vector4f rotateViewVector(Vector4f viewVector) {
      if (CubeNetClientTransition.shadeQuarterTurns() == 0) return viewVector;
      try {
         Field instanceField = capturedStateInstance;
         Method modelViewMethod = capturedStateModelView;
         if (instanceField == null || modelViewMethod == null) {
            synchronized (CubeNetIrisCelestialLightingMixin.class) {
               instanceField = capturedStateInstance;
               modelViewMethod = capturedStateModelView;
               if (instanceField == null || modelViewMethod == null) {
                  Class<?> stateClass = Class.forName(CAPTURED_RENDERING_STATE);
                  instanceField = stateClass.getField("INSTANCE");
                  modelViewMethod = stateClass.getMethod("getGbufferModelView");
                  capturedStateInstance = instanceField;
                  capturedStateModelView = modelViewMethod;
               }
            }
         }
         Object state = instanceField.get(null);
         Matrix4f modelView = new Matrix4f((Matrix4fc) modelViewMethod.invoke(state));
         Vector4f worldVector = new Vector4f(viewVector);
         modelView.invert(new Matrix4f()).transform(worldVector);
         CubeNetClientTransition.rotateCelestialWorld(worldVector);
         modelView.transform(worldVector);
         return worldVector;
      } catch (ReflectiveOperationException | RuntimeException error) {
         if (!reflectionWarning) {
            reflectionWarning = true;
            com.yucareux.tellus.TellusCommon.LOGGER.warn(
               "Cube-net could not rotate Iris celestial direction; retaining Iris vector", error);
         }
         return viewVector;
      }
   }
}
