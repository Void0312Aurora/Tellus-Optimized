package com.yucareux.tellus.client;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;

/** Optional native state; kept outside the mixin package for normal class loading. */
public final class CubeNetSodiumRendererStage {
   public final RenderSectionManager manager;
   public final int expectedSections;
   public boolean consumed;

   public CubeNetSodiumRendererStage(RenderSectionManager manager, int expectedSections) {
      this.manager = manager;
      this.expectedSections = expectedSections;
   }
}
