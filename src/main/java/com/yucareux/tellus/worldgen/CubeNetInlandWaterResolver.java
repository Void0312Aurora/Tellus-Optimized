package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.List;
import java.util.Objects;

/**
 * Adapts Tellus's Earth-grid inland hydrology to canonical Cube-Net columns.
 *
 * <p>The hydrology planner still owns river and lake decisions. This adapter
 * only gives it an Earth-projected integer grid, converts its geodetic queries
 * to the existing OSM geodetic API, and samples terrain by mapping each Earth
 * grid point back to the canonical sphere. Cube-Net planar coordinates never
 * enter the planner.</p>
 */
public final class CubeNetInlandWaterResolver {
   private final SphereWorldModel model;
   private final double worldScale;
   private final int seaLevel;
   private final SphereChunkSampler terrain;
   private final InlandHydrologyPlanner planner;

   public CubeNetInlandWaterResolver(
      SphereWorldModel model, int seaLevel, SphereChunkSampler terrain, TellusOsmWaterSource waterSource
   ) {
      this(model, seaLevel, terrain, new TellusGeodeticWaterInput(
         Objects.requireNonNull(waterSource, "waterSource")));
   }

   CubeNetInlandWaterResolver(
      SphereWorldModel model, int seaLevel, SphereChunkSampler terrain, GeodeticWaterInput waterSource
   ) {
      this.model = Objects.requireNonNull(model, "model");
      this.worldScale = model.config().worldScale();
      if (!Double.isFinite(seaLevel)) throw new IllegalArgumentException("seaLevel");
      this.seaLevel = seaLevel;
      this.terrain = Objects.requireNonNull(terrain, "terrain");
      Objects.requireNonNull(waterSource, "waterSource");
      this.planner = new InlandHydrologyPlanner(
         new EarthGridWaterInput(waterSource, this.worldScale),
         this.worldScale,
         32,
         new SphereTerrainSampler(),
         (x, z) -> Integer.MIN_VALUE,
         (x, z) -> true,
         (meters, z) -> meters / this.worldScale
      );
   }

   /** Resolves one canonical surface column, returning dry when no inland water is mapped. */
   public Sample sample(SphereWorldModel.GeoPoint point, SphereChunkSampler.SurfaceData surface) {
      Objects.requireNonNull(point, "point");
      Objects.requireNonNull(surface, "surface");
      if (!surface.elevationAvailable() || surface.oceanWater() || !waterCandidate(surface.coverClass())) {
         return Sample.dry();
      }
      int earthX = earthBlockX(point.longitude());
      int earthZ = earthBlockZ(point.latitude());
      int rawTerrain = height(surface.elevationMeters());
      InlandHydrologyPlanner.Column column;
      try {
         column = this.planner.sample(earthX, earthZ, rawTerrain);
      } catch (RuntimeException unavailable) {
         if (containsCoverageFailure(unavailable)) {
            // A missing OSM tile must not invalidate an otherwise usable
            // terrain column. The cache/prefetch path can resolve it later.
            return Sample.dry();
         }
         throw unavailable;
      }
      if (!column.wet()) return Sample.dry();
      double waterSurface = (column.water() - this.seaLevel) * this.worldScale;
      double terrainSurface = (column.terrain() - this.seaLevel) * this.worldScale;
      if (!Double.isFinite(waterSurface) || !Double.isFinite(terrainSurface) || terrainSurface > waterSurface) {
         return Sample.dry();
      }
      return new Sample(true, false, terrainSurface, waterSurface, column.terrain(), column.water());
   }

   /** Applies the planner's bed and water levels to an existing canonical surface sample. */
   public SphereChunkSampler.SurfaceData apply(
      SphereWorldModel.GeoPoint point, SphereChunkSampler.SurfaceData surface
   ) {
      Sample sample = this.sample(point, surface);
      if (!sample.hasWater()) return surface;
      return new SphereChunkSampler.SurfaceData(
         sample.terrainSurfaceMeters(), surface.coverClass(), sample.waterSurfaceMeters(),
         SphereChunkSampler.SurfaceData.WaterKind.INLAND
      );
   }

   public void clear() {
      this.planner.clear();
   }

   private int height(double elevationMeters) {
      double blocks = elevationMeters / this.worldScale;
      double height = this.seaLevel + (elevationMeters >= 0.0 ? Math.ceil(blocks) : Math.floor(blocks));
      if (!Double.isFinite(height) || height < Integer.MIN_VALUE || height > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("elevation exceeds supported block heights");
      }
      return (int)height;
   }

   private int earthBlockX(double longitude) {
      return boundedInt(EarthProjection.longitudeToBlockX(longitude, this.worldScale));
   }

   private int earthBlockZ(double latitude) {
      return boundedInt(EarthProjection.latToBlockZ(latitude, this.worldScale));
   }

