package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetCaveSeamPolicy;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubeNetCaveSeamPolicyTest {
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1000.0);
   private static final CubeNetLayout LAYOUT = CubeNetLayout.standard(MODEL.config().faceBlocks());

   @Test
   void everyCutSeamHasOneCanonicalOwnerForItsInteriorEdgeChunks() {
      int chunks = MODEL.config().faceBlocks() / 16;
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
            if (LAYOUT.arePlanarNeighbors(face, side)) continue;
            long along = chunks / 2L;
            long chunkX = side == CubeNetLayout.Side.WEST ? 0
               : side == CubeNetLayout.Side.EAST ? chunks - 1 : along;
            long chunkZ = side == CubeNetLayout.Side.NORTH ? 0
               : side == CubeNetLayout.Side.SOUTH ? chunks - 1 : along;
            var source = new SphereWorldModel.SphereChunkAddress(face.index(), chunkX, chunkZ);
            var projection = CubeNetCaveSeamPolicy.forChunk(MODEL, LAYOUT, source).orElse(null);
            assertNotNull(projection, () -> face + " " + side);
            assertEquals(face, CubeNetLayout.Face.of(source.face()));
            for (int localZ : new int[]{0, 15}) for (int localX : new int[]{0, 15}) {
               var target = projection.targetPoint(LAYOUT, localX, localZ);
               assertEquals(projection.targetFace(), target.face());
               assertTrue(target.u() >= 0.0 && target.u() <= LAYOUT.faceSize());
               assertTrue(target.v() >= 0.0 && target.v() <= LAYOUT.faceSize());
            }
            var targetAddress = new SphereWorldModel.SphereChunkAddress(
               projection.targetFace().index(), projection.targetChunkX(), projection.targetChunkZ());
            var reverse = CubeNetCaveSeamPolicy.forChunk(MODEL, LAYOUT, targetAddress).orElse(null);
            assertNotNull(reverse, () -> "reverse " + face + " " + side);
            assertEquals(!projection.requiresOwnerProjection(), reverse.requiresOwnerProjection());
         }
      }
   }

   @Test
   void ownerChoiceIsStableAndDoesNotProjectBothSides() {
      var source = new SphereWorldModel.SphereChunkAddress(5, 0, MODEL.config().faceBlocks() / 16L / 2L);
      var projection = CubeNetCaveSeamPolicy.forChunk(MODEL, LAYOUT, source).orElseThrow();
      assertTrue(projection.requiresOwnerProjection());
      var target = new SphereWorldModel.SphereChunkAddress(projection.targetFace().index(),
         projection.targetChunkX(), projection.targetChunkZ());
      var reverse = CubeNetCaveSeamPolicy.forChunk(MODEL, LAYOUT, target).orElse(null);
      if (reverse != null) assertTrue(!reverse.requiresOwnerProjection());
   }
}
