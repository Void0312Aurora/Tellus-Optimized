package com.yucareux.tellus.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;

/** 1.21.x and 26.x bridge for the MapCodec-returning BiomeSource API. */
public abstract class TellusBiomeSourceBridge extends BiomeSource {
   @FunctionalInterface
   protected interface ColumnResolver {
      Holder<Biome> resolve(int x, int y, int z);
   }

   @Override
   protected MapCodec<? extends BiomeSource> codec() {
      return tellusBiomeSourceMapCodec();
   }

   protected abstract MapCodec<? extends BiomeSource> tellusBiomeSourceMapCodec();

   protected BiomeResolver chunkResolver(ColumnResolver resolver) {
      return (x, y, z, sampler) -> resolver.resolve(x, y, z);
   }
}
