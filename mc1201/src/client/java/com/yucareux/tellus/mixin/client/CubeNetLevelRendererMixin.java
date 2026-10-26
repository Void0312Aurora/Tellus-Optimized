package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetRenderStaging;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Mixin(LevelRenderer.class)
public abstract class CubeNetLevelRendererMixin implements CubeNetRenderStaging {
   @Shadow private ViewArea viewArea;
   @Shadow public abstract void allChanged();
   @Unique private boolean tellus$syncFirstCull;

   @Override
   public void tellus$installStagedView(ViewArea staged) {
      // Reuse vanilla's task drain and visibility-storage reset. Its new empty
      // pool is replaced immediately by our precompiled pool, before rendering.
      this.allChanged();
      this.viewArea.releaseAllBuffers();
      this.viewArea = staged;
      this.tellus$syncFirstCull = true;
   }

   @Redirect(method = "setupRender", at = @At(value = "INVOKE",
      target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;"))
   private Future<?> tellus$finishFirstChartCull(ExecutorService executor, Runnable update) {
      if (!this.tellus$syncFirstCull) return executor.submit(update);
      this.tellus$syncFirstCull = false;
      // An asynchronous first cull would draw the newly emptied visibility
      // list for one frame even though every staged mesh already exists.
      update.run();
      return CompletableFuture.completedFuture(null);
   }
}
