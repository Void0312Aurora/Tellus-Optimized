package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.runtime.CubeNetGeneratorSettingsViewAdapter;
import java.util.Objects;

/**
 * 1.21.1 typed Cube-Net source accessor. It intentionally lives in the
 * generator package to read the package-private biome-source settings method.
 */
public final class CubeNetGeneratorSettingsViewFactory {
   private static final String SOURCE_LAYER = "mc1211";
   private CubeNetGeneratorSettingsViewFactory() { }
   public static String sourceLayer() { return SOURCE_LAYER; }

   public static CubeNetBiomeSource.Settings settingsFor(CubeNetChunkGenerator generator) {
      Objects.requireNonNull(generator, "generator");
      return generator.getBiomeSource() instanceof CubeNetBiomeSource source
         ? source.settings()
         : CubeNetBiomeSource.Settings.DEFAULT;
   }

   public static CubeNetBiomeSource.Settings requireMatchingSettings(
      CubeNetChunkGenerator generator, CubeNetBiomeSource.Settings supplied
   ) {
      Objects.requireNonNull(supplied, "settings");
      CubeNetBiomeSource.Settings actual = settingsFor(generator);
      if (!actual.equals(supplied)) {
         throw new IllegalArgumentException("Cube-Net settings do not match the generator biome source");
      }
      return supplied;
   }

   public static CubeNetGeneratorSettingsViewAdapter viewFor(CubeNetChunkGenerator generator) {
      return new CubeNetGeneratorSettingsViewAdapter(generator, settingsFor(generator));
   }
}
