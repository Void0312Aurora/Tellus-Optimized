package com.yucareux.tellus.worldgen;

/** Horizontal mesh, neighbor-data and source-apron bounds for one handoff. */
public record CubeNetViewCoverage(int viewRadius, int dataRadius, int requiredApronChunks) {
   public static CubeNetViewCoverage forDistances(int requestedView, int serverView,
      int simulationDistance, int rebaseDepth) {
      if (requestedView < 2 || requestedView > 32 || serverView < 2 || serverView > 32
         || simulationDistance < 2 || simulationDistance > 32 || rebaseDepth < 0) {
         throw new IllegalArgumentException("invalid cube-net view bounds");
      }
      int view = Math.min(requestedView, serverView);
      // The threshold can lie anywhere in its chunk. Include its far edge,
      // the full view/simulation radius, and a neighbor chunk for mesh/light.
      int apron = rebaseDepth / 16 + Math.max(view, simulationDistance) + 2;
      return new CubeNetViewCoverage(view, view + 1, apron);
   }
}
