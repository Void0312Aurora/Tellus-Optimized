package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/** Ownership view for one canonical spherical face address. */
public record SphericalOwnerView(
   SphereWorldModel.SphereBlockAddress block,
   SphereWorldModel.SphereChunkAddress chunk
) implements GeneratorOwnerView {
   public SphericalOwnerView {
      Objects.requireNonNull(block, "block");
      Objects.requireNonNull(chunk, "chunk");
      if (block.face() < 0 || block.face() >= SphereWorldModel.FACE_COUNT
         || chunk.face() < 0 || chunk.face() >= SphereWorldModel.FACE_COUNT) {
         throw new IllegalArgumentException("face must be in [0, 5]");
      }
      if (block.face() != chunk.face()
         || Math.floorDiv(block.blockX(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS) != chunk.chunkX()
         || Math.floorDiv(block.blockZ(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS) != chunk.chunkZ()) {
         throw new IllegalArgumentException("block and chunk addresses do not agree");
      }
   }

   @Override
   public GeneratorOwner kind() {
      return GeneratorOwner.SPHERE_FACE;
   }
}
