package com.yucareux.tellus.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;

/** Keeps biome resolver signatures outside the shared geographic biome logic. */
public abstract class TellusBiomeSourceBridge extends BiomeSource {
   /** Supplies the target-independent map codec; version bridges expose the API-specific view. */
   protected abstract MapCodec<? extends BiomeSource> tellusBiomeSourceMapCodec();

   @FunctionalInterface
   protected interface ColumnResolver {
      Holder<Biome> resolve(int x, int y, int z);
   }

   protected BiomeResolver chunkResolver(ColumnResolver resolver) {
      return (x, y, z, sampler) -> resolver.resolve(x, y, z);
   }
}
