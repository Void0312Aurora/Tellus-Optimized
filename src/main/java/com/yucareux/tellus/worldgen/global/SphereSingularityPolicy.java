package com.yucareux.tellus.worldgen.global;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runtime boundary policy for the eight degree-three corners of the conformal
 * cubed sphere. Classification is conservative: terrain may use the ordinary
 * sampler through the transition buffer, while player admission remains
 * restricted to the ordinary band.
 */
public final class SphereSingularityPolicy {
   public enum Status { ORDINARY, TRANSITION, EXCLUDED }

   public static final Config DEFAULT_CONFIG = new Config(300_000.0, 50_000.0);

   public record Config(double excludedRadiusMeters, double transitionWidthMeters) {
      public Config {
         if (excludedRadiusMeters < 0.0 || !Double.isFinite(excludedRadiusMeters)) {
            throw new IllegalArgumentException("excludedRadiusMeters");
         }
         if (transitionWidthMeters < 0.0 || !Double.isFinite(transitionWidthMeters)) {
            throw new IllegalArgumentException("transitionWidthMeters");
         }
         if (!Double.isFinite(excludedRadiusMeters + transitionWidthMeters)) {
            throw new IllegalArgumentException("combined radius overflows");
         }
      }

      public double transitionLimitMeters() {
         return excludedRadiusMeters + transitionWidthMeters;
      }
   }

   public record Classification(Status status, double nearestCornerDistanceMeters,
      double footprintRadiusMeters, double conservativeDistanceMeters) {
      public Classification {
         Objects.requireNonNull(status, "status");
         if (nearestCornerDistanceMeters < 0.0 || !Double.isFinite(nearestCornerDistanceMeters)
            || footprintRadiusMeters < 0.0 || !Double.isFinite(footprintRadiusMeters)
            || conservativeDistanceMeters < 0.0 || !Double.isFinite(conservativeDistanceMeters)) {
            throw new IllegalArgumentException("classification distances");
         }
      }
   }

   private final SphereWorldModel model;
   private final Config config;
   private final List<SphereWorldModel.GeoPoint> corners;

   public SphereSingularityPolicy(SphereWorldModel model, Config config) {
      this.model = Objects.requireNonNull(model, "model");
      this.config = Objects.requireNonNull(config, "config");
      this.corners = List.copyOf(findUniqueCorners(model));
      if (this.corners.size() != 8) {
         throw new IllegalStateException("conformal cubed sphere must have eight unique corners");
      }
   }

   public SphereWorldModel model() { return model; }
   public Config config() { return config; }
   public String mappingId() { return model.mappingId(); }
   public List<SphereWorldModel.GeoPoint> corners() { return corners; }

   /**
    * Ideal area fraction for eight disjoint spherical caps. The runtime
    * whole-cell quarantine is larger because it rounds the boundary outward;
    * this value is a lower-bound planning estimate, not a mesh measurement.
    */
   public double idealExcludedSurfaceFraction() {
      return eightCapFraction(config.excludedRadiusMeters());
   }

   public double idealQuarantinedSurfaceFraction() {
      return eightCapFraction(config.transitionLimitMeters());
   }

   private static double eightCapFraction(double radiusMeters) {
      double angular = Math.min(Math.PI, radiusMeters / SphereWorldModel.EARTH_RADIUS_METERS);
      return Math.min(1.0, 4.0 * (1.0 - Math.cos(angular)));
   }

   /** Classifies an exact surface point without a cell-footprint allowance. */
   public Classification classify(SphereWorldModel.GeoPoint point) {
      Objects.requireNonNull(point, "point");
      return classifyDistance(nearestCornerDistance(point), 0.0);
   }

   public Status status(SphereWorldModel.GeoPoint point) {
      return classify(point).status();
   }

   /** Classifies a block cell using a conservative radial footprint. */
   public Classification classify(SphereWorldModel.SphereBlockAddress address) {
      Objects.requireNonNull(address, "address");
      model.chartForBlock(address.face(), address.blockX(), address.blockZ());
      return classify(address.face(), address.blockX() + 0.5, address.blockZ() + 0.5,
         blockFootprintRadiusMeters());
   }

   public Status status(SphereWorldModel.SphereBlockAddress address) {
      return classify(address).status();
   }

