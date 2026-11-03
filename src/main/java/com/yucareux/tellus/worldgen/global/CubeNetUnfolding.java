package com.yucareux.tellus.worldgen.global;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Render-only development of the cube around the player's physical face.
 *
 * <p>The planar net stores each face once. Far terrain is drawn by placing
 * those canonical charts rigidly around the player: the physical face, its
 * four neighbours, and the two candidates for each corner square. A corner
 * candidate is drawn only where the straight line from the player crosses the
 * edge it is attached through, which is the shortest path on the cube surface.
 * The placements depend only on the physical face; a seam rebase changes the
 * home chart, which composes every placement with the same rigid map.</p>
 */
public final class CubeNetUnfolding {
   /** Integers per placement in {@link #encode(List)}. */
   public static final int ENCODED_STRIDE = 14;

   private static final CubeNetLayout.Side[][] CORNERS = {
      {CubeNetLayout.Side.NORTH, CubeNetLayout.Side.WEST},
      {CubeNetLayout.Side.NORTH, CubeNetLayout.Side.EAST},
      {CubeNetLayout.Side.SOUTH, CubeNetLayout.Side.WEST},
      {CubeNetLayout.Side.SOUTH, CubeNetLayout.Side.EAST},
   };

   private final CubeNetLayout layout;
   private final CubeNetRemoteProjection projection;

   public CubeNetUnfolding(CubeNetLayout layout, double apronWidth) {
      this.layout = Objects.requireNonNull(layout, "layout");
      this.projection = new CubeNetRemoteProjection(layout, apronWidth);
   }

   public CubeNetLayout layout() { return this.layout; }

   /**
    * Resolves the player's physical face and its chart in the current planar
    * coordinates. Returns null where the planar point has no unique owner.
    */
   public Frame frameAt(double x, double z) {
      var resolved = this.projection.resolve(x, z).orElse(null);
      if (resolved == null) return null;
      CubeNetLayout.Face physical = resolved.point().face();
      CubeNetLayout.Side cut = resolved.chart().cut();
      Affine chart = cut == null ? Affine.IDENTITY : chartTransform(resolved.chart().source(), cut);
      return new Frame(physical, chart);
   }

