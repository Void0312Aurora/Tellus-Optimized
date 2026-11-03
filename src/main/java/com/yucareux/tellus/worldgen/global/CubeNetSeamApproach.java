package com.yucareux.tellus.worldgen.global;

import java.util.Optional;

/** Predicts the handoff chunk while an observer approaches one directed cut. */
public final class CubeNetSeamApproach {
   private CubeNetSeamApproach() { }

   public static Optional<Approach> find(CubeNetLayout layout, CubeNetApronResolver resolver,
      double x, double z, float yaw, double lookahead, double depth) {
      double angle = Math.toRadians(yaw);
      return find(layout, resolver, x, z, -Math.sin(angle), Math.cos(angle), lookahead, depth);
   }

   /**
    * Finds the nearest cut without assuming which way the observer will move.
    * This is used while the observer is stationary, before a reliable position
    * delta exists. It preserves the old warm-start behavior without treating
    * camera yaw as a movement command.
    */
   public static Optional<Approach> findNearest(CubeNetLayout layout, CubeNetApronResolver resolver,
      double x, double z, double lookahead, double depth) {
      return findInternal(layout, resolver, x, z, 0.0, 0.0, false, lookahead, depth);
   }

   /**
    * Finds a cut seam in an explicit planar travel direction. The direction
    * is normalized before it is transformed into the local face basis, so a
    * server position delta can be passed directly without affecting the
    * outward-facing threshold.
    */
   public static Optional<Approach> find(CubeNetLayout layout, CubeNetApronResolver resolver,
      double x, double z, double directionX, double directionZ, double lookahead, double depth) {
      return findInternal(layout, resolver, x, z, directionX, directionZ, true, lookahead, depth);
   }

   private static Optional<Approach> findInternal(CubeNetLayout layout, CubeNetApronResolver resolver,
      double x, double z, double directionX, double directionZ, boolean directed,
      double lookahead, double depth) {
      CubeNetLayout.FacePoint local;
      try {
         local = layout.fromPlanar(new CubeNetLayout.PlanarPoint(x, z));
      } catch (IllegalArgumentException outside) {
         var apron = resolver.resolve(x, z).orElse(null);
         if (apron == null) return Optional.empty();
         var origin = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(apron.sourceFace(), 0, 0));
         double[] uv = layout.localVector(apron.sourceFace(), x - origin.x(), z - origin.z());
         local = new CubeNetLayout.FacePoint(apron.sourceFace(), uv[0], uv[1]);
      }
      double[] heading = null;
      if (directed) {
         double lengthSquared = directionX * directionX + directionZ * directionZ;
         if (!Double.isFinite(lengthSquared) || lengthSquared < 1.0E-12) return Optional.empty();
         double inverseLength = 1.0 / Math.sqrt(lengthSquared);
         heading = layout.localVector(local.face(), directionX * inverseLength,
            directionZ * inverseLength);
      }
      Approach best = null;
      for (var side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(local.face(), side)) continue;
         double distance = switch (side) {
            case WEST -> local.u(); case EAST -> layout.faceSize() - local.u();
            case NORTH -> local.v(); case SOUTH -> layout.faceSize() - local.v();
         };
         if (distance < -depth - 4 || distance > lookahead) continue;
         if (directed) {
            double outward = switch (side) {
               case WEST -> -heading[0]; case EAST -> heading[0];
               case NORTH -> -heading[1]; case SOUTH -> heading[1];
            };
            if (outward < 0.5) continue;
         }
         double edge = side == CubeNetLayout.Side.WEST || side == CubeNetLayout.Side.EAST ? local.v() : local.u();
         if (edge <= 0 || edge >= layout.faceSize()) continue;
         var target = layout.toPlanar(layout.mapAcross(local.face(), side, edge, depth + 0.5));
         var candidate = new Approach(layout.seam(local.face(), side),
            Math.floorDiv((int)Math.floor(target.x()), 16), Math.floorDiv((int)Math.floor(target.z()), 16), distance);
         if (best == null || candidate.distance() < best.distance()) best = candidate;
      }
      return Optional.ofNullable(best);
   }

   public record Approach(CubeNetLayout.Seam seam, int chunkX, int chunkZ, double distance) { }
}
