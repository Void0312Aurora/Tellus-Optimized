package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Retains vanilla rebuild workers while supplying the target chart's camera. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask")
public abstract class CubeNetRebuildCameraMixin {
   @Shadow @Final private ChunkRenderDispatcher.RenderChunk field_20839;

   @Redirect(method = "doTask", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;getCameraPosition()Lnet/minecraft/world/phys/Vec3;"))
   private Vec3 tellus$targetCamera(ChunkRenderDispatcher dispatcher) {
      return CubeNetClientTransition.compilationCamera(this.field_20839, dispatcher.getCameraPosition());
   }
}
