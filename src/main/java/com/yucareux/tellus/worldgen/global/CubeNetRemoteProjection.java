package com.yucareux.tellus.worldgen.global;

import java.util.Objects;
import java.util.Optional;

/**
 * A deterministic, single-edge resolver for the fixed cube net's native apron.
 * Canonical footprints retain ownership. Outside them the closest directed
 * cut strip wins only within the configured native apron width. Ties, overlaps,
 * and cells crossing chart boundaries are quarantined. This resolver must not
 * invent a second face-sized world outside the native cube-net handoff.
 */
public final class CubeNetRemoteProjection {
   private static final double EPSILON = 1.0E-7;
   private final CubeNetLayout layout;
   private final double nativeWidth;
   private final double remoteWidth;

   public CubeNetRemoteProjection(CubeNetLayout layout) {
      this(layout, 0.0);
   }

   public CubeNetRemoteProjection(CubeNetLayout layout, double nativeWidth) {
      this.layout = Objects.requireNonNull(layout, "layout");
      if (!Double.isFinite(nativeWidth) || nativeWidth < 0 || nativeWidth > layout.faceSize()) {
         throw new IllegalArgumentException("nativeWidth");
      }
      this.nativeWidth = nativeWidth;
      // DH receives planar Minecraft coordinates.  It has no face identity, so
      // allowing a larger width here would silently turn a request outside the
      // native apron into a request for a different cube face.
      this.remoteWidth = nativeWidth;
   }

   public double remoteWidth() { return this.remoteWidth; }

   /** Coordinates are continuous block centres, including their half block. */
   public Optional<ResolvedPoint> resolve(double x, double z) {
      var planar = new CubeNetLayout.PlanarPoint(x, z);
      try {
         var point = this.layout.fromPlanar(planar);
         return Optional.of(new ResolvedPoint(point, new Chart(point.face(), null), 0.0));
      } catch (IllegalArgumentException outsideCanonicalFootprint) { }

      ResolvedPoint closest = null;
      boolean tied = false;
      int nativeCandidates = 0;
      for (var face : CubeNetLayout.Face.values()) {
         var placement = this.layout.placement(face);
         double dx = x - (double)placement.originX() * this.layout.faceSize();
         double dz = z - (double)placement.originZ() * this.layout.faceSize();
         double u = placement.uAxisX() * dx + placement.uAxisZ() * dz;
         double v = placement.vAxisX() * dx + placement.vAxisZ() * dz;
         for (var side : CubeNetLayout.Side.values()) {
            if (this.layout.arePlanarNeighbors(face, side)) continue;
            double penetration = switch (side) {
               case WEST -> -u;
               case EAST -> u - this.layout.faceSize();
               case NORTH -> -v;
               case SOUTH -> v - this.layout.faceSize();
            };
            double edge = side == CubeNetLayout.Side.WEST || side == CubeNetLayout.Side.EAST ? v : u;
            if (!(penetration > 0.0 && penetration <= this.remoteWidth
               && edge > 0.0 && edge < this.layout.faceSize())) continue;
            var target = this.layout.mapAcross(face, side, edge, penetration);
            if (!(target.u() > 0.0 && target.u() < this.layout.faceSize()
               && target.v() > 0.0 && target.v() < this.layout.faceSize())) continue;
            if (penetration <= this.nativeWidth) nativeCandidates++;
            if (closest == null || penetration < closest.penetration() - EPSILON) {
               closest = new ResolvedPoint(target, new Chart(face, side), penetration);
               tied = false;
            } else if (Math.abs(penetration - closest.penetration()) <= EPSILON) {
               tied = true;
            }
         }
      }
      return tied || nativeCandidates > 1 ? Optional.empty() : Optional.ofNullable(closest);
   }

   /**
    * Conservative chart guard for a rectangular group of block cells. A DH
    * request crossing a cut is split until it reaches block detail; a single
    * coarse column must never paint one face across a different chart.
    */
   public boolean isUniformArea(long minX, long minZ, long width) {
      if (width < 1) throw new IllegalArgumentException("width");
      Chart chart = null;
      boolean first = true;
      for (double dz : new double[]{0.5, width * 0.5, width - 0.5}) {
         for (double dx : new double[]{0.5, width * 0.5, width - 0.5}) {
            var resolved = resolve(minX + dx, minZ + dz).orElse(null);
            Chart current = resolved == null ? null : resolved.chart();
            if (!first && !Objects.equals(chart, current)) return false;
            chart = current;
            first = false;
         }
      }
      // All-empty samples do not prove that the rectangle is empty: a whole
      // narrow chart might lie between them. Split these areas too.
      return chart != null;
   }

   public record Chart(CubeNetLayout.Face source, CubeNetLayout.Side cut) { }
   public record ResolvedPoint(CubeNetLayout.FacePoint point, Chart chart, double penetration) { }
}
