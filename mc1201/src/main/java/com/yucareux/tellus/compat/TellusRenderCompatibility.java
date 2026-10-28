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
   private static final String SUPPORTED_SODIUM_VERSION = "0.5.13+mc1.20.1";

   private TellusRenderCompatibility() { }

   public static boolean sodiumLoaded() {
      return FabricLoader.getInstance().isModLoaded(SODIUM_MOD_ID);
   }

   public static String sodiumVersion() {
      return FabricLoader.getInstance().getModContainer(SODIUM_MOD_ID)
         .map(container -> container.getMetadata().getVersion().getFriendlyString())
         .orElse("");
   }

   public static boolean sodiumStagingSupported() {
      return sodiumLoaded() && supportsSodiumVersion(sodiumVersion());
   }

   static boolean supportsSodiumVersion(String version) {
      return SUPPORTED_SODIUM_VERSION.equals(version);
   }

   /**
    * The existing staged-view implementation owns vanilla's ViewArea and
    * ChunkRenderDispatcher.  Sodium replaces that renderer path, so it must
    * not receive the vanilla backend mixins even when a native adapter is enabled.
    */
   public static boolean vanillaStagedMixinsEnabled() {
      return !sodiumLoaded();
   }

   public static boolean cubeNetStagingSupported() {
      return vanillaStagedMixinsEnabled() || sodiumStagingSupported();
   }

   public static String backendId() {
      if (!sodiumLoaded()) return "vanilla";
      return sodiumStagingSupported() ? SODIUM_MOD_ID + "-" + sodiumVersion()
         : SODIUM_MOD_ID + "-unsupported";
   }
}
