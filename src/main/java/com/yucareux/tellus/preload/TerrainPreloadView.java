package com.yucareux.tellus.preload;

/**
 * Narrow read-only view of terrain preload coverage, independent of the Earth
 * generator settings object. Coordinates use the planar Earth block grid:
 * longitude maps to X and the configured Earth projection maps latitude to Z.
 */
public interface TerrainPreloadView {
   TerrainPreloadPackage.Sample sample(int blockX, int blockZ);

   TerrainPreloadPackage.Sample sample(int blockX, int blockZ, double requestedResolutionMeters);

   default boolean contains(int blockX, int blockZ) {
      return this.contains(blockX, blockZ, Double.POSITIVE_INFINITY);
   }

   boolean contains(int blockX, int blockZ, double requestedResolutionMeters);
}
