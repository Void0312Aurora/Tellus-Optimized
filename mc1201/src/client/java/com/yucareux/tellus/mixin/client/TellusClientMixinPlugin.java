package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.compat.TellusRenderCompatibility;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Selects the renderer-specific part of the client mixin set before target
 * classes are transformed.  This is deliberately a string-based gate: the
 * optional renderer must not be a compile-time dependency of Tellus.
 */
public final class TellusClientMixinPlugin implements IMixinConfigPlugin {
   private static final String VANILLA_RENDERER_MIXIN =
      "com.yucareux.tellus.mixin.client.CubeNetLevelRendererMixin";
   private static final String VANILLA_REBUILD_MIXIN =
      "com.yucareux.tellus.mixin.client.CubeNetRebuildCameraMixin";
   private static final String SODIUM_RENDERER_MIXIN =
      "com.yucareux.tellus.mixin.client.CubeNetSodiumWorldRendererMixin";
   private static final String SODIUM_SECTION_MIXIN =
      "com.yucareux.tellus.mixin.client.CubeNetSodiumRenderSectionManagerMixin";
   private static final String SODIUM_ENTITY_MIXIN =
      "com.yucareux.tellus.mixin.client.CubeNetSodiumRenderSectionMixin";
   private static final String SODIUM_BLOCK_LIGHT_MIXIN =
      "com.yucareux.tellus.mixin.client.CubeNetSodiumBlockLightMixin";
   private static final String SODIUM_FLUID_LIGHT_MIXIN =
      "com.yucareux.tellus.mixin.client.CubeNetSodiumFluidLightMixin";
   private static final String VANILLA_MODEL_LIGHT_MIXIN =
      "com.yucareux.tellus.mixin.client.CubeNetModelBlockRendererMixin";
   private static final String VANILLA_LEVEL_LIGHT_MIXIN =
      "com.yucareux.tellus.mixin.client.CubeNetClientLevelShadeMixin";

   @Override
   public void onLoad(String mixinPackage) { }

   @Override
   public String getRefMapperConfig() {
      return null;
   }

   @Override
   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      if (VANILLA_RENDERER_MIXIN.equals(mixinClassName)
         || VANILLA_REBUILD_MIXIN.equals(mixinClassName)
         || VANILLA_MODEL_LIGHT_MIXIN.equals(mixinClassName)
         || VANILLA_LEVEL_LIGHT_MIXIN.equals(mixinClassName)) {
         return TellusRenderCompatibility.vanillaStagedMixinsEnabled();
      }
      if (SODIUM_RENDERER_MIXIN.equals(mixinClassName)
         || SODIUM_SECTION_MIXIN.equals(mixinClassName)
         || SODIUM_ENTITY_MIXIN.equals(mixinClassName)
         || SODIUM_BLOCK_LIGHT_MIXIN.equals(mixinClassName)
         || SODIUM_FLUID_LIGHT_MIXIN.equals(mixinClassName)) {
         return TellusRenderCompatibility.sodiumStagingSupported();
      }
      return true;
   }

   @Override
   public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }

   @Override
   public List<String> getMixins() {
      return null;
   }

   @Override
   public void preApply(String targetClassName, ClassNode targetClass,
      String mixinClassName, IMixinInfo mixinInfo) { }

   @Override
   public void postApply(String targetClassName, ClassNode targetClass,
      String mixinClassName, IMixinInfo mixinInfo) { }
}
