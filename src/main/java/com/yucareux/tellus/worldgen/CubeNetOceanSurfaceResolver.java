package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Objects;

/** Applies the ocean-floor part of Tellus water generation to a canonical sphere column. */
public final class CubeNetOceanSurfaceResolver {
   private static final double MIN_OCEAN_DEPTH_METERS = 10.0;

   private final double worldScale;
   private final OceanBathymetry bathymetry;

   public CubeNetOceanSurfaceResolver(double worldScale, OceanBathymetry bathymetry) {
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) throw new IllegalArgumentException("worldScale");
      this.worldScale = worldScale;
      this.bathymetry = Objects.requireNonNull(bathymetry, "bathymetry");
   }

   public SphereChunkSampler.SurfaceData apply(
      SphereWorldModel.GeoPoint point, SphereChunkSampler.SurfaceData surface, double resolutionMeters
   ) {
      Objects.requireNonNull(point, "point");
      Objects.requireNonNull(surface, "surface");
      if (!surface.oceanWater() || !surface.elevationAvailable() || !surface.waterAvailable()) return surface;
      double waterSurface = surface.waterSurfaceMeters();
      double bathymetryMeters = this.bathymetry.sample(point, this.worldScale, resolutionMeters);
      double floor = Double.isFinite(bathymetryMeters) ? bathymetryMeters : surface.elevationMeters();
      floor = Math.min(floor, waterSurface - MIN_OCEAN_DEPTH_METERS);
      if (!Double.isFinite(floor) || floor >= waterSurface) return surface;
      return new SphereChunkSampler.SurfaceData(
         floor, surface.coverClass(), waterSurface, SphereChunkSampler.SurfaceData.WaterKind.OCEAN,
         surface.geology(), surface.neighbourhood()
      );
   }

   @FunctionalInterface
   public interface OceanBathymetry {
      double sample(SphereWorldModel.GeoPoint point, double worldScale, double resolutionMeters);
   }
}
