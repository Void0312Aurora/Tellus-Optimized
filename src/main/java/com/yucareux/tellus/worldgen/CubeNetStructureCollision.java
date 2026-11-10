package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetFeatureRasterizer;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.List;
import java.util.Optional;

/** One coordinate boundary for native structure boxes and geodetic feature footprints. */
final class CubeNetStructureCollision {
   private CubeNetStructureCollision() { }

   record Rectangle(int minX, int minZ, int maxX, int maxZ) { }

   /** Whole-envelope admission per enabled family; no prefix query, invented clear result, or unused water fetch. */
   static SphereChunkSampler.FeatureData boundedFeatures(GeodeticBounds bounds, boolean buildings, boolean roads,
      TellusOsmBuildingSource buildingSource, TellusOsmRoadSource roadSource) {
      boolean buildingAdmitted = buildings && buildingSource.canQueryGeoAreaWithinBudget(bounds,256);
      boolean roadAdmitted = roads && roadSource.canQueryGeoAreaWithinBudget(bounds,256);
      return new SphereChunkSampler.FeatureData(
         buildingAdmitted ? buildingSource.buildingsForGeoAreaWithStatus(bounds,OsmQueryMode.NON_BLOCKING)
            : new TellusOsmBuildingSource.BuildingQueryResult(List.of(),buildings),
         roadAdmitted ? roadSource.roadsForGeoAreaWithStatus(bounds,OsmQueryMode.NON_BLOCKING)
            : new TellusOsmRoadSource.RoadQueryResult(List.of(),roads),
         new TellusOsmWaterSource.WaterQueryResult(List.of(),TellusOsmWaterSource.CoverageStatus.PENDING,0),
         roadAdmitted ? roadSource.roadAreasForGeoAreaWithStatus(bounds,OsmQueryMode.NON_BLOCKING)
            : new TellusOsmRoadSource.RoadAreaQueryResult(List.of(),roads));
   }

   /** Inclusive engine block rectangle -> inclusive canonical cell rectangle on one face. */
   static Optional<Rectangle> faceBounds(CubeNetLayout layout, CubeNetLayout.Face face, Rectangle engine) {
      if (engine.minX() > engine.maxX() || engine.minZ() > engine.maxZ()) return Optional.empty();
      var placement = layout.placement(face);
      long minU = Long.MAX_VALUE, minV = Long.MAX_VALUE, maxU = Long.MIN_VALUE, maxV = Long.MIN_VALUE;
      for (int x : new int[]{engine.minX(), engine.maxX()}) {
         for (int z : new int[]{engine.minZ(), engine.maxZ()}) {
            double dx = x + 0.5 - (double)placement.originX() * layout.faceSize();
            double dz = z + 0.5 - (double)placement.originZ() * layout.faceSize();
            long u = (long)Math.floor(placement.uAxisX() * dx + placement.uAxisZ() * dz);
            long v = (long)Math.floor(placement.vAxisX() * dx + placement.vAxisZ() * dz);
            minU = Math.min(minU, u); maxU = Math.max(maxU, u);
            minV = Math.min(minV, v); maxV = Math.max(maxV, v);
         }
      }
      minU = Math.max(0L, minU); minV = Math.max(0L, minV);
      maxU = Math.min(layout.faceSize() - 1L, maxU); maxV = Math.min(layout.faceSize() - 1L, maxV);
      if (minU > maxU || minV > maxV) return Optional.empty();
      return Optional.of(new Rectangle((int)minU, (int)minV, (int)maxU, (int)maxV));
   }

   /** Feature queries use metres, not engine blocks. Cover the full structure, not just its start chunk. */
   static double queryMarginMeters(int chunkMinX, int chunkMinZ, Rectangle box, double worldScale) {
      double extent = Math.max(Math.max(Math.abs((double)box.minX() - chunkMinX - 8.0),
            Math.abs((double)box.maxX() - chunkMinX - 8.0)),
         Math.max(Math.abs((double)box.minZ() - chunkMinZ - 8.0),
            Math.abs((double)box.maxZ() - chunkMinZ - 8.0)));
      // The spherical sampler already covers the chunk itself. A two-axis path and the
      // conformal chart's bounded derivative conservatively cover this extra rectangle.
      return Math.max(96.0, 4.0 * extent * worldScale);
   }

   static boolean collides(SphereWorldModel model, CubeNetLayout layout, Rectangle engine,
      SphereChunkSampler.FeatureData data, boolean roads, boolean buildings,
      CubeNetStructurePlacement.ColumnPredicate landmarkAtEarthGrid) {
      if (!roads && !buildings) return false;
      // A structure can reach an ordinary planar neighbour. Intersect each chart separately;
      // never rasterize across the gap, reinterpret an apron as an owner, or multiply blockX by 16 again.
      for (var face : CubeNetLayout.Face.values()) {
         var intersection = faceBounds(layout, face, engine);
         if (intersection.isEmpty()) continue;
         var box = intersection.orElseThrow();
         if (buildings && landmarkAtEarthGrid != null) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) for (int x = box.minX(); x <= box.maxX(); x++) {
               var point = model.exactSurfacePoint(face.index(), x + 0.5, z + 0.5);
               double earthX = EarthProjection.longitudeToBlockX(point.longitude(), model.config().worldScale());
               double earthZ = EarthProjection.latToBlockZ(point.latitude(), model.config().worldScale());
               if (Double.isFinite(earthX) && Double.isFinite(earthZ)
                  && earthX >= Integer.MIN_VALUE && earthX < (double)Integer.MAX_VALUE + 1.0
                  && earthZ >= Integer.MIN_VALUE && earthZ < (double)Integer.MAX_VALUE + 1.0
                  && landmarkAtEarthGrid.test((int)Math.floor(earthX), (int)Math.floor(earthZ))) return true;
            }
         }
         // null denotes an unadmitted whole-envelope source query, not an empty/clear footprint.
         // Known landmarks above remain independently authoritative; OSM stays unknown/keep.
         if (data == null) continue;
         if (buildings) for (var building : data.buildings().features()) {
            if (!CubeNetFeatureRasterizer.buildingCells(model, layout, building, face,
               box.minX(), box.minZ(), box.maxX(), box.maxZ()).isEmpty()) return true;
         }
         if (roads) {
            for (var road : data.roads().features()) {
               if (!CubeNetFeatureRasterizer.roadCells(model, layout, road, face,
                  box.minX(), box.minZ(), box.maxX(), box.maxZ(), model.config().worldScale()).isEmpty()) return true;
            }
            for (var area : data.roadAreas().features()) {
               if (!CubeNetFeatureRasterizer.roadAreaCells(model, layout, area, face,
                  box.minX(), box.minZ(), box.maxX(), box.maxZ()).isEmpty()) return true;
            }
         }
      }
      return false;
   }
}
