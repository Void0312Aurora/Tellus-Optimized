package com.yucareux.tellus.worldgen.global;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic owner selection for cave state at a physical face seam. */
public final class CubeNetCaveSeamPolicy {
   private CubeNetCaveSeamPolicy() { }

   public static Optional<Projection> forChunk(
      SphereWorldModel model, CubeNetLayout layout, SphereWorldModel.SphereChunkAddress source
   ) {
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(layout, "layout");
      Objects.requireNonNull(source, "source");
      if (layout.faceSize() != model.config().faceBlocks()) return Optional.empty();
      List<Projection> candidates = new ArrayList<>(2);
      for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(CubeNetLayout.Face.of(source.face()), side)
            || !touches(source, side, layout.faceSize())) continue;
         Projection projection = projectionForSide(layout, source, side);
         if (projection != null) candidates.add(projection);
      }
      return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
   }

   private static Projection projectionForSide(
      CubeNetLayout layout, SphereWorldModel.SphereChunkAddress source, CubeNetLayout.Side side
   ) {
      CubeNetLayout.Face sourceFace = CubeNetLayout.Face.of(source.face());
      int size = layout.faceSize();
      int sourceMinX = Math.toIntExact(source.blockX());
      int sourceMinZ = Math.toIntExact(source.blockZ());
      CubeNetLayout.Face targetFace = null;
      int targetChunkX = Integer.MIN_VALUE;
      int targetChunkZ = Integer.MIN_VALUE;
      for (int localZ : new int[]{0, 15}) for (int localX : new int[]{0, 15}) {
         CubeNetLayout.FacePoint target = targetPoint(layout, sourceFace, side,
            sourceMinX + localX + 0.5, sourceMinZ + localZ + 0.5, size);
         int chunkX = Math.floorDiv((int)Math.floor(target.u()), 16);
         int chunkZ = Math.floorDiv((int)Math.floor(target.v()), 16);
         if (targetFace == null) {
            targetFace = target.face();
            targetChunkX = chunkX;
            targetChunkZ = chunkZ;
         } else if (target.face() != targetFace || chunkX != targetChunkX || chunkZ != targetChunkZ) {
            return null;
         }
      }
      if (targetFace == null) return null;
      return new Projection(source, side, targetFace, targetChunkX, targetChunkZ);
   }

   private static boolean touches(SphereWorldModel.SphereChunkAddress source, CubeNetLayout.Side side, int size) {
      long blockX = source.blockX(), blockZ = source.blockZ();
      return switch (side) {
         case WEST -> blockX == 0;
         case EAST -> blockX + 16L == size;
         case NORTH -> blockZ == 0;
         case SOUTH -> blockZ + 16L == size;
      };
   }

   private static CubeNetLayout.FacePoint targetPoint(
      CubeNetLayout layout, CubeNetLayout.Face sourceFace, CubeNetLayout.Side side,
      double sourceX, double sourceZ, int size
   ) {
      double edge = side == CubeNetLayout.Side.WEST || side == CubeNetLayout.Side.EAST ? sourceZ : sourceX;
      double penetration = switch (side) {
         case WEST -> sourceX;
         case EAST -> size - sourceX;
         case NORTH -> sourceZ;
         case SOUTH -> size - sourceZ;
      };
      return layout.mapAcross(sourceFace, side, edge, penetration);
   }

   public record Projection(
      SphereWorldModel.SphereChunkAddress source,
      CubeNetLayout.Side sourceSide,
      CubeNetLayout.Face targetFace,
      int targetChunkX,
      int targetChunkZ
   ) {
      public Projection {
         Objects.requireNonNull(source, "source");
         Objects.requireNonNull(sourceSide, "sourceSide");
         Objects.requireNonNull(targetFace, "targetFace");
      }

      public boolean requiresOwnerProjection() {
         return this.source.face() > this.targetFace.index();
      }

      public CubeNetLayout.FacePoint targetPoint(CubeNetLayout layout, int localX, int localZ) {
         int sourceX = Math.toIntExact(this.source.blockX()) + localX;
         int sourceZ = Math.toIntExact(this.source.blockZ()) + localZ;
         return CubeNetCaveSeamPolicy.targetPoint(layout, CubeNetLayout.Face.of(this.source.face()),
            this.sourceSide, sourceX + 0.5, sourceZ + 0.5, layout.faceSize());
      }
   }
}
