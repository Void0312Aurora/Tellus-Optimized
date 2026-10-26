package com.yucareux.tellus.worldgen.global;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One directed physical seam expressed as a projection into the source face's
 * planar chart.
 *
 * <p>The canonical block remains on {@link #seam()}'s target face. The
 * projection is a derived apron position: it is the position at
 * which that target block must be rendered while the player is still on the
 * source face. Keeping this calculation here prevents movement, packet and
 * rendering code from implementing subtly different seam transforms.</p>
 */
public final class CubeNetSeamTransform {
   private final CubeNetLayout layout;
   private final CubeNetLayout.Seam seam;

   public CubeNetSeamTransform(CubeNetLayout layout, CubeNetLayout.Seam seam) {
      this.layout = Objects.requireNonNull(layout, "layout");
      this.seam = Objects.requireNonNull(seam, "seam");
      if (!layout.seam(seam.source(), seam.sourceSide()).equals(seam)) {
         throw new IllegalArgumentException("seam does not belong to layout");
      }
   }

   public CubeNetLayout layout() { return this.layout; }
   public CubeNetLayout.Seam seam() { return this.seam; }

   /** Maps a point on the target face into the source face's apron chart. */
   public CubeNetLayout.PlanarPoint targetToSourcePlanar(CubeNetLayout.FacePoint target) {
      Objects.requireNonNull(target, "target");
      if (target.face() != this.seam.target()) {
         throw new IllegalArgumentException("point is not on the seam target face");
      }
      double edge = sideAlong(target, this.seam.targetSide());
      CubeNetLayout.FacePoint targetEdge = edgePoint(
         this.seam.target(), this.seam.targetSide(), edge
      );
      CubeNetLayout.FacePoint sourceEdge = this.layout.mapAcross(
         this.seam.target(), this.seam.targetSide(), edge, 0.0
      );
      double[] delta = this.layout.transportVector(
         this.seam.target(), this.seam.targetSide(),
         target.u() - targetEdge.u(), target.v() - targetEdge.v()
      );
      CubeNetLayout.FacePoint source = new CubeNetLayout.FacePoint(
         this.seam.source(), sourceEdge.u() + delta[0], sourceEdge.v() + delta[1]
      );
      return this.layout.toPlanarUnbounded(source);
   }

   /** Maps a target-face block center into the source chart. */
   public CubeNetLayout.PlanarPoint targetBlockToSourcePlanar(int targetBlockX, int targetBlockZ) {
      return targetToSourcePlanar(new CubeNetLayout.FacePoint(
         this.seam.target(), targetBlockX + 0.5, targetBlockZ + 0.5
      ));
   }

   /** Inverse affine map; it preserves the exact fractional position at rebase. */
   public CubeNetLayout.FacePoint sourcePlanarToTarget(CubeNetLayout.PlanarPoint source) {
      Objects.requireNonNull(source, "source");
      CubeNetLayout.PlanarPoint origin = targetToSourcePlanar(
         new CubeNetLayout.FacePoint(this.seam.target(), 0.0, 0.0));
      double[] targetEngine = targetToSourceGridTransform().inverse()
         .apply(source.x() - origin.x(), source.z() - origin.z());
      double[] targetLocal = this.layout.localVector(this.seam.target(), targetEngine[0], targetEngine[1]);
      return new CubeNetLayout.FacePoint(this.seam.target(), targetLocal[0], targetLocal[1]);
   }

   /**
    * Maps a target-face chunk to the source chart. A valid cube-net layout
    * keeps all four chunk corners in one proxy chunk when the face size is a
    * multiple of 16. A present result is therefore safe to use as a normal
    * client ChunkPos projection; an empty result means the chunk straddles a
    * chart boundary and must be split before it can be sent.
    */
   public Optional<ChunkProjection> targetChunkToSourceChunk(int targetChunkX, int targetChunkZ) {
      int[] xs = {targetChunkX * 16, targetChunkX * 16 + 15};
      int[] zs = {targetChunkZ * 16, targetChunkZ * 16 + 15};
      int proxyChunkX = Integer.MIN_VALUE;
      int proxyChunkZ = Integer.MIN_VALUE;
      for (int x : xs) for (int z : zs) {
         CubeNetLayout.PlanarPoint proxy = targetBlockToSourcePlanar(x, z);
         int cx = Math.floorDiv((int)Math.floor(proxy.x()), 16);
         int cz = Math.floorDiv((int)Math.floor(proxy.z()), 16);
         if (proxyChunkX == Integer.MIN_VALUE) {
            proxyChunkX = cx;
            proxyChunkZ = cz;
         } else if (proxyChunkX != cx || proxyChunkZ != cz) {
            return Optional.empty();
         }
      }
      return Optional.of(new ChunkProjection(
         this.seam.target(), this.seam.source(), targetChunkX, targetChunkZ,
         proxyChunkX, proxyChunkZ, targetToSourceGridTransform()
      ));
   }

   /** Maps a target-face local tangent vector into source planar X/Z. */
   public double[] targetToSourcePlanarVector(double deltaU, double deltaV) {
      double[] sourceLocal = this.layout.transportVector(
         this.seam.target(), this.seam.targetSide(), deltaU, deltaV
      );
      return this.layout.planarVector(this.seam.source(), sourceLocal[0], sourceLocal[1]);
   }

   /** Maps a target engine-chart X/Z vector into the source engine chart. */
   public double[] targetEngineToSourceEngineVector(double deltaX, double deltaZ) {
      double[] targetLocal = this.layout.localVector(this.seam.target(), deltaX, deltaZ);
      return targetToSourcePlanarVector(targetLocal[0], targetLocal[1]);
   }

   /** Returns the signed permutation that maps target engine X/Z to source X/Z. */
   public GridTransform targetToSourceGridTransform() {
      double[] east = targetEngineToSourceEngineVector(1.0, 0.0);
      double[] south = targetEngineToSourceEngineVector(0.0, 1.0);
      int m00 = gridComponent(east[0]), m10 = gridComponent(east[1]);
      int m01 = gridComponent(south[0]), m11 = gridComponent(south[1]);
      int determinant = m00 * m11 - m01 * m10;
      if (determinant != 1
         || (m00 == 0 && m10 == 0) || (m00 != 0 && m10 != 0)
         || (m01 == 0 && m11 == 0) || (m01 != 0 && m11 != 0)) {
         throw new IllegalStateException("seam projection must be a grid rotation: "
            + this.seam);
      }
      return new GridTransform(m00, m01, m10, m11, determinant);
   }

   /**
    * Returns the rotation in the oriented planar atlas. A reflection is an
    * invalid atlas, not a reason to mirror block states.
    */
   public OptionalInt targetToSourceClockwiseQuarterTurns() {
      GridTransform transform = targetToSourceGridTransform();
      if (transform.m00() == 1 && transform.m10() == 0) return OptionalInt.of(0);
      if (transform.m00() == 0 && transform.m10() == 1) return OptionalInt.of(1);
      if (transform.m00() == -1 && transform.m10() == 0) return OptionalInt.of(2);
      if (transform.m00() == 0 && transform.m10() == -1) return OptionalInt.of(3);
      throw new IllegalStateException("grid rotation is not a quarter-turn");
   }

   private CubeNetLayout.FacePoint edgePoint(CubeNetLayout.Face face,
      CubeNetLayout.Side side, double edge) {
      double size = this.layout.faceSize();
      double u = switch (side) {
         case WEST -> 0.0;
         case EAST -> size;
         case NORTH, SOUTH -> edge;
      };
      double v = switch (side) {
         case NORTH -> 0.0;
         case SOUTH -> size;
         case WEST, EAST -> edge;
      };
      return new CubeNetLayout.FacePoint(face, u, v);
   }

   private static double sideAlong(CubeNetLayout.FacePoint point, CubeNetLayout.Side side) {
      return switch (side) {
         case WEST, EAST -> point.v();
         case NORTH, SOUTH -> point.u();
      };
   }

   private static int gridComponent(double value) {
      if (Math.abs(value) < 1.0E-9) return 0;
      if (Math.abs(value - 1.0) < 1.0E-9) return 1;
      if (Math.abs(value + 1.0) < 1.0E-9) return -1;
      throw new IllegalStateException("seam transform is not grid-aligned: " + value);
   }

   public record ChunkProjection(CubeNetLayout.Face targetFace, CubeNetLayout.Face sourceFace,
      int targetChunkX, int targetChunkZ, int sourceChunkX, int sourceChunkZ,
      GridTransform gridTransform) {
      public ChunkProjection {
         Objects.requireNonNull(targetFace, "targetFace");
         Objects.requireNonNull(sourceFace, "sourceFace");
         Objects.requireNonNull(gridTransform, "gridTransform");
      }
   }

   /** Integer quarter-turn rotation in engine X/Z coordinates. */
   public record GridTransform(int m00, int m01, int m10, int m11, int determinant) {
      public GridTransform {
         if (determinant != 1 || m00 * m11 - m01 * m10 != determinant
            || m00 * m00 + m10 * m10 != 1 || m01 * m01 + m11 * m11 != 1
            || m00 * m01 + m10 * m11 != 0) {
            throw new IllegalArgumentException("invalid grid transform");
         }
      }

      public double[] apply(double x, double z) {
         return new double[]{m00 * x + m01 * z, m10 * x + m11 * z};
      }

      public GridTransform inverse() {
         return new GridTransform(this.m00, this.m10, this.m01, this.m11, 1);
      }
   }
}
