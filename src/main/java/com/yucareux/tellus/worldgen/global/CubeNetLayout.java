package com.yucareux.tellus.worldgen.global;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/**
 * A single, deterministic planar net for the six faces of a cube.
 *
 * <p>The net is a coordinate view only. Physical adjacency is defined by the
 * seam table, not by planar distance. Five physical face edges are ordinary
 * planar adjacencies in this net; the other seven are represented by seam
 * transfers.</p>
 */
public final class CubeNetLayout {
   public static final int FACE_COUNT = 6;

   private final int faceSize;
   private final Map<Face, FacePlacement> placements;
   private final Map<Face, Map<Side, Seam>> seams;

   public CubeNetLayout(int faceSize) {
      if (faceSize < 1) throw new IllegalArgumentException("faceSize must be positive");
      this.faceSize = faceSize;
      this.placements = buildPlacements();
      this.seams = buildSeams();
      validatePlacements();
      validateSeams();
   }

   public static CubeNetLayout standard(int faceSize) {
      return new CubeNetLayout(faceSize);
   }

   public int faceSize() { return this.faceSize; }

   public FacePlacement placement(Face face) {
      return this.placements.get(Objects.requireNonNull(face, "face"));
   }

   public PlanarPoint toPlanar(FacePoint point) {
      Objects.requireNonNull(point, "point");
      requireFaceCoordinate(point.u(), point.v());
      FacePlacement placement = placement(point.face());
      return new PlanarPoint(
         (double)placement.originX() * this.faceSize
            + placement.uAxisX() * point.u() + placement.vAxisX() * point.v(),
         (double)placement.originZ() * this.faceSize
            + placement.uAxisZ() * point.u() + placement.vAxisZ() * point.v());
   }

   /**
    * Resolves a planar point in the interior of one net face. Points exactly
    * on a direct net edge are intentionally rejected: the caller must apply
    * the same half-open ownership rule used by its block/chunk materializer.
    */
   public FacePoint fromPlanar(PlanarPoint point) {
      Objects.requireNonNull(point, "point");
      FacePoint result = null;
      for (Face face : Face.values()) {
         FacePlacement placement = placement(face);
         double dx = point.x() - (double)placement.originX() * this.faceSize;
         double dz = point.z() - (double)placement.originZ() * this.faceSize;
         double u = placement.uAxisX() * dx + placement.uAxisZ() * dz;
         double v = placement.vAxisX() * dx + placement.vAxisZ() * dz;
         if (u > 0.0 && u < this.faceSize && v > 0.0 && v < this.faceSize) {
            if (result != null) throw new IllegalArgumentException("planar point lies in two net faces");
            result = new FacePoint(face, u, v);
         }
      }
      if (result == null) throw new IllegalArgumentException("planar point is not in a net-face interior");
      return result;
   }

   public Seam seam(Face face, Side side) {
      Objects.requireNonNull(face, "face");
      Objects.requireNonNull(side, "side");
      return this.seams.get(face).get(side);
   }

   /**
    * Maps a point crossing one physical edge to the adjacent face.
    *
    * @param edgeCoordinate coordinate along the source edge in [0, faceSize]
    * @param penetration positive distance inside the destination face
    */
   public FacePoint mapAcross(Face face, Side side, double edgeCoordinate, double penetration) {
      Seam seam = seam(face, side);
      if (!Double.isFinite(edgeCoordinate) || edgeCoordinate < 0.0
         || edgeCoordinate > this.faceSize) {
         throw new IllegalArgumentException("edge coordinate outside seam");
      }
      if (!Double.isFinite(penetration) || penetration < 0.0
         || penetration > this.faceSize) {
         throw new IllegalArgumentException("seam penetration outside face");
      }
      double targetEdgeCoordinate = seam.reverse()
         ? this.faceSize - edgeCoordinate : edgeCoordinate;
      double u = targetEdgeCoordinate, v = targetEdgeCoordinate;
      switch (seam.targetSide()) {
         case WEST -> u = penetration;
         case EAST -> u = this.faceSize - penetration;
         case NORTH -> v = penetration;
         case SOUTH -> v = this.faceSize - penetration;
      }
      if (seam.targetSide() == Side.WEST || seam.targetSide() == Side.EAST) v = targetEdgeCoordinate;
      else u = targetEdgeCoordinate;
      return new FacePoint(seam.target(), u, v);
   }