   private static int boundedInt(double value) {
      if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("Earth projection exceeds integer hydrology grid");
      }
      return (int)Math.floor(value);
   }

   private static boolean waterCandidate(int coverClass) {
      return coverClass == 0 || coverClass == 80;
   }

   private static boolean containsCoverageFailure(Throwable error) {
      for (Throwable current = error; current != null; current = current.getCause()) {
         if (current instanceof OceanCoverageUnavailableException) return true;
      }
      return false;
   }

   public record Sample(boolean hasWater, boolean ocean, double terrainSurfaceMeters,
      double waterSurfaceMeters, int terrainSurface, int waterSurface) {
      static Sample dry() {
         return new Sample(false, false, Double.NaN, Double.NaN, Integer.MIN_VALUE, Integer.MIN_VALUE);
      }
   }

   private final class SphereTerrainSampler implements InlandHydrologyPlanner.TerrainSampler {
      @Override
      public InlandHydrologyPlanner.Elevation sample(int x, int z) {
         return this.sampleAtResolution(x, z, worldScale);
      }

      @Override
      public InlandHydrologyPlanner.Elevation sampleAtResolution(int x, int z, double meters) {
         double latitude = EarthProjection.blockZToLat(z, worldScale);
         double longitude = EarthProjection.blockXToLongitude(x, worldScale);
         SphereWorldModel.GeoPoint earthPoint = new SphereWorldModel.GeoPoint(latitude, longitude, 0.0);
         SphereWorldModel.SphereBlockAddress address = model.blockForGeo(earthPoint);
         SphereChunkSampler.SurfaceData data = terrain.samplePoint(model.surfacePoint(address), Math.max(1.0, meters));
         if (!data.elevationAvailable()) return new InlandHydrologyPlanner.Elevation(Double.NaN, meters / worldScale);
         return new InlandHydrologyPlanner.Elevation(height(data.elevationMeters()),
            Math.max(1.0, meters / worldScale));
      }
   }

   private static final class EarthGridWaterInput implements InlandWaterInput {
      private final GeodeticWaterInput delegate;
      private final double scale;

      private EarthGridWaterInput(GeodeticWaterInput delegate, double scale) {
         this.delegate = delegate;
         this.scale = scale;
      }

      @Override
      public TellusOsmWaterSource.WaterQueryResult waterForAreaWithStatus(
         int minX, int minZ, int maxX, int maxZ, double ignoredScale, int margin, OsmQueryMode mode
      ) {
         return this.delegate.waterForGeoAreaWithStatus(this.bounds(minX, minZ, maxX, maxZ, margin), this.scale, mode);
      }

      @Override
      public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double ignoredScale) {
         return this.delegate.connectedWaterBody(seed, this.scale);
      }

      @Override
      public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double ignoredScale) {
         return this.delegate.connectedWaterfall(seed, this.scale);
      }

      @Override
      public TellusOsmWaterSource.WaterQueryResult connectedWaterBodyWithin(
         OsmWaterFeature seed, double ignoredScale, int maxTiles
      ) {
         return this.delegate.connectedWaterBodyWithin(seed, this.scale, maxTiles);
      }

      @Override
      public TellusOsmWaterSource.WaterQueryResult waterForAreaAtTileWidth(
         int minX, int minZ, int maxX, int maxZ, double ignoredScale, int minTileWidthBlocks
      ) {
         return this.delegate.waterForGeoAreaWithStatus(this.bounds(minX, minZ, maxX, maxZ, 0), this.scale,
            OsmQueryMode.BLOCKING);
      }

      @Override
      public void preload(List<int[]> areas, double ignoredScale, int margin) {
         this.delegate.preload(areas, this.scale, margin);
      }

      private GeodeticBounds bounds(int minX, int minZ, int maxX, int maxZ, int margin) {
         int x0 = Math.min(minX, maxX) - margin;
         int x1 = Math.max(minX, maxX) + margin;
         int z0 = Math.min(minZ, maxZ) - margin;
         int z1 = Math.max(minZ, maxZ) + margin;
         double south = EarthProjection.blockZToLat(z1, this.scale);
         double north = EarthProjection.blockZToLat(z0, this.scale);
         double lonSpan = (x1 - (double)x0) * this.scale / EarthProjection.METERS_PER_DEGREE;
         if (lonSpan >= 180.0) return new GeodeticBounds(south, -180.0, north, 180.0);
         double west = normalizeLongitude(EarthProjection.blockXToLongitude(x0, this.scale));
         double east = normalizeLongitude(EarthProjection.blockXToLongitude(x1, this.scale));
         return new GeodeticBounds(south, west, north, east);
      }

      private static double normalizeLongitude(double longitude) {
         double normalized = longitude % 360.0;
         if (normalized > 180.0) normalized -= 360.0;
         if (normalized <= -180.0) normalized += 360.0;
         return normalized;
      }
   }

   interface GeodeticWaterInput {
      TellusOsmWaterSource.WaterQueryResult waterForGeoAreaWithStatus(
         GeodeticBounds bounds, double worldScale, OsmQueryMode mode
      );

      TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double worldScale);

      TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double worldScale);

      default TellusOsmWaterSource.WaterQueryResult connectedWaterBodyWithin(
         OsmWaterFeature seed, double worldScale, int maxTiles
      ) {
         return this.connectedWaterBody(seed, worldScale);
      }

      default void preload(List<int[]> areas, double worldScale, int margin) {
      }
   }

   private static final class TellusGeodeticWaterInput implements GeodeticWaterInput {
      private final TellusOsmWaterSource source;

      private TellusGeodeticWaterInput(TellusOsmWaterSource source) {
         this.source = source;
      }

      @Override
      public TellusOsmWaterSource.WaterQueryResult waterForGeoAreaWithStatus(
         GeodeticBounds bounds, double worldScale, OsmQueryMode mode
      ) {
         return this.source.waterForGeoAreaWithStatus(bounds, worldScale, mode);
      }

      @Override
      public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double worldScale) {
         return this.source.connectedWaterBody(seed, worldScale);
      }

      @Override
      public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double worldScale) {
         return this.source.connectedWaterfall(seed, worldScale);
      }

      @Override
      public TellusOsmWaterSource.WaterQueryResult connectedWaterBodyWithin(
         OsmWaterFeature seed, double worldScale, int maxTiles
      ) {
         return this.source.connectedWaterBodyWithin(seed, worldScale, maxTiles);
      }

      @Override
      public void preload(List<int[]> areas, double worldScale, int margin) {
         this.source.preloadAreas(areas, worldScale, margin);
      }
   }
}
