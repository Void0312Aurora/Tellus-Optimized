package com.yucareux.tellus.integration.distant_horizons;

import net.minecraft.world.level.chunk.ChunkGenerator;

/** Shared generator classification that can be tested without loading the DH API. */
final class DistantHorizonsGeneratorSupport {
   static final String CUBE_NET_GENERATOR_CLASS = "com.yucareux.tellus.worldgen.CubeNetChunkGenerator";

   private DistantHorizonsGeneratorSupport() {
   }

   static boolean isCubeNetGenerator(ChunkGenerator generator) {
      return generator != null && isCubeNetGeneratorClassName(generator.getClass().getName());
   }

   static boolean isCubeNetGeneratorClassName(String className) {
      return CUBE_NET_GENERATOR_CLASS.equals(className);
   }
}