   /** Classifies a chunk and its full 16x16 surface footprint. */
   public Classification classify(SphereWorldModel.SphereChunkAddress address) {
      Objects.requireNonNull(address, "address");
      model.chartForChunk(address);
      return classify(address.face(), address.blockX() + 8.0, address.blockZ() + 8.0,
         chunkFootprintRadiusMeters());
   }

   public Status status(SphereWorldModel.SphereChunkAddress address) {
      return classify(address).status();
   }

   /** Classifies a continuous engine coordinate after converting it to a face cell. */
   public Classification classifyEnginePoint(int face, double engineX, double engineZ) {
      if (!Double.isFinite(engineX) || !Double.isFinite(engineZ)) {
         throw new IllegalArgumentException("engine coordinate");
      }
      double offset = model.config().faceBlocks() * 0.5;
      return classify(face, engineX + offset, engineZ + offset, 0.0);
   }

   public boolean admitsOrdinaryMaterialization(SphereWorldModel.SphereChunkAddress address) {
      return status(address) == Status.ORDINARY;
   }

   /**
    * Terrain generation may continue through the non-playable transition
    * buffer. The excluded cap remains outside the ordinary sampler because its
    * cells do not have a valid gameplay representation.
    */
   public boolean admitsTerrainMaterialization(SphereWorldModel.SphereChunkAddress address) {
      return status(address) != Status.EXCLUDED;
   }

   public boolean admitsOrdinaryEnginePoint(int face, double engineX, double engineZ) {
      return classifyEnginePoint(face, engineX, engineZ).status() == Status.ORDINARY;
   }

   public void requireOrdinary(SphereWorldModel.SphereChunkAddress address) {
      Classification result = classify(address);
      if (result.status() != Status.ORDINARY) {
         throw new IllegalStateException("spherical singularity quarantine: " + result.status()
            + " at " + address);
      }
   }

   public void requireTerrainMaterialization(SphereWorldModel.SphereChunkAddress address) {
      Classification result = classify(address);
      if (result.status() == Status.EXCLUDED) {
         throw new IllegalStateException("spherical singularity cap: " + result.status()
            + " at " + address);
      }
   }

   private Classification classify(int face, double blockX, double blockZ, double footprintRadiusMeters) {
      SphereWorldModel.GeoPoint point = model.exactSurfacePoint(face, blockX, blockZ);
      return classifyDistance(nearestCornerDistance(point), footprintRadiusMeters);
   }

   private Classification classifyDistance(double nearestDistance, double footprintRadiusMeters) {
      double conservative = Math.max(0.0, nearestDistance - footprintRadiusMeters);
      Status status;
      if (config.excludedRadiusMeters() > 0.0
         && conservative <= config.excludedRadiusMeters()) {
         status = Status.EXCLUDED;
      } else if (conservative <= config.transitionLimitMeters()) {
         status = Status.TRANSITION;
      } else {
         status = Status.ORDINARY;
      }
      return new Classification(status, nearestDistance, footprintRadiusMeters, conservative);
   }

   private double nearestCornerDistance(SphereWorldModel.GeoPoint point) {
      double result = Double.POSITIVE_INFINITY;
      for (var corner : corners) {
         result = Math.min(result, SphereWorldModel.greatCircleDistanceMeters(point, corner));
      }
      return result;
   }

   private double blockFootprintRadiusMeters() {
      // The conformal study's sampled maximum local scale is near 1.036. The
      // diagonal allowance is intentionally conservative while the exact
      // global derivative bound remains a separate acceptance gate.
      return Math.sqrt(2.0) * model.nominalBlockScaleMeters();
   }

   private double chunkFootprintRadiusMeters() {
      return 32.0 * model.nominalBlockScaleMeters();
   }

   private static List<SphereWorldModel.GeoPoint> findUniqueCorners(SphereWorldModel model) {
      List<SphereWorldModel.GeoPoint> result = new ArrayList<>();
      int size = model.config().faceBlocks();
      for (int face = 0; face < SphereWorldModel.FACE_COUNT; face++) {
         for (int uSign : new int[]{-1, 1}) {
            for (int vSign : new int[]{-1, 1}) {
               var point = model.exactSurfacePoint(face,
                  uSign < 0 ? 0.0 : size, vSign < 0 ? 0.0 : size);
               boolean known = result.stream().anyMatch(existing ->
                  SphereWorldModel.greatCircleDistanceMeters(existing, point) < 1.0E-5);
               if (!known) result.add(point);
            }
         }
      }
      return result;
   }
}
