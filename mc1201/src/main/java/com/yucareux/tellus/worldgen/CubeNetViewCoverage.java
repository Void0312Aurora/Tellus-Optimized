package com.yucareux.tellus.worldgen;

/** Horizontal mesh, neighbor-data and source-apron bounds for one handoff. */
public record CubeNetViewCoverage(int viewRadius, int dataRadius, int requiredApronChunks) {
   public static final int MIN_DISTANCE = 2;
   public static final int MAX_DISTANCE = 32;
   public static final int DEFAULT_APRON_CHUNKS = 5;

   public static CubeNetViewCoverage forDistances(int requestedView, int serverView,
      int simulationDistance, int rebaseDepth) {
      if (requestedView < MIN_DISTANCE || requestedView > MAX_DISTANCE
         || serverView < MIN_DISTANCE || serverView > MAX_DISTANCE
         || simulationDistance < MIN_DISTANCE || simulationDistance > MAX_DISTANCE || rebaseDepth < 0) {
         throw new IllegalArgumentException("invalid cube-net view bounds");
      }
      int view = Math.min(requestedView, serverView);
      // The threshold can lie anywhere in its chunk. Include its far edge,
      // the full view/simulation radius, and a neighbor chunk for mesh/light.
      int apron = rebaseDepth / 16 + Math.max(view, simulationDistance) + 2;
      return new CubeNetViewCoverage(view, view + 1, apron);
   }
}
