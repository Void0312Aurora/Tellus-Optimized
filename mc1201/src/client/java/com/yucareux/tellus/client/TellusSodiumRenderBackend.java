package com.yucareux.tellus.client;

/** Sodium-owned operations used by Tellus without linking the transition flow to Sodium internals. */
public interface TellusSodiumRenderBackend {
   Object tellus$createStagedRenderer(int centerChunkX, int centerChunkZ, int radius,
      int shadingQuarterTurns);

   void tellus$tickStagedRenderer(Object stagedRenderer);

   boolean tellus$stagedRendererReady(Object stagedRenderer);

   int tellus$stagedRendererSectionCount(Object stagedRenderer);

   void tellus$commitStagedRenderer(Object stagedRenderer, int centerChunkX, int centerChunkZ,
      int renderRadius);

   void tellus$discardStagedRenderer(Object stagedRenderer);

   void tellus$updateCachedRendererPosition(double x, double z);

   void tellus$discardCachedRenderer();
}