   /**
    * Routes one continuous local-face step through a cut seam. The returned
    * point contains the remaining displacement after the boundary hit. A step
    * that reaches two edges at once is rejected because cube vertices require
    * an explicit extraordinary-point policy rather than an arbitrary edge
    * ordering.
    */
   public SeamTransfer crossSeam(FacePoint source, double deltaU, double deltaV) {
      Objects.requireNonNull(source, "source");
      requireFaceCoordinate(source.u(), source.v());
      if (!Double.isFinite(deltaU) || !Double.isFinite(deltaV)) {
         throw new IllegalArgumentException("seam displacement");
      }
      Side side = outsideSide(source.u(), source.v(), deltaU, deltaV);
      if (side == null) {
         return new SeamTransfer(source, new FacePoint(source.face(), source.u() + deltaU, source.v() + deltaV),
            null, 0.0, false);
      }
      double hit = hitFraction(source, side, deltaU, deltaV);
      double edge = edgeCoordinate(source, side, hit, deltaU, deltaV);
      double remainingU = deltaU * (1.0 - hit), remainingV = deltaV * (1.0 - hit);
      double[] transported = transportVector(source.face(), side, remainingU, remainingV);
      FacePoint boundary = mapAcross(source.face(), side, edge, 0.0);
      FacePoint destination = new FacePoint(boundary.face(),
         boundary.u() + transported[0], boundary.v() + transported[1]);
      requireFaceCoordinate(destination.u(), destination.v());
      return new SeamTransfer(source, destination, seam(source.face(), side), edge, true);
   }

   /** Maps a local tangent vector through the orientation of one physical seam. */
   public double[] transportVector(Face face, Side side, double deltaU, double deltaV) {
      Seam seam = seam(face, side);
      double tangent = switch (side) {
         case WEST, EAST -> deltaV;
         case NORTH, SOUTH -> deltaU;
      };
      double outward = switch (side) {
         case WEST -> -deltaU;
         case EAST -> deltaU;
         case NORTH -> -deltaV;
         case SOUTH -> deltaV;
      };
      double targetTangent = seam.reverse() ? -tangent : tangent;
      double targetNormal = switch (seam.targetSide()) {
         case WEST, NORTH -> outward;
         case EAST, SOUTH -> -outward;
      };
      double targetU = switch (seam.targetSide()) {
         case WEST, EAST -> targetNormal;
         case NORTH, SOUTH -> targetTangent;
      };
      double targetV = switch (seam.targetSide()) {
         case WEST, EAST -> targetTangent;
         case NORTH, SOUTH -> targetNormal;
      };
      return new double[]{targetU, targetV};
   }

   /** Converts a planar X/Z vector into the local tangent basis of a face. */
   public double[] localVector(Face face, double deltaX, double deltaZ) {
      FacePlacement placement = placement(face);
      return new double[]{
         placement.uAxisX() * deltaX + placement.uAxisZ() * deltaZ,
         placement.vAxisX() * deltaX + placement.vAxisZ() * deltaZ
      };
   }

   /** Converts a local face tangent vector back into the planar X/Z basis. */
   public double[] planarVector(Face face, double deltaU, double deltaV) {
      FacePlacement placement = placement(face);
      return new double[]{
         placement.uAxisX() * deltaU + placement.vAxisX() * deltaV,
         placement.uAxisZ() * deltaU + placement.vAxisZ() * deltaV
      };
   }

   public boolean arePlanarNeighbors(Face first, Side firstSide) {
      Seam seam = seam(first, firstSide);
      FacePlacement a = placement(first);
      FacePlacement b = placement(seam.target());
      double[] firstStart = planarEdgePoint(a, firstSide, 0.0);
      double[] firstEnd = planarEdgePoint(a, firstSide, this.faceSize);
      double[] targetStart = planarEdgePoint(b, seam.targetSide(), 0.0);
      double[] targetEnd = planarEdgePoint(b, seam.targetSide(), this.faceSize);
      return (planarSame(firstStart, targetEnd) && planarSame(firstEnd, targetStart))
         || (planarSame(firstStart, targetStart) && planarSame(firstEnd, targetEnd));
   }

