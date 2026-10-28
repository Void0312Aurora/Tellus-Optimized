package com.yucareux.tellus.worldgen;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

/** Optional terrain preview bridge; loading it does not require the DH API. */
public interface CubeNetLodSurface {
   int minY();
   int maxY();

   /**
    * Sample a square of block cells. Empty means outside the supported chart;
    * missing source data must throw so DH retries rather than caching air.
    */
   Optional<List<Layer>> sampleLodColumn(long minBlockX, long minBlockZ, int cellSize);

   /** Whether one DH request may represent this whole block square at once. */
   boolean isUniformArea(long minX, long minZ, long width);

   /** Absolute Y bounds, bottom inclusive and top exclusive. */
   record Layer(int bottom, int top, BlockState state) {
      public Layer {
         if (top <= bottom || state == null) throw new IllegalArgumentException("invalid LOD layer");
      }
   }
}
