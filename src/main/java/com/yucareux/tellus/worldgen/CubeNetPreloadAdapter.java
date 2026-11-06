package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.preload.TerrainPreloadPackage;
import com.yucareux.tellus.preload.TerrainPreloadPackageRegistry;
import com.yucareux.tellus.preload.TerrainPreloadStorage;
import com.yucareux.tellus.preload.TerrainPreloadView;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapts the Earth preload package's equirectangular/Mercator block grid to a
 * Cube-Net geodetic sample. A Cube-Net face coordinate is first resolved by
 * {@link SphereWorldModel} to a {@link SphereWorldModel.GeoPoint}; longitude is
 * then {@link EarthProjection#longitudeToBlockX(double, double)}-mapped to X
 * and latitude is {@link EarthProjection#latToBlockZ(double, double)}-mapped to
 * Z. Packages are intentionally not projected onto face-local coordinates.
 *
 * <p>Mercator preload coverage ends at {@link EarthProjection#MAX_MERCATOR_LATITUDE}.
 * Cube-Net points outside that range (the polar caps) return a cache miss and
 * continue through the normal spherical source path.</p>
 */
public final class CubeNetPreloadAdapter {
   private CubeNetPreloadAdapter() {
   }

   public static TerrainPreloadView viewFor(double worldScale, int seaLevel) {
      EarthGeneratorSettings settings = TellusWorldgenSources.cubeNetWaterSettings(worldScale, seaLevel);
      TerrainPreloadStorage storage = TerrainPreloadStorage.instance();
      String fingerprint = storage.settingsFingerprint(storage.encodeSettings(settings));
      return TerrainPreloadPackageRegistry.instance().viewForFingerprint(fingerprint);
   }

   public static Optional<PlanarPoint> earthBlock(SphereWorldModel.GeoPoint point, double worldScale) {
      if (point == null || !(worldScale > 0.0) || !Double.isFinite(worldScale)
         || Math.abs(point.latitude()) > EarthProjection.MAX_MERCATOR_LATITUDE + 1.0E-9) {
         return Optional.empty();
      }
      double blockX = EarthProjection.longitudeToBlockX(point.longitude(), worldScale);
      double blockZ = EarthProjection.latToBlockZ(point.latitude(), worldScale);
      if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)
         || blockX < Integer.MIN_VALUE || blockX > Integer.MAX_VALUE
         || blockZ < Integer.MIN_VALUE || blockZ > Integer.MAX_VALUE) {
         return Optional.empty();
      }
      return Optional.of(new PlanarPoint((int)Math.floor(blockX), (int)Math.floor(blockZ)));
   }

   public static SphereChunkSampler.SurfaceData sample(
      TerrainPreloadView view,
      SphereWorldModel.GeoPoint point,
      double worldScale,
      int seaLevel,
      double resolutionMeters,
      Supplier<SphereChunkSampler.SurfaceData> fallbackSupplier
   ) {
      if (view == null || fallbackSupplier == null) return fallbackSupplier == null ? null : fallbackSupplier.get();
      Optional<PlanarPoint> coordinate = earthBlock(point, worldScale);
      if (coordinate.isEmpty()) return fallbackSupplier.get();
      TerrainPreloadPackage.Sample sample = view.sample(
         coordinate.get().blockX(), coordinate.get().blockZ(), resolutionMeters);
      if (sample == null || sample.terrainHeight() == Integer.MIN_VALUE) return fallbackSupplier.get();
      double elevation = (sample.terrainHeight() - seaLevel) * worldScale;
      if (!Double.isFinite(elevation)) return fallbackSupplier.get();
      boolean ocean = sample.landMaskKnown() && !sample.land()
         && (sample.coverClass() == 0 || sample.coverClass() == 80)
         && !sample.mapterhornLandOverride();
      double waterSurface = ocean && elevation < 0.0 ? 0.0 : Double.NaN;
      SphereChunkSampler.SurfaceData.WaterKind waterKind = Double.isFinite(waterSurface)
         ? SphereChunkSampler.SurfaceData.WaterKind.OCEAN
         : SphereChunkSampler.SurfaceData.WaterKind.NONE;
      return new SphereChunkSampler.SurfaceData(elevation, sample.coverClass(), waterSurface, waterKind);
   }

   public record PlanarPoint(int blockX, int blockZ) {
   }
}
