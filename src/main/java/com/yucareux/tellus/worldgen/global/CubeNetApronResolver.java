package com.yucareux.tellus.worldgen.global;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a planar point in a cut-edge apron to its canonical face cell.
 *
 * <p>The resolver is deliberately independent from Minecraft chunk classes.
 * It owns the chart-side part of the mirror-band contract: a point in the
 * source face's outside strip is mapped through the directed seam to the
 * target face, where storage and terrain sampling remain canonical.</p>
 */
public final class CubeNetApronResolver {
   private final CubeNetLayout layout;
   private final int width;
   private final List<CubeNetLayout.Seam> cutSeams;

   public CubeNetApronResolver(CubeNetLayout layout, int width) {
      this.layout = Objects.requireNonNull(layout, "layout");
      if (width < 16 || width % 16 != 0 || width > layout.faceSize() || layout.faceSize() % 16 != 0) {
         throw new IllegalArgumentException("face size and apron width must be chunk aligned");
      }
      this.width = width;
      List<CubeNetLayout.Seam> seams = new ArrayList<>();
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (!layout.arePlanarNeighbors(face, side)) seams.add(layout.seam(face, side));
      }
      this.cutSeams = List.copyOf(seams);
   }

   public CubeNetLayout layout() { return this.layout; }
   public int width() { return this.width; }

   /**
    * Resolves a block centre in an apron. A point is rejected when it is not
    * in exactly one directed cut strip; this keeps corner overlap out of the
    * ordinary chunk path until an explicit vertex policy exists.
    */
   public Optional<ApronAddress> resolve(double planarX, double planarZ) {
      if (!Double.isFinite(planarX) || !Double.isFinite(planarZ)) {
         throw new IllegalArgumentException("planar point");
      }
      // Canonical faces always own their planar footprint. An apron must
      // never overwrite an existing face in the fixed net.
      try {
         this.layout.fromPlanar(new CubeNetLayout.PlanarPoint(planarX, planarZ));
         return Optional.empty();
      } catch (IllegalArgumentException outsideCanonicalFaces) { }
      ApronAddress result = null;
      for (CubeNetLayout.Seam seam : this.cutSeams) {
         CubeNetLayout.Face source = seam.source();
         CubeNetLayout.FacePlacement placement = this.layout.placement(source);
         double dx = planarX - (double)placement.originX() * this.layout.faceSize();
         double dz = planarZ - (double)placement.originZ() * this.layout.faceSize();
         double u = placement.uAxisX() * dx + placement.uAxisZ() * dz;
         double v = placement.vAxisX() * dx + placement.vAxisZ() * dz;
            CubeNetLayout.Side side = seam.sourceSide();
            double penetration = penetration(u, v, side);
            if (!(penetration > 0.0 && penetration <= this.width)) continue;
            double edge = side == CubeNetLayout.Side.WEST || side == CubeNetLayout.Side.EAST ? v : u;
            if (!(edge >= 0.0 && edge < this.layout.faceSize())) continue;
            CubeNetLayout.FacePoint target = this.layout.mapAcross(source, side, edge, penetration);
            int blockX = (int)Math.floor(target.u());
            int blockZ = (int)Math.floor(target.v());
            if (blockX < 0 || blockX >= this.layout.faceSize()
               || blockZ < 0 || blockZ >= this.layout.faceSize()) continue;
            ApronAddress candidate = new ApronAddress(source, side, target.face(),
               target.u(), target.v(), blockX, blockZ, edge, penetration);
            if (result != null) return Optional.empty();
            result = candidate;
      }
      return Optional.ofNullable(result);
   }

   /**
    * Resolves the derived apron cell(s) that represent one canonical face
    * block. This is the reverse lookup used to refresh already-loaded views
    * when the owner block changes.
    */
   public List<CanonicalProjection> resolveCanonicalBlock(double planarX, double planarZ) {
      CubeNetLayout.FacePoint canonical;
      try {
         canonical = this.layout.fromPlanar(new CubeNetLayout.PlanarPoint(planarX, planarZ));
      } catch (IllegalArgumentException outsideNet) {
         return List.of();
      }
      int canonicalBlockX = (int)Math.floor(canonical.u());
      int canonicalBlockZ = (int)Math.floor(canonical.v());
      List<CanonicalProjection> result = new ArrayList<>(1);
      for (CubeNetLayout.Seam seam : this.cutSeams) {
         if (seam.target() != canonical.face()) continue;
         double penetration = targetPenetration(canonical.u(), canonical.v(), seam.targetSide());
         if (!(penetration > 0.0 && penetration <= this.width)) continue;
         double edge = seam.targetSide() == CubeNetLayout.Side.WEST
            || seam.targetSide() == CubeNetLayout.Side.EAST ? canonical.v() : canonical.u();
         if (!(edge >= 0.0 && edge < this.layout.faceSize())) continue;

         CubeNetSeamTransform transform = new CubeNetSeamTransform(this.layout, seam);
         CubeNetLayout.PlanarPoint proxy = transform.targetToSourcePlanar(canonical);
         ApronAddress resolved = this.resolve(proxy.x(), proxy.z()).orElse(null);
         if (resolved == null || resolved.targetFace() != canonical.face()
            || resolved.blockX() != canonicalBlockX || resolved.blockZ() != canonicalBlockZ) continue;
         result.add(new CanonicalProjection(resolved.sourceFace(), resolved.sourceSide(), canonical.face(),
            (int)Math.floor(proxy.x()), (int)Math.floor(proxy.z()), canonicalBlockX, canonicalBlockZ));
      }
      return List.copyOf(result);
   }

   /**
    * Resolves a complete proxy chunk to one canonical target chunk. All strip
    * bounds and transforms are chunk aligned, so checking its four cell
    * corners suffices; no per-block strip searches are needed at fill time.
    */
   public Optional<ChunkProjection> resolveChunk(int chunkX, int chunkZ) {
      ApronAddress first = null;
      int targetChunkX = Integer.MIN_VALUE;
      int targetChunkZ = Integer.MIN_VALUE;
      for (int z : new int[]{0, 15}) for (int x : new int[]{0, 15}) {
         ApronAddress current = resolve(chunkX * 16.0 + x + 0.5, chunkZ * 16.0 + z + 0.5).orElse(null);
         if (current == null) return Optional.empty();
         int currentChunkX = Math.floorDiv(current.blockX(), 16);
         int currentChunkZ = Math.floorDiv(current.blockZ(), 16);
         if (first == null) {
            first = current;
            targetChunkX = currentChunkX;
            targetChunkZ = currentChunkZ;
         } else if (current.sourceFace() != first.sourceFace() || current.sourceSide() != first.sourceSide()
            || current.targetFace() != first.targetFace()
            || currentChunkX != targetChunkX || currentChunkZ != targetChunkZ) {
            return Optional.empty();
         }
      }
      if (first == null) return Optional.empty();
      double[] localX = this.layout.localVector(first.sourceFace(), 1.0, 0.0);
      double[] localZ = this.layout.localVector(first.sourceFace(), 0.0, 1.0);
      double[] targetX = this.layout.transportVector(first.sourceFace(), first.sourceSide(), localX[0], localX[1]);
      double[] targetZ = this.layout.transportVector(first.sourceFace(), first.sourceSide(), localZ[0], localZ[1]);
      return Optional.of(new ChunkProjection(first.sourceFace(), first.sourceSide(),
         first.targetFace(), chunkX, chunkZ, targetChunkX, targetChunkZ,
         first.blockX(), first.blockZ(), (int)targetX[0], (int)targetZ[0], (int)targetX[1], (int)targetZ[1]));
   }

   private double penetration(double u, double v, CubeNetLayout.Side side) {
      return switch (side) {
         case WEST -> -u;
         case EAST -> u - this.layout.faceSize();
         case NORTH -> -v;
         case SOUTH -> v - this.layout.faceSize();
      };
   }

   private double targetPenetration(double u, double v, CubeNetLayout.Side targetSide) {
      return switch (targetSide) {
         case WEST -> u;
         case EAST -> this.layout.faceSize() - u;
         case NORTH -> v;
         case SOUTH -> this.layout.faceSize() - v;
      };
   }

   public record ApronAddress(CubeNetLayout.Face sourceFace, CubeNetLayout.Side sourceSide,
      CubeNetLayout.Face targetFace, double targetU, double targetV, int blockX, int blockZ,
      double edgeCoordinate, double penetration) {
      public ApronAddress {
         Objects.requireNonNull(sourceFace, "sourceFace");
         Objects.requireNonNull(sourceSide, "sourceSide");
         Objects.requireNonNull(targetFace, "targetFace");
         if (!Double.isFinite(targetU) || !Double.isFinite(targetV)
            || !Double.isFinite(edgeCoordinate) || !Double.isFinite(penetration)) {
            throw new IllegalArgumentException("apron address");
         }
      }
   }

   public record CanonicalProjection(CubeNetLayout.Face sourceFace, CubeNetLayout.Side sourceSide,
      CubeNetLayout.Face targetFace, int sourceBlockX, int sourceBlockZ,
      int targetBlockX, int targetBlockZ) { }

   public record ChunkProjection(CubeNetLayout.Face sourceFace, CubeNetLayout.Side sourceSide,
      CubeNetLayout.Face targetFace, int sourceChunkX, int sourceChunkZ,
      int targetChunkX, int targetChunkZ, int originBlockX, int originBlockZ,
      int m00, int m01, int m10, int m11) {
      public ChunkProjection {
         Objects.requireNonNull(sourceFace, "sourceFace");
         Objects.requireNonNull(sourceSide, "sourceSide");
         Objects.requireNonNull(targetFace, "targetFace");
      }

      public int targetBlockX(int localX, int localZ) {
         return this.originBlockX + this.m00 * localX + this.m01 * localZ;
      }

      public int targetBlockZ(int localX, int localZ) {
         return this.originBlockZ + this.m10 * localX + this.m11 * localZ;
      }
   }
}
