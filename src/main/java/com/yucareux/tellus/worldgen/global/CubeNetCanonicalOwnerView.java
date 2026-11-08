package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/**
 * Canonical Cube-Net owner plus the face-local and planar coordinates used to
 * render that owner in the fixed net.
 */
public record CubeNetCanonicalOwnerView(
   SphereWorldModel.SphereBlockAddress block,
   SphereWorldModel.SphereChunkAddress chunk,
   CubeNetLayout.FacePoint facePoint,
   CubeNetLayout.PlanarPoint planarPoint
) implements GeneratorOwnerView {
   public CubeNetCanonicalOwnerView {
      Objects.requireNonNull(block, "block");
      Objects.requireNonNull(chunk, "chunk");
      Objects.requireNonNull(facePoint, "facePoint");
      Objects.requireNonNull(planarPoint, "planarPoint");
      if (block.face() < 0 || block.face() >= SphereWorldModel.FACE_COUNT
         || chunk.face() < 0 || chunk.face() >= SphereWorldModel.FACE_COUNT) {
         throw new IllegalArgumentException("face must be in [0, 5]");
      }
      if (block.face() != chunk.face()
         || Math.floorDiv(block.blockX(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS) != chunk.chunkX()
         || Math.floorDiv(block.blockZ(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS) != chunk.chunkZ()) {
         throw new IllegalArgumentException("block and chunk addresses do not agree");
      }
      if (facePoint.face().index() != block.face()
         || facePoint.u() < 0.0 || facePoint.v() < 0.0) {
         throw new IllegalArgumentException("face point does not identify the canonical block");
      }
   }

   @Override
   public GeneratorOwner kind() {
      return GeneratorOwner.CUBE_NET_CANONICAL;
   }
}
