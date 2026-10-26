package com.yucareux.tellus.worldgen.global;

import java.util.Objects;
import java.util.Optional;

/**
 * Bridges the canonical six-face block address to one planar cube-net view.
 * Minecraft coordinates are deliberately absent here: this adapter defines
 * the physical address relation that a Level/ChunkPos compatibility layer can
 * later materialize.
 */
public final class CubeNetAddressAdapter {
   private final SphereWorldModel model;
   private final CubeNetLayout layout;

   public CubeNetAddressAdapter(SphereWorldModel model) {
      this.model = Objects.requireNonNull(model, "model");
      if (model.config().faceBlocks() > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("face is too large for the integer cube-net view");
      }
      this.layout = new CubeNetLayout(model.config().faceBlocks());
   }

   public SphereWorldModel model() { return this.model; }
   public CubeNetLayout layout() { return this.layout; }

   /** Returns the center coordinate used by the planar materialization. */
   public CubeNetLayout.PlanarPoint planarCenter(SphereWorldModel.SphereBlockAddress address) {
      CubeNetLayout.FacePoint point = facePoint(address);
      return this.layout.toPlanar(point);
   }

   /** Returns the canonical continuous face coordinate of a block center. */
   public CubeNetLayout.FacePoint facePoint(SphereWorldModel.SphereBlockAddress address) {
      Objects.requireNonNull(address, "address");
      this.model.chartForBlock(address.face(), address.blockX(), address.blockZ());
      return new CubeNetLayout.FacePoint(CubeNetLayout.Face.of(address.face()),
         address.blockX() + 0.5, address.blockZ() + 0.5);
   }

   /**
    * Resolves a planar engine chunk only when all four block-cell corners
    * belong to one face. A chunk that straddles a net cut is left out of the
    * ordinary generator path and must be split by the seam runtime.
    */
   public Optional<SphereWorldModel.SphereChunkAddress> chunkAddress(int chunkX, int chunkZ) {
      CubeNetLayout.Face owner = null;
      double localU = 0.0, localV = 0.0;
      for (double dx : new double[]{0.5, 15.5}) {
         for (double dz : new double[]{0.5, 15.5}) {
            CubeNetLayout.FacePoint point;
            try {
               point = this.layout.fromPlanar(new CubeNetLayout.PlanarPoint(
                  chunkX * 16.0 + dx, chunkZ * 16.0 + dz));
            } catch (IllegalArgumentException ignored) {
               return Optional.empty();
            }
            if (owner == null) {
               owner = point.face();
               localU = point.u();
               localV = point.v();
            } else if (point.face() != owner) {
               return Optional.empty();
            }
         }
      }
      long faceChunkX = Math.floorDiv((long)Math.floor(localU), 16L);
      long faceChunkZ = Math.floorDiv((long)Math.floor(localV), 16L);
      try {
         var address = new SphereWorldModel.SphereChunkAddress(owner.index(), faceChunkX, faceChunkZ);
         this.model.chartForChunk(address);
         return Optional.of(address);
      } catch (IllegalArgumentException ignored) {
         return Optional.empty();
      }
   }

   /**
    * Resolves one cardinal physical block neighbor. At a face edge the target
    * cell is selected by the seam transform, including reversed edge order.
    */
   public Neighbor neighbor(SphereWorldModel.SphereBlockAddress source, int deltaX, int deltaZ) {
      Objects.requireNonNull(source, "source");
      if (Math.abs(deltaX) + Math.abs(deltaZ) != 1) {
         throw new IllegalArgumentException("only cardinal neighbors are supported");
      }
      this.model.chartForBlock(source.face(), source.blockX(), source.blockZ());
      int size = this.model.config().faceBlocks();
      long targetX = source.blockX() + deltaX;
      long targetZ = source.blockZ() + deltaZ;
      if (targetX >= 0L && targetX < size && targetZ >= 0L && targetZ < size) {
         return new Neighbor(new SphereWorldModel.SphereBlockAddress(source.face(), targetX, targetZ),
            false, null);
      }
      CubeNetLayout.Side side = side(deltaX, deltaZ);
      double edge = deltaX != 0 ? source.blockZ() + 0.5 : source.blockX() + 0.5;
      CubeNetLayout.FacePoint target = this.layout.mapAcross(CubeNetLayout.Face.of(source.face()), side, edge, 0.5);
      long mappedX = (long)Math.floor(target.u());
      long mappedZ = (long)Math.floor(target.v());
      SphereWorldModel.SphereBlockAddress address = new SphereWorldModel.SphereBlockAddress(
         target.face().index(), mappedX, mappedZ);
      this.model.chartForBlock(address.face(), address.blockX(), address.blockZ());
      CubeNetLayout.Seam seam = this.layout.seam(CubeNetLayout.Face.of(source.face()), side);
      boolean crossed = !this.layout.arePlanarNeighbors(CubeNetLayout.Face.of(source.face()), side);
      return new Neighbor(address, crossed, crossed ? seam : null);
   }

   private static CubeNetLayout.Side side(int deltaX, int deltaZ) {
      if (deltaX < 0) return CubeNetLayout.Side.WEST;
      if (deltaX > 0) return CubeNetLayout.Side.EAST;
      if (deltaZ < 0) return CubeNetLayout.Side.NORTH;
      return CubeNetLayout.Side.SOUTH;
   }

   public record Neighbor(SphereWorldModel.SphereBlockAddress address,
      boolean crossedSeam, CubeNetLayout.Seam seam) {
      public Neighbor {
         Objects.requireNonNull(address, "address");
         if (!crossedSeam && seam != null) throw new IllegalArgumentException("direct neighbor has seam metadata");
         if (crossedSeam && seam == null) throw new IllegalArgumentException("seam neighbor has no metadata");
      }
   }
}
