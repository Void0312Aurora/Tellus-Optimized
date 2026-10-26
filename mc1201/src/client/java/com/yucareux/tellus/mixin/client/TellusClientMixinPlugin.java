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

   @Override
   public void onLoad(String mixinPackage) { }

   @Override
   public String getRefMapperConfig() {
      return null;
   }

   @Override
   public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
      if (TellusRenderCompatibility.vanillaStagedMixinsEnabled()) return true;
      return !VANILLA_RENDERER_MIXIN.equals(mixinClassName)
         && !VANILLA_REBUILD_MIXIN.equals(mixinClassName);
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