   /** Placements for a player whose physical face is drawn through {@code frame.chart()}. */
   public List<Placement> around(Frame frame) {
      Objects.requireNonNull(frame, "frame");
      CubeNetLayout.Face home = frame.physical();
      Affine chart = frame.chart();
      List<Placement> result = new ArrayList<>(13);
      result.add(new Placement(home, chart, null));
      for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
         CubeNetLayout.Face neighbour = this.layout.seam(home, side).target();
         result.add(new Placement(neighbour, chartTransform(home, side).then(chart), null));
      }
      for (CubeNetLayout.Side[] corner : CORNERS) {
         result.add(cornerPlacement(home, corner[0], corner[1], chart));
         result.add(cornerPlacement(home, corner[1], corner[0], chart));
      }
      return List.copyOf(result);
   }

   /**
    * Maps the canonical chart of the face across {@code side} into the chart
    * of {@code face}. Direct net edges are already adjacent in the plane.
    */
   public Affine chartTransform(CubeNetLayout.Face face, CubeNetLayout.Side side) {
      if (this.layout.arePlanarNeighbors(face, side)) return Affine.IDENTITY;
      var seam = new CubeNetSeamTransform(this.layout, this.layout.seam(face, side));
      var grid = seam.targetToSourceGridTransform();
      var origin = new CubeNetLayout.FacePoint(seam.seam().target(), 0.0, 0.0);
      var canonical = this.layout.toPlanar(origin);
      var projected = seam.targetToSourcePlanar(origin);
      double[] rotated = grid.apply(canonical.x(), canonical.z());
      return new Affine(grid.m00(), grid.m01(), grid.m10(), grid.m11(),
         exact(projected.x() - rotated[0]), exact(projected.z() - rotated[1]));
   }

   /** Axis-aligned canonical footprint of one face: min X, min Z and size. */
   public long[] canonicalRect(CubeNetLayout.Face face) {
      var placement = this.layout.placement(face);
      long size = this.layout.faceSize();
      long x0 = placement.originX() * size, z0 = placement.originZ() * size;
      long x1 = x0 + (placement.uAxisX() + placement.vAxisX()) * size;
      long z1 = z0 + (placement.uAxisZ() + placement.vAxisZ()) * size;
      return new long[]{Math.min(x0, x1), Math.min(z0, z1), size};
   }

   /**
    * Flat encoding consumed by the Tellus Distant Horizons fork: canonical
    * rect, rotation, translation and an optional portal edge.
    */
   public int[] encode(List<Placement> placements) {
      int[] result = new int[placements.size() * ENCODED_STRIDE];
      int i = 0;
      for (Placement placement : placements) {
         long[] rect = canonicalRect(placement.face());
         Affine t = placement.transform();
         result[i++] = Math.toIntExact(rect[0]);
         result[i++] = Math.toIntExact(rect[1]);
         result[i++] = Math.toIntExact(rect[2]);
         result[i++] = t.m00();
         result[i++] = t.m01();
         result[i++] = t.m10();
         result[i++] = t.m11();
         result[i++] = Math.toIntExact(t.tx());
         result[i++] = Math.toIntExact(t.tz());
         Portal portal = placement.portal();
         result[i++] = portal == null ? 0 : 1;
         result[i++] = portal == null ? 0 : Math.toIntExact(portal.startX());
         result[i++] = portal == null ? 0 : Math.toIntExact(portal.startZ());
         result[i++] = portal == null ? 0 : Math.toIntExact(portal.endX());
         result[i++] = portal == null ? 0 : Math.toIntExact(portal.endZ());
      }
      return result;
   }

   private Placement cornerPlacement(CubeNetLayout.Face home, CubeNetLayout.Side throughSide,
      CubeNetLayout.Side targetSide, Affine chart) {
      CubeNetLayout.Face through = this.layout.seam(home, throughSide).target();
      CubeNetLayout.Face target = this.layout.seam(home, targetSide).target();
      CubeNetLayout.Side link = sideFacing(through, target);
      Affine arm = chartTransform(home, throughSide);
      Affine placed = chartTransform(through, link).then(arm);
      // The portal is the image of the through-face edge shared with target;
      // one endpoint is the home-face vertex.
      double[] vertex = cornerPoint(home, throughSide, targetSide);
      double[] start = null, end = null;
      for (double along : new double[]{0.0, this.layout.faceSize()}) {
         double[] p = arm.apply(planarEdgePoint(through, link, along));
         if (same(p, vertex)) start = p;
         else end = p;
      }
      if (start == null || end == null) {
         throw new IllegalStateException("corner portal does not meet the home vertex: " + home + " " + throughSide);
      }
      double[] s = chart.apply(start), e = chart.apply(end);
      return new Placement(target, placed.then(chart),
         new Portal(exact(s[0]), exact(s[1]), exact(e[0]), exact(e[1])));
   }

   private CubeNetLayout.Side sideFacing(CubeNetLayout.Face face, CubeNetLayout.Face neighbour) {
      for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
         if (this.layout.seam(face, side).target() == neighbour) return side;
      }
      throw new IllegalStateException(face + " is not adjacent to " + neighbour);
   }

   private double[] cornerPoint(CubeNetLayout.Face face, CubeNetLayout.Side a, CubeNetLayout.Side b) {
      double size = this.layout.faceSize();
      double u = a == CubeNetLayout.Side.EAST || b == CubeNetLayout.Side.EAST ? size : 0.0;
      double v = a == CubeNetLayout.Side.SOUTH || b == CubeNetLayout.Side.SOUTH ? size : 0.0;
      var p = this.layout.toPlanar(new CubeNetLayout.FacePoint(face, u, v));
      return new double[]{p.x(), p.z()};
   }

   private double[] planarEdgePoint(CubeNetLayout.Face face, CubeNetLayout.Side side, double along) {
      double size = this.layout.faceSize();
      double u = switch (side) {
         case WEST -> 0.0;
         case EAST -> size;
         case NORTH, SOUTH -> along;
      };
      double v = switch (side) {
         case NORTH -> 0.0;
         case SOUTH -> size;
         case WEST, EAST -> along;
      };
      var p = this.layout.toPlanar(new CubeNetLayout.FacePoint(face, u, v));
      return new double[]{p.x(), p.z()};
   }

   private static boolean same(double[] a, double[] b) {
      return Math.abs(a[0] - b[0]) < 1.0E-6 && Math.abs(a[1] - b[1]) < 1.0E-6;
   }

   private static long exact(double value) {
      long rounded = Math.round(value);
      if (Math.abs(value - rounded) > 1.0E-6) {
         throw new IllegalStateException("cube-net placement is not block aligned: " + value);
      }
      return rounded;
   }

   /** Physical face of the player and the map from its canonical chart to the current planar chart. */
   public record Frame(CubeNetLayout.Face physical, Affine chart) {
      public Frame {
         Objects.requireNonNull(physical, "physical");
         Objects.requireNonNull(chart, "chart");
      }
   }

   /** Quarter-turn rotation plus integer translation in planar X/Z. */
   public record Affine(int m00, int m01, int m10, int m11, long tx, long tz) {
      public static final Affine IDENTITY = new Affine(1, 0, 0, 1, 0, 0);

      public Affine {
         if (m00 * m11 - m01 * m10 != 1 || m00 * m00 + m10 * m10 != 1
            || m01 * m01 + m11 * m11 != 1 || m00 * m01 + m10 * m11 != 0) {
            throw new IllegalArgumentException("placement must be a quarter-turn rotation");
         }
      }

      public double[] apply(double x, double z) {
         return new double[]{m00 * x + m01 * z + tx, m10 * x + m11 * z + tz};
      }

      public double[] apply(double[] point) { return apply(point[0], point[1]); }

      /** Returns {@code outer ∘ this}. */
      public Affine then(Affine outer) {
         return new Affine(
            outer.m00 * m00 + outer.m01 * m10, outer.m00 * m01 + outer.m01 * m11,
            outer.m10 * m00 + outer.m11 * m10, outer.m10 * m01 + outer.m11 * m11,
            outer.m00 * tx + outer.m01 * tz + outer.tx, outer.m10 * tx + outer.m11 * tz + outer.tz);
      }

      public Affine inverse() {
         return new Affine(m00, m10, m01, m11, -(m00 * tx + m10 * tz), -(m01 * tx + m11 * tz));
      }
   }

   /**
    * Screen-space edge a corner placement is seen through. A point is drawn
    * only if the segment from the player to it crosses this edge.
    */
   public record Portal(long startX, long startZ, long endX, long endZ) {
      public boolean admits(double playerX, double playerZ, double x, double z) {
         double a = cross(startX - playerX, startZ - playerZ, x - playerX, z - playerZ);
         double b = cross(endX - playerX, endZ - playerZ, x - playerX, z - playerZ);
         return a * b < 0.0;
      }

      private static double cross(double ax, double az, double bx, double bz) {
         return ax * bz - az * bx;
      }
   }

   /** One canonical face drawn through {@code transform}, optionally limited by a portal. */
   public record Placement(CubeNetLayout.Face face, Affine transform, Portal portal) {
      public Placement {
         Objects.requireNonNull(face, "face");
         Objects.requireNonNull(transform, "transform");
      }

      public boolean admits(double playerX, double playerZ, double screenX, double screenZ) {
         return this.portal == null || this.portal.admits(playerX, playerZ, screenX, screenZ);
      }
   }
}
