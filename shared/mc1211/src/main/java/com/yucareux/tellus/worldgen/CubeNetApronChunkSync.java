package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetSeamTransform;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;

/**
 * 1.21.1 server-generation geometry bridge.
 *
 * <p>The full apron edit/sync lifecycle remains a later target-specific step.
 * The generator only needs these pure coordinate operations while producing a
 * server chunk, so keeping them in this version layer avoids pulling the 1.20.1
 * event/ticket implementation into the initial 1.21.1 compile.</p>
 */
public final class CubeNetApronChunkSync {
   private CubeNetApronChunkSync() { }

   public static ChunkPos canonicalChunkPosition(CubeNetLayout layout,
      CubeNetApronResolver.ChunkProjection projection) {
      int targetLocalX = projection.targetChunkX() * 16 + 8;
      int targetLocalZ = projection.targetChunkZ() * 16 + 8;
      CubeNetLayout.PlanarPoint targetCenter = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(
         projection.targetFace(), targetLocalX + 0.5, targetLocalZ + 0.5));
      return new ChunkPos(
         Math.floorDiv((int)Math.floor(targetCenter.x()), 16),
         Math.floorDiv((int)Math.floor(targetCenter.z()), 16));
   }

   public static Rotation rotation(int clockwiseQuarterTurns) {
      return switch (Math.floorMod(clockwiseQuarterTurns, 4)) {
         case 0 -> Rotation.NONE;
         case 1 -> Rotation.CLOCKWISE_90;
         case 2 -> Rotation.CLOCKWISE_180;
         default -> Rotation.COUNTERCLOCKWISE_90;
      };
   }
}
