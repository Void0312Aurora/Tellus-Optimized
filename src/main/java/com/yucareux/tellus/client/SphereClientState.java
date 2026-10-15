package com.yucareux.tellus.client;

import java.util.Objects;

/**
 * Client-side snapshot of the server-authoritative spherical frame.
 *
 * <p>The snapshot deliberately stores the active mapping id and face-local
 * coordinates together. A client renderer or movement adapter must reject a
 * snapshot with an unknown mapping instead of silently applying a different
 * projection.</p>
 */
public final class SphereClientState {
   private static final Snapshot EMPTY = new Snapshot(false, "", "", -1,
      Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0F, 0L);
   private static volatile Snapshot current = EMPTY;

   private SphereClientState() {
   }

   public static Snapshot current() {
      return current;
   }

   public static void update(Snapshot snapshot) {
      Objects.requireNonNull(snapshot, "snapshot");
      Snapshot previous = current;
      if (snapshot.revision() < previous.revision()) return;
      current = snapshot;
   }

   public static void reset() {
      current = EMPTY;
   }

   /**
    * Refreshes only the local pose while retaining the server-authoritative
    * planet, mapping, face, and revision identity.
    */
   public static void updateLocalPose(double x, double y, double z, float yaw) {
      Snapshot previous = current;
      if (!previous.spherical() || !Double.isFinite(x) || !Double.isFinite(y)
         || !Double.isFinite(z) || !Float.isFinite(yaw)) return;
      current = new Snapshot(previous.spherical(), previous.planet(), previous.mappingId(), previous.face(),
         x, y, z, previous.worldScale(), yaw, previous.revision());
   }

   public record Snapshot(
      boolean spherical,
      String planet,
      String mappingId,
      int face,
      double x,
      double y,
      double z,
      double worldScale,
      float yaw,
      long revision
   ) {
      public Snapshot {
         planet = Objects.requireNonNull(planet, "planet");
         mappingId = Objects.requireNonNull(mappingId, "mappingId");
         if (spherical && (face < 0 || face >= 6 || planet.isEmpty() || mappingId.isEmpty()
            || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !(worldScale > 0.0) || !Double.isFinite(worldScale) || !Float.isFinite(yaw))) {
            throw new IllegalArgumentException("invalid spherical client frame");
         }
         if (revision < 0L) throw new IllegalArgumentException("revision must be non-negative");
      }

      public static Snapshot empty(long revision) {
         return new Snapshot(false, "", "", -1,
            Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0F, revision);
      }
   }
}
