package com.yucareux.tellus.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Detects optional client render backends without linking against their
 * internal classes.  The detection is intentionally kept in the common
 * source set so the mixin plugin and the client transition code use the same
 * decision.
 */
public final class TellusRenderCompatibility {
   private static final String SODIUM_MOD_ID = "sodium";

   private TellusRenderCompatibility() { }

   public static boolean sodiumLoaded() {
      return FabricLoader.getInstance().isModLoaded(SODIUM_MOD_ID);
   }

   /**
    * The existing staged-view implementation owns vanilla's ViewArea and
    * ChunkRenderDispatcher.  Sodium replaces that renderer path, so it must
    * not receive the vanilla backend mixins until a native adapter exists.
    */
   public static boolean vanillaStagedMixinsEnabled() {
      return !sodiumLoaded();
   }

   public static boolean cubeNetStagingSupported() {
      return vanillaStagedMixinsEnabled();
   }

   public static String backendId() {
      return sodiumLoaded() ? SODIUM_MOD_ID : "vanilla";
   }
}
