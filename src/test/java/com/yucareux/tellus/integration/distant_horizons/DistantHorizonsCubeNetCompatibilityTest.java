package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DistantHorizonsCubeNetCompatibilityTest {
   @Test
   void recognizesOnlyTellusCubeNetGenerator() {
      assertTrue(DistantHorizonsGeneratorSupport.isCubeNetGeneratorClassName(
         "com.yucareux.tellus.worldgen.CubeNetChunkGenerator"
      ));
      assertFalse(DistantHorizonsGeneratorSupport.isCubeNetGeneratorClassName(
         "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator"
      ));
      assertFalse(DistantHorizonsGeneratorSupport.isCubeNetGeneratorClassName(
         "com.yucareux.tellus.worldgen.CubeNetChunkGenerator$Proxy"
      ));
   }
}