   public enum Face {
      POS_X(0), NEG_X(1), POS_Y(2), NEG_Y(3), POS_Z(4), NEG_Z(5);

      private final int index;

      Face(int index) { this.index = index; }

      public int index() { return this.index; }

      public static Face of(int index) {
         for (Face face : values()) if (face.index == index) return face;
         throw new IllegalArgumentException("face index must be in [0, 5]");
      }
   }

   /** NORTH/SOUTH are v=0/v=faceSize; WEST/EAST are u=0/u=faceSize. */
   public enum Side {
      WEST, EAST, NORTH, SOUTH;

      public Side opposite() {
         return switch (this) {
            case WEST -> EAST;
            case EAST -> WEST;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
         };
      }
   }

   public record FacePoint(Face face, double u, double v) {
      public FacePoint {
         Objects.requireNonNull(face, "face");
         if (!Double.isFinite(u) || !Double.isFinite(v)) throw new IllegalArgumentException("face point");
      }
   }

   public record PlanarPoint(double x, double z) {
      public PlanarPoint {
         if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("planar point");
      }
   }

   public record FacePlacement(Face face, int originX, int originZ,
      int uAxisX, int uAxisZ, int vAxisX, int vAxisZ) {
      public FacePlacement {
         Objects.requireNonNull(face, "face");
         if (!isAxis(uAxisX, uAxisZ) || !isAxis(vAxisX, vAxisZ)
            || uAxisX * vAxisX + uAxisZ * vAxisZ != 0) {
            throw new IllegalArgumentException("face placement axes must be perpendicular unit axes");
         }
      }

      private static boolean isAxis(int x, int z) {
         return Math.abs(x) + Math.abs(z) == 1;
      }
   }

   public record Seam(Face source, Side sourceSide, Face target, Side targetSide, boolean reverse) {
      public Seam {
         Objects.requireNonNull(source, "source");
         Objects.requireNonNull(sourceSide, "sourceSide");
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(targetSide, "targetSide");
         if (source == target) throw new IllegalArgumentException("seam cannot stay on one face");
      }
   }

   public record SeamTransfer(FacePoint source, FacePoint destination, Seam seam,
      double edgeCoordinate, boolean crossed) {
      public SeamTransfer {
         Objects.requireNonNull(source, "source");
         Objects.requireNonNull(destination, "destination");
         if (!Double.isFinite(edgeCoordinate) || edgeCoordinate < 0.0) {
            throw new IllegalArgumentException("edge coordinate");
         }
         if (!crossed && seam != null) throw new IllegalArgumentException("uncrossed transfer has seam");
         if (crossed && seam == null) throw new IllegalArgumentException("crossed transfer has no seam");
      }
   }

   private Map<Face, FacePlacement> buildPlacements() {
      // A cross with NEG_Y attached below NEG_Z. POS_Y is the center face.
      return Map.of(
         // The translation keeps the ordinary scale-1 net inside the default
         // Minecraft world border while preserving every relative adjacency.
         Face.POS_X, new FacePlacement(Face.POS_X, 2, -1, 0, 1, -1, 0),
         Face.NEG_X, new FacePlacement(Face.NEG_X, -1, -1, 0, 1, 1, 0),
         Face.POS_Y, new FacePlacement(Face.POS_Y, 0, -1, 1, 0, 0, 1),
         Face.NEG_Y, new FacePlacement(Face.NEG_Y, 0, 1, 1, 0, 0, 1),
         Face.POS_Z, new FacePlacement(Face.POS_Z, 0, -2, 1, 0, 0, 1),
         Face.NEG_Z, new FacePlacement(Face.NEG_Z, 0, 1, 1, 0, 0, -1));
   }

   private Map<Face, Map<Side, Seam>> buildSeams() {
      EnumMap<Face, Map<Side, Seam>> result = new EnumMap<>(Face.class);
      for (Face face : Face.values()) {
         EnumMap<Side, Seam> sides = new EnumMap<>(Side.class);
         for (Side side : Side.values()) {
            Face target = neighborFace(face, side);
            Side targetSide = neighborSide(face, side);
            sides.put(side, new Seam(face, side, target, targetSide,
               edgeReversed(face, side, target, targetSide)));
         }
         result.put(face, Map.copyOf(sides));
      }
      return Map.copyOf(result);
   }

