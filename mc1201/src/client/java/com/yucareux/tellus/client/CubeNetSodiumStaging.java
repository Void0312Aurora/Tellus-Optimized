package com.yucareux.tellus.client;

import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.compat.TellusRenderCompatibility;
import net.minecraft.client.Minecraft;

/** Optional Sodium bridge. Reflection keeps vanilla client loading independent of Sodium classes. */
public final class CubeNetSodiumStaging {
   private static final String RENDERER_CLASS =
      "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer";
   private static volatile TellusSodiumRenderBackend cachedBackend;

   private CubeNetSodiumStaging() { }

   public static StagedRenderer begin(int centerChunkX, int centerChunkZ, int radius,
      int shadingQuarterTurns) {
      if (!TellusRenderCompatibility.sodiumStagingSupported()) {
         throw new IllegalStateException("unsupported Sodium renderer: " + TellusRenderCompatibility.backendId());
      }
      try {
         Object renderer = Class.forName(RENDERER_CLASS).getMethod("instanceNullable").invoke(null);
         if (!(renderer instanceof TellusSodiumRenderBackend backend)) {
            throw new IllegalStateException("Sodium staging mixin is unavailable");
         }
         cachedBackend = backend;
         Object staged = backend.tellus$createStagedRenderer(centerChunkX, centerChunkZ, radius,
            shadingQuarterTurns);
         return new StagedRenderer(backend, staged);
      } catch (ReflectiveOperationException error) {
         throw new IllegalStateException("could not attach Sodium native staging backend", error);
      }
   }

   public static void tick(StagedRenderer staged) {
      staged.backend.tellus$tickStagedRenderer(staged.renderer);
   }

   public static boolean ready(StagedRenderer staged) {
      return staged.backend.tellus$stagedRendererReady(staged.renderer);
   }

   public static int sectionCount(StagedRenderer staged) {
      return staged.backend.tellus$stagedRendererSectionCount(staged.renderer);
   }

   public static void commit(StagedRenderer staged, int centerChunkX, int centerChunkZ, int renderRadius) {
      staged.backend.tellus$commitStagedRenderer(staged.renderer, centerChunkX, centerChunkZ, renderRadius);
      staged.consumed = true;
   }

   public static void discard(StagedRenderer staged) {
      if (staged == null || staged.consumed) return;
      try {
         staged.backend.tellus$discardStagedRenderer(staged.renderer);
      } catch (RuntimeException error) {
         TellusCommon.LOGGER.error("Failed to discard staged Sodium renderer", error);
      } finally {
         staged.consumed = true;
      }
   }

   /** Updates the normal-movement hysteresis for the one reverse renderer. */
   public static void updateCachedRendererPosition(double x, double z) {
      TellusSodiumRenderBackend backend = backendOrNull();
      if (backend != null) backend.tellus$updateCachedRendererPosition(x, z);
   }

   /** Clears the reverse renderer during disconnect or renderer teardown. */
   public static void discardCachedRenderer() {
      TellusSodiumRenderBackend backend = backendOrNull();
      if (backend != null) backend.tellus$discardCachedRenderer();
   }

   private static TellusSodiumRenderBackend backendOrNull() {
      TellusSodiumRenderBackend known = cachedBackend;
      if (known != null) return known;
      try {
         Object renderer = Class.forName(RENDERER_CLASS).getMethod("instanceNullable").invoke(null);
         TellusSodiumRenderBackend backend = renderer instanceof TellusSodiumRenderBackend value ? value : null;
         cachedBackend = backend;
         return backend;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
         return null;
      }
   }

   public static final class StagedRenderer {
      private final TellusSodiumRenderBackend backend;
      private final Object renderer;
      private boolean consumed;

      private StagedRenderer(TellusSodiumRenderBackend backend, Object renderer) {
         this.backend = backend;
         this.renderer = renderer;
      }
   }
}
