package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.runtime.CubeNetGeneratorSettingsViewAdapter;
import java.util.Objects;

/**
 * Typed Cube-Net settings accessor kept beside CubeNetBiomeSource so it can
 * read the source's package-private settings accessor without reflection.
 * A non-CubeNet biome source preserves the generator's existing DEFAULT path.
 */
public final class CubeNetGeneratorSettingsViewFactory {
   private CubeNetGeneratorSettingsViewFactory() { }

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
