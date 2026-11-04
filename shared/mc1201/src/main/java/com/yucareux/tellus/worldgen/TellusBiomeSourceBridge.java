package com.yucareux.tellus.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;

/** 1.20.x bridge for the Codec-returning BiomeSource API. */
public abstract class TellusBiomeSourceBridge extends BiomeSource {
   @FunctionalInterface
   protected interface ColumnResolver {
      Holder<Biome> resolve(int x, int y, int z);
   }

   @Override
   protected Codec<? extends BiomeSource> codec() {
      return tellusBiomeSourceMapCodec().codec();
   }

   protected abstract MapCodec<? extends BiomeSource> tellusBiomeSourceMapCodec();

   protected BiomeResolver chunkResolver(ColumnResolver resolver) {
      return (x, y, z, sampler) -> resolver.resolve(x, y, z);
   }
}