   private void validatePlacements() {
      EnumSet<Face> seen = EnumSet.noneOf(Face.class);
      for (Face face : Face.values()) {
         FacePlacement placement = placement(face);
         if (!seen.add(placement.face())) throw new IllegalStateException("duplicate net face");
      }
      for (Face first : Face.values()) for (Face second : Face.values()) {
         if (first.ordinal() >= second.ordinal()) continue;
         FacePlacement a = placement(first), b = placement(second);
         double[] aBounds = bounds(a), bBounds = bounds(b);
         if (aBounds[0] < bBounds[1] && bBounds[0] < aBounds[1]
            && aBounds[2] < bBounds[3] && bBounds[2] < aBounds[3]) {
            throw new IllegalStateException("overlapping cube-net faces");
         }
      }
   }

   private double[] bounds(FacePlacement placement) {
      double x0 = (double)placement.originX() * this.faceSize;
      double z0 = (double)placement.originZ() * this.faceSize;
      double x1 = x0 + (placement.uAxisX() + placement.vAxisX()) * this.faceSize;
      double z1 = z0 + (placement.uAxisZ() + placement.vAxisZ()) * this.faceSize;
      return new double[]{Math.min(x0, x1), Math.max(x0, x1), Math.min(z0, z1), Math.max(z0, z1)};
   }

   private void validateSeams() {
      for (Face face : Face.values()) for (Side side : Side.values()) {
         Seam seam = seam(face, side);
         Seam reverse = seam(seam.target(), seam.targetSide());
         if (reverse.target() != face || reverse.targetSide() != side || reverse.reverse() != seam.reverse()) {
            throw new IllegalStateException("non-reciprocal seam: " + seam);
         }
      }
   }

   private static Face neighborFace(Face face, Side side) {
      double[] midpoint = cubeEdgePoint(face, side, 0.5);
      Face result = null;
      for (Face candidate : Face.values()) {
         if (candidate == face) continue;
         if (normalCoordinate(candidate, midpoint) > 1.0 - 1.0E-9) {
            if (result != null) throw new IllegalStateException("edge has multiple target faces");
            result = candidate;
         }
      }
      if (result == null) throw new IllegalStateException("edge has no target face");
      return result;
   }

   private static Side neighborSide(Face face, Side side) {
      Face target = neighborFace(face, side);
      double[] midpoint = cubeEdgePoint(face, side, 0.5);
      double u = localU(target, midpoint), v = localV(target, midpoint);
      if (Math.abs(u + 1.0) < 1.0E-9) return Side.WEST;
      if (Math.abs(u - 1.0) < 1.0E-9) return Side.EAST;
      if (Math.abs(v + 1.0) < 1.0E-9) return Side.NORTH;
      if (Math.abs(v - 1.0) < 1.0E-9) return Side.SOUTH;
      throw new IllegalStateException("target edge is not a boundary");
   }

   private boolean edgeReversed(Face source, Side sourceSide, Face target, Side targetSide) {
      double[] sourceStart = cubeEdgePoint(source, sourceSide, 0);
      double[] sourceEnd = cubeEdgePoint(source, sourceSide, 1);
      double[] targetStart = cubeEdgePoint(target, targetSide, 0);
      double[] targetEnd = cubeEdgePoint(target, targetSide, 1);
      if (same(sourceStart, targetStart) && same(sourceEnd, targetEnd)) return false;
      if (same(sourceStart, targetEnd) && same(sourceEnd, targetStart)) return true;
      throw new IllegalStateException("seam endpoints do not meet: " + source + " " + sourceSide);
   }

