package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/**
 * A derived cut-edge view. All authoritative data remains on
 * {@link #canonicalOwner()}; the proxy and seam projections exist only for
 * planar transport, rendering and synchronization.
 */
public record CubeNetApronOwnerView(
   CubeNetLayout.PlanarPoint proxyPoint,
   CubeNetApronResolver.ApronAddress blockProjection,
   CubeNetApronResolver.ChunkProjection chunkProjection,
   CubeNetCanonicalOwnerView canonicalOwner
) implements GeneratorOwnerView {
   public CubeNetApronOwnerView {
      Objects.requireNonNull(proxyPoint, "proxyPoint");
      Objects.requireNonNull(blockProjection, "blockProjection");
      Objects.requireNonNull(chunkProjection, "chunkProjection");
      Objects.requireNonNull(canonicalOwner, "canonicalOwner");
      if (blockProjection.targetFace().index() != canonicalOwner.block().face()
         || blockProjection.blockX() != canonicalOwner.block().blockX()
         || blockProjection.blockZ() != canonicalOwner.block().blockZ()) {
         throw new IllegalArgumentException("apron block projection does not identify canonical owner");
      }
      if (chunkProjection.targetFace().index() != canonicalOwner.chunk().face()
         || chunkProjection.targetChunkX() != canonicalOwner.chunk().chunkX()
         || chunkProjection.targetChunkZ() != canonicalOwner.chunk().chunkZ()) {
         throw new IllegalArgumentException("apron chunk projection does not identify canonical owner");
      }
      if (Math.floorDiv((long)blockProjection.blockX(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS)
            != canonicalOwner.chunk().chunkX()
         || Math.floorDiv((long)blockProjection.blockZ(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS)
            != canonicalOwner.chunk().chunkZ()) {
         throw new IllegalArgumentException("apron block and chunk projections do not agree");
      }
   }

   @Override
   public GeneratorOwner kind() {
      return GeneratorOwner.CUBE_NET_APRON;
   }
}
