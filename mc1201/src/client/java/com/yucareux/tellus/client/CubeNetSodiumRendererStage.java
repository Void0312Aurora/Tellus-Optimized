package com.yucareux.tellus.client;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;

/** Optional native state; kept outside the mixin package for normal class loading. */
public final class CubeNetSodiumRendererStage {
   public final RenderSectionManager manager;
   public final int expectedSections;
   public final int centerChunkX;
   public final int centerChunkZ;
   public final int renderRadius;
   public final int shadingQuarterTurns;
   public final int readyRadius;
   public boolean consumed;

   public CubeNetSodiumRendererStage(RenderSectionManager manager, int expectedSections,
      int centerChunkX, int centerChunkZ, int renderRadius, int shadingQuarterTurns, int readyRadius) {
      this.manager = manager;
      this.expectedSections = expectedSections;
      this.centerChunkX = centerChunkX;
      this.centerChunkZ = centerChunkZ;
      this.renderRadius = renderRadius;
      this.shadingQuarterTurns = shadingQuarterTurns;
      this.readyRadius = readyRadius;
   }

   public boolean matches(int centerChunkX, int centerChunkZ, int renderRadius, int shadingQuarterTurns) {
      return this.renderRadius == renderRadius
         && this.shadingQuarterTurns == shadingQuarterTurns
         && Math.abs((long)this.centerChunkX - centerChunkX) <= 2
         && Math.abs((long)this.centerChunkZ - centerChunkZ) <= 2;
   }
}