   private static double[] cubeEdgePoint(Face face, Side side, double endpoint) {
      double coordinate = endpoint * 2.0 - 1.0;
      double u = switch (side) { case WEST -> -1.0; case EAST -> 1.0; case NORTH, SOUTH -> coordinate; };
      double v = switch (side) { case NORTH -> -1.0; case SOUTH -> 1.0; case WEST, EAST -> coordinate; };
      return switch (face) {
         case POS_X -> new double[]{1.0, v, -u};
         case NEG_X -> new double[]{-1.0, v, u};
         case POS_Y -> new double[]{u, 1.0, -v};
         case NEG_Y -> new double[]{u, -1.0, v};
         case POS_Z -> new double[]{u, v, 1.0};
         case NEG_Z -> new double[]{-u, v, -1.0};
      };
   }

   private static double normalCoordinate(Face face, double[] point) {
      return switch (face) {
         case POS_X -> point[0]; case NEG_X -> -point[0];
         case POS_Y -> point[1]; case NEG_Y -> -point[1];
         case POS_Z -> point[2]; case NEG_Z -> -point[2];
      };
   }

   private static double localU(Face face, double[] point) {
      return switch (face) {
         case POS_X -> -point[2]; case NEG_X -> point[2];
         case POS_Y -> point[0]; case NEG_Y -> point[0];
         case POS_Z -> point[0]; case NEG_Z -> -point[0];
      };
   }

   private static double localV(Face face, double[] point) {
      return switch (face) {
         case POS_X, NEG_X, POS_Z, NEG_Z -> point[1];
         case POS_Y -> -point[2]; case NEG_Y -> point[2];
      };
   }

   private double[] planarEdgePoint(FacePlacement placement, Side side, double coordinate) {
      double u = switch (side) { case WEST -> 0.0; case EAST -> this.faceSize; case NORTH, SOUTH -> coordinate; };
      double v = switch (side) { case NORTH -> 0.0; case SOUTH -> this.faceSize; case WEST, EAST -> coordinate; };
      return new double[]{(double)placement.originX() * this.faceSize + placement.uAxisX() * u + placement.vAxisX() * v,
         (double)placement.originZ() * this.faceSize + placement.uAxisZ() * u + placement.vAxisZ() * v};
   }

   private static boolean planarSame(double[] first, double[] second) {
      return Math.abs(first[0] - second[0]) < 1.0E-9
         && Math.abs(first[1] - second[1]) < 1.0E-9;
   }

   private Side outsideSide(double u, double v, double deltaU, double deltaV) {
      boolean west = u + deltaU < 0.0, east = u + deltaU > this.faceSize;
      boolean north = v + deltaV < 0.0, south = v + deltaV > this.faceSize;
      int count = (west || east ? 1 : 0) + (north || south ? 1 : 0);
      if (count > 1) throw new IllegalArgumentException("corner seam crossing requires an explicit vertex policy");
      if (west) return Side.WEST;
      if (east) return Side.EAST;
      if (north) return Side.NORTH;
      if (south) return Side.SOUTH;
      return null;
   }

   private double hitFraction(FacePoint source, Side side, double deltaU, double deltaV) {
      double fraction = switch (side) {
         case WEST -> -source.u() / deltaU;
         case EAST -> (this.faceSize - source.u()) / deltaU;
         case NORTH -> -source.v() / deltaV;
         case SOUTH -> (this.faceSize - source.v()) / deltaV;
      };
      if (!(fraction >= 0.0 && fraction <= 1.0) || !Double.isFinite(fraction)) {
         throw new IllegalArgumentException("seam displacement does not hit the selected edge");
      }
      return fraction;
   }

   private double edgeCoordinate(FacePoint source, Side side, double hit,
      double deltaU, double deltaV) {
      return switch (side) {
         case WEST, EAST -> source.v() + hit * deltaV;
         case NORTH, SOUTH -> source.u() + hit * deltaU;
      };
   }

   private static boolean same(double[] first, double[] second) {
      return Math.abs(first[0] - second[0]) < 1.0E-9
         && Math.abs(first[1] - second[1]) < 1.0E-9
         && Math.abs(first[2] - second[2]) < 1.0E-9;
   }

   private void requireFaceCoordinate(double u, double v) {
      if (!Double.isFinite(u) || !Double.isFinite(v)
         || u < 0.0 || u > this.faceSize || v < 0.0 || v > this.faceSize) {
         throw new IllegalArgumentException("face coordinate outside face");
      }
   }
}
