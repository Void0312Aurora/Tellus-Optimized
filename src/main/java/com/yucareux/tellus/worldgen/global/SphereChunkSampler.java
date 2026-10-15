package com.yucareux.tellus.worldgen.global;

import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Demand-driven spherical chunk data sampling, before Minecraft block filling.
 * No projected Minecraft coordinates enter the external data readers.
 */
public final class SphereChunkSampler {
   private final SphereWorldModel model;
   private final PointSampler sampler;

   public SphereChunkSampler(SphereWorldModel model, PointSampler sampler) {
      this.model = Objects.requireNonNull(model, "model");
      this.sampler = Objects.requireNonNull(sampler, "sampler");
   }

   public static SphereChunkSampler fromSources(
      SphereWorldModel model, TellusElevationSource elevation, TellusLandCoverSource cover
   ) {
      return fromSources(model, elevation, cover, null);
   }

   public static SphereChunkSampler fromSources(
      SphereWorldModel model, TellusElevationSource elevation, TellusLandCoverSource cover, TellusLandMaskSource landMask
   ) {
      Objects.requireNonNull(elevation, "elevation");
      Objects.requireNonNull(cover, "cover");
      return new SphereChunkSampler(model, (point, resolutionMeters) -> {
         int coverClass = cover.sampleCoverClass(point, model.config().worldScale(), resolutionMeters);
         var mask = landMask == null ? TellusLandMaskSource.LandMaskSample.unknown()
            : landMask.sampleLandMask(point, model.config().worldScale());
         boolean ocean = mask.known() && !mask.land() && (coverClass == 0 || coverClass == 80);
         TellusElevationSource.ResolvedElevationSample sample = elevation.sampleResolvedPreviewElevationMeters(
            point, model.config().worldScale(), ocean || (!mask.known() && coverClass == 80), resolutionMeters
         );
         // Neither cover=80 nor a bathymetry sample proves ocean membership.
         double waterSurface = ocean && !sample.mapterhornLandOverride()
            && sample.elevationMeters() < 0.0 ? 0.0 : Double.NaN;
         return new SurfaceData(sample.elevationMeters(), coverClass, waterSurface);
      });
   }

   /** Samples exactly 16 by 16 block centers, in z-major order. */
   public TerrainGrid sampleTerrain(SphereWorldModel.SphereChunkAddress address) {
      var chart = this.model.prepareChart(this.model.chartForChunk(address));
      List<Column> columns = new ArrayList<>(256);
      for (int z = 0; z < 16; z++) {
         for (int x = 0; x < 16; x++) {
            SphereWorldModel.GeoPoint point = this.model.surfacePoint(chart, new SphereWorldModel.SphereBlockAddress(
               address.face(), address.blockX() + x, address.blockZ() + z
            ));
            SurfaceData data = Objects.requireNonNull(
               this.sampler.sample(point, this.model.config().worldScale()), "surfaceData"
            );
            columns.add(new Column(point, data));
         }
      }
      return new TerrainGrid(address, columns);
   }

   public SurfaceData samplePoint(SphereWorldModel.GeoPoint point) {
      return Objects.requireNonNull(this.sampler.sample(Objects.requireNonNull(point, "point"),
         this.model.config().worldScale()), "surfaceData");
   }

   /**
    * Conservative spherical cap covering this chunk and its feature margin.
    * The active face mapping has a bounded local derivative; Manhattan
    * distance from the center to a corner is 16 block units.
    */
   public GeodeticBounds featureBounds(SphereWorldModel.SphereChunkAddress address, double marginMeters) {
      this.model.chartForChunk(address);
      if (!(marginMeters >= 0.0) || !Double.isFinite(marginMeters)) {
         throw new IllegalArgumentException("marginMeters must be finite and non-negative");
      }
      return GeodeticBounds.around(
         this.model.exactSurfacePoint(address), 32.0 * this.model.nominalBlockScaleMeters() + marginMeters
      );
   }

   /** Fetches vector geometry in geodetic form; building/road block filling is a later step. */
   public FeatureData sampleFeatures(
      SphereWorldModel.SphereChunkAddress address, double marginMeters, OsmQueryMode mode,
      TellusOsmBuildingSource buildings, TellusOsmRoadSource roads, TellusOsmWaterSource water
   ) {
      GeodeticBounds bounds = this.featureBounds(address, marginMeters);
      return new FeatureData(
         buildings.buildingsForGeoAreaWithStatus(bounds, mode),
         roads.roadsForGeoAreaWithStatus(bounds, mode),
         water.waterForGeoAreaWithStatus(bounds, this.model.config().worldScale(), mode)
      );
   }

   @FunctionalInterface
   public interface PointSampler {
      SurfaceData sample(SphereWorldModel.GeoPoint point, double resolutionMeters);
   }

   public record SurfaceData(double elevationMeters, int coverClass, double waterSurfaceMeters) {
      public SurfaceData(double elevationMeters, int coverClass) {
         this(elevationMeters, coverClass, Double.NaN);
      }
      public SurfaceData {
         if (!Double.isNaN(waterSurfaceMeters)
            && (!Double.isFinite(waterSurfaceMeters) || !Double.isFinite(elevationMeters)
               || waterSurfaceMeters < elevationMeters)) {
            throw new IllegalArgumentException("water surface requires finite terrain at or below it");
         }
      }
      public boolean elevationAvailable() { return Double.isFinite(this.elevationMeters); }
      public boolean waterAvailable() { return Double.isFinite(this.waterSurfaceMeters); }
   }

   public record Column(SphereWorldModel.GeoPoint point, SurfaceData data) { }

   public record TerrainGrid(SphereWorldModel.SphereChunkAddress address, List<Column> columns) {
      public TerrainGrid {
         Objects.requireNonNull(address, "address");
         columns = List.copyOf(columns);
         if (columns.size() != 256) throw new IllegalArgumentException("a terrain grid needs exactly 256 columns");
      }
      public Column column(int x, int z) {
         if (x < 0 || x >= 16 || z < 0 || z >= 16) throw new IndexOutOfBoundsException();
         return this.columns.get(z * 16 + x);
      }
   }

   public record FeatureData(
      TellusOsmBuildingSource.BuildingQueryResult buildings,
      TellusOsmRoadSource.RoadQueryResult roads,
      TellusOsmWaterSource.WaterQueryResult water
   ) { }
}
