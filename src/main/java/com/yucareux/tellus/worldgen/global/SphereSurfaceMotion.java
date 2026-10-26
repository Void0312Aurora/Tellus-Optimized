package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/**
 * Horizontal surface-motion adapter shared by server routing and a future
 * client/collision hook. It owns the seam transfer decision and fails closed
 * when a destination probe or singularity quarantine rejects the step.
 */
public final class SphereSurfaceMotion {
   @FunctionalInterface
   public interface CollisionProbe {
      boolean blocked(int face, double engineX, double engineZ);
   }

   public record Result(
      SphereFaceTopology.EnginePoint destination,
      SphereWorldModel.GeoPoint sourceSurface,
      SphereWorldModel.GeoPoint destinationSurface,
      SphereWorldModel.HorizontalVector velocity,
      SphereWorldModel.FaceTransition frame,
      boolean crossedFace,
      boolean blocked
   ) {
      public Result {
         Objects.requireNonNull(destination, "destination");
         Objects.requireNonNull(sourceSurface, "sourceSurface");
         Objects.requireNonNull(destinationSurface, "destinationSurface");
         Objects.requireNonNull(velocity, "velocity");
      }
   }

   private final SphereWorldModel model;
   private final SphereFaceTopology topology;
   private final SphereSingularityPolicy singularityPolicy;

   public SphereSurfaceMotion(SphereWorldModel model, SphereSingularityPolicy singularityPolicy) {
      this.model = Objects.requireNonNull(model, "model");
      this.topology = new SphereFaceTopology(model);
      this.singularityPolicy = Objects.requireNonNull(singularityPolicy, "singularityPolicy");
      if (singularityPolicy.model() != model) {
         throw new IllegalArgumentException("policy must use the same model instance");
      }
   }

   public SphereFaceTopology topology() { return topology; }

   /**
    * Advances one horizontal step. The caller supplies the already selected
    * Minecraft horizontal velocity and a collision probe for the target.
    * Position and velocity are transformed together at a face seam.
    */
   public Result step(int face, double engineX, double engineZ, double velocityX, double velocityZ,
      double seconds, CollisionProbe probe) {
      validate(face, engineX, engineZ, velocityX, velocityZ, seconds);
      Objects.requireNonNull(probe, "probe");
      if (!topology.containsEnginePoint(engineX, engineZ)) {
         throw new IllegalArgumentException("source point is outside the face");
      }
      SphereWorldModel.GeoPoint sourceSurface = surface(face, engineX, engineZ);
      double rawX = engineX + velocityX * seconds;
      double rawZ = engineZ + velocityZ * seconds;
      if (topology.containsEnginePoint(rawX, rawZ)) {
         if (probe.blocked(face, rawX, rawZ)
            || !singularityPolicy.admitsOrdinaryEnginePoint(face, rawX, rawZ)) {
            return stopped(face, engineX, engineZ, sourceSurface);
         }
         return new Result(new SphereFaceTopology.EnginePoint(face, rawX, rawZ), sourceSurface,
            surface(face, rawX, rawZ), new SphereWorldModel.HorizontalVector(velocityX, velocityZ),
            null, false, false);
      }

      SphereFaceTopology.SeamTransfer transfer = topology.transfer(face, rawX, rawZ, 0.0F,
         velocityX, velocityZ);
      var destination = transfer.destination();
      if (probe.blocked(destination.face(), destination.x(), destination.z())
         || !singularityPolicy.admitsOrdinaryEnginePoint(destination.face(), destination.x(), destination.z())) {
         return stopped(face, engineX, engineZ, sourceSurface);
      }
      return new Result(destination, sourceSurface,
         surface(destination.face(), destination.x(), destination.z()), transfer.velocity(), transfer.frame(), true, false);
   }

   private Result stopped(int face, double engineX, double engineZ,
      SphereWorldModel.GeoPoint sourceSurface) {
      return new Result(new SphereFaceTopology.EnginePoint(face, engineX, engineZ), sourceSurface,
         sourceSurface, new SphereWorldModel.HorizontalVector(0.0, 0.0), null, false, true);
   }

   private SphereWorldModel.GeoPoint surface(int face, double engineX, double engineZ) {
      double offset = topology.minEngineBlock();
      return model.exactSurfacePoint(face, engineX - offset, engineZ - offset);
   }

   private static void validate(int face, double engineX, double engineZ, double velocityX,
      double velocityZ, double seconds) {
      if (face < 0 || face >= SphereWorldModel.FACE_COUNT
         || !Double.isFinite(engineX) || !Double.isFinite(engineZ)
         || !Double.isFinite(velocityX) || !Double.isFinite(velocityZ)
         || !Double.isFinite(seconds) || seconds < 0.0) {
         throw new IllegalArgumentException("invalid surface-motion step");
      }
   }
}
