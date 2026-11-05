package com.yucareux.tellus.worldgen.global;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.RoadAreaFeature;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadSurfaceStyle;
import com.yucareux.tellus.worldgen.ScanlinePolygonRasterizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Rasterizes OSM geometry in canonical face coordinates. Road segments are
 * sampled along the geodetic path and split at face ownership changes. A
 * building that crosses a face seam is rasterized by testing canonical cell
 * centres against the original geodetic footprint, so it cannot bridge the
 * planar gap between faces.
 */
public final class CubeNetFeatureRasterizer {
   private CubeNetFeatureRasterizer() { }

   public static List<FaceCell> roadCells(
      SphereWorldModel model, CubeNetLayout layout, RoadFeature road,
      CubeNetLayout.Face face, int minX, int minZ, int maxX, int maxZ, double worldScale
   ) {
      Objects.requireNonNull(road, "road");
      if (minX > maxX || minZ > maxZ) return List.of();
      int width = Math.max(1, RoadSurfaceStyle.effectiveRoadWidth(road, road.roadClass().baseWidth(), worldScale));
      double radius = Math.max(0.5, width * 0.5);
      LinkedHashSet<FaceCell> cells = new LinkedHashSet<>();
      for (int i = 1; i < road.pointCount(); i++) {
         rasterizeGeodeticSegment(model, layout, road.latAt(i - 1), road.lonAt(i - 1),
            road.latAt(i), road.lonAt(i), face.index(), radius, minX, minZ, maxX, maxZ, cells);
      }
      return List.copyOf(cells);
   }

   /**
    * Returns the cells occupied by Tellus's dashed lane dividers. The station
    * and lateral distance are measured in the canonical face coordinates after
    * geodetic projection, so the same road marking rule can be reused without
    * treating longitude/latitude as a flat world plane.
    */
   public static List<FaceCell> roadMarkingCells(
      SphereWorldModel model, CubeNetLayout layout, RoadFeature road,
      CubeNetLayout.Face face, int minX, int minZ, int maxX, int maxZ, double worldScale
   ) {
      Objects.requireNonNull(road, "road");
      if (minX > maxX || minZ > maxZ) return List.of();
      int width = Math.max(1, RoadSurfaceStyle.effectiveRoadWidth(road, road.roadClass().baseWidth(), worldScale));
      if (width < 5 || !road.laneMarkings()) return List.of();
      LinkedHashSet<FaceCell> cells = new LinkedHashSet<>();
      double station = 0.0;
      for (int i = 1; i < road.pointCount(); i++) {
         var start = CubeNetFeatureProjector.project(model, layout, road.latAt(i - 1), road.lonAt(i - 1)).orElse(null);
         var end = CubeNetFeatureProjector.project(model, layout, road.latAt(i), road.lonAt(i)).orElse(null);
         double segmentLength = start != null && end != null && start.face() == end.face()
            ? Math.hypot(end.faceX() - start.faceX(), end.faceZ() - start.faceZ())
            : geodeticLengthBlocks(road.latAt(i - 1), road.lonAt(i - 1), road.latAt(i), road.lonAt(i), worldScale);
         if (start != null && end != null && start.face() == face.index() && end.face() == face.index()) {
            rasterizeMarkingSegment(road, width, station, start.faceX(), start.faceZ(), end.faceX(), end.faceZ(),
               face.index(), minX, minZ, maxX, maxZ, cells);
         }
         station += segmentLength;
      }
      return List.copyOf(cells);
   }

   private static void rasterizeMarkingSegment(
      RoadFeature road, int roadWidth, double stationStart,
      double x1, double z1, double x2, double z2, int targetFace,
      int minX, int minZ, int maxX, int maxZ, LinkedHashSet<FaceCell> cells
   ) {
      double dx = x2 - x1;
      double dz = z2 - z1;
      double lengthSq = dx * dx + dz * dz;
      if (lengthSq <= 1.0E-12) return;
      double length = Math.sqrt(lengthSq);
      double halfWidth = Math.max(0.5, (roadWidth - 1) * 0.5);
      int fromX = Math.max(minX, (int)Math.floor(Math.min(x1, x2) - halfWidth - 1.0));
      int toX = Math.min(maxX, (int)Math.ceil(Math.max(x1, x2) + halfWidth + 1.0));
      int fromZ = Math.max(minZ, (int)Math.floor(Math.min(z1, z2) - halfWidth - 1.0));
      int toZ = Math.min(maxZ, (int)Math.ceil(Math.max(z1, z2) + halfWidth + 1.0));
      double radiusSq = halfWidth * halfWidth + 1.0E-6;
      for (int z = fromZ; z <= toZ; z++) for (int x = fromX; x <= toX; x++) {
         double px = x + 0.5;
         double pz = z + 0.5;
         double t = Math.max(0.0, Math.min(1.0, ((px - x1) * dx + (pz - z1) * dz) / lengthSq));
         double projectionX = x1 + t * dx;
         double projectionZ = z1 + t * dz;
         double distanceX = px - projectionX;
         double distanceZ = pz - projectionZ;
         double distanceSq = distanceX * distanceX + distanceZ * distanceZ;
         if (distanceSq <= radiusSq) {
            double lateral = ((px - projectionX) * -dz + (pz - projectionZ) * dx) / length;
            double station = stationStart + t * length;
            if (RoadSurfaceStyle.shouldDrawLaneMarking(road, roadWidth, station, lateral,
               Math.sqrt(distanceSq), 0.45)) {
               cells.add(new FaceCell(targetFace, x, z));
            }
         }
      }
   }

   private static double geodeticLengthBlocks(double lat1, double lon1, double lat2, double lon2, double worldScale) {
      double deltaLon = lon2 - lon1;
      if (deltaLon > 180.0) deltaLon -= 360.0;
      if (deltaLon < -180.0) deltaLon += 360.0;
      double meanLat = Math.toRadians((lat1 + lat2) * 0.5);
      double meters = Math.hypot((lat2 - lat1) * 111_320.0, deltaLon * 111_320.0 * Math.cos(meanLat));
      return meters * worldScale;
   }

   private static void rasterizeGeodeticSegment(
      SphereWorldModel model, CubeNetLayout layout, double lat1, double lon1, double lat2, double lon2,
      int targetFace, double radius, int minX, int minZ, int maxX, int maxZ, LinkedHashSet<FaceCell> cells
   ) {
      double deltaLon = lon2 - lon1;
      if (deltaLon > 180.0) deltaLon -= 360.0;
      if (deltaLon < -180.0) deltaLon += 360.0;
      int steps = Math.max(8, Math.min(128, (int)Math.ceil(Math.max(Math.abs(lat2 - lat1), Math.abs(deltaLon)) * 2.0)));
      var previous = CubeNetFeatureProjector.project(model, layout, lat1, lon1).orElse(null);
      for (int step = 1; step <= steps && previous != null; step++) {
         double t = step / (double)steps;
         var current = CubeNetFeatureProjector.project(model, layout, lat1 + (lat2 - lat1) * t,
            lon1 + deltaLon * t).orElse(null);
         if (current != null && previous.face() == targetFace && current.face() == targetFace) {
            addSegmentCells(cells, targetFace, previous.faceX(), previous.faceZ(), current.faceX(), current.faceZ(),
               radius, minX, minZ, maxX, maxZ);
         }
         previous = current;
      }
   }

   public static List<FaceCell> buildingCells(
      SphereWorldModel model, CubeNetLayout layout, OsmBuildingFeature building,
      CubeNetLayout.Face face, int minX, int minZ, int maxX, int maxZ
   ) {
      Objects.requireNonNull(building, "building");
      if (minX > maxX || minZ > maxZ) return List.of();
      double[][] ringsX = new double[building.partCount()][];
      double[][] ringsZ = new double[building.partCount()][];
      for (int part = 0; part < building.partCount(); part++) {
         ringsX[part] = new double[building.pointCount(part)];
         ringsZ[part] = new double[building.pointCount(part)];
         for (int point = 0; point < building.pointCount(part); point++) {
            var projected = CubeNetFeatureProjector.project(model, layout,
               building.latAt(part, point), building.lonAt(part, point)).orElse(null);
            if (projected == null) return List.of();
            if (projected.face() != face.index()) {
               return buildingCellsByGeodeticMembership(model, building, face, minX, minZ, maxX, maxZ);
            }
            ringsX[part][point] = projected.faceX();
            ringsZ[part][point] = projected.faceZ();
         }
      }
      List<FaceCell> cells = new ArrayList<>();
      ScanlinePolygonRasterizer.fill(ringsX, ringsZ, minX, minZ, maxX, maxZ,
         (x, z) -> cells.add(new FaceCell(face.index(), x, z)));
      return List.copyOf(cells);
   }

   public static List<FaceCell> roadAreaCells(
      SphereWorldModel model, CubeNetLayout layout, RoadAreaFeature area,
      CubeNetLayout.Face face, int minX, int minZ, int maxX, int maxZ
   ) {
      Objects.requireNonNull(area, "area");
      if (minX > maxX || minZ > maxZ) return List.of();
      double[][] ringsX = new double[area.partCount()][];
      double[][] ringsZ = new double[area.partCount()][];
      for (int part = 0; part < area.partCount(); part++) {
         ringsX[part] = new double[area.pointCount(part)];
         ringsZ[part] = new double[area.pointCount(part)];
         for (int point = 0; point < area.pointCount(part); point++) {
            var projected = CubeNetFeatureProjector.project(model, layout,
               area.latAt(part, point), area.lonAt(part, point)).orElse(null);
            if (projected == null) return List.of();
            if (projected.face() != face.index()) {
               return roadAreaCellsByGeodeticMembership(model, area, face, minX, minZ, maxX, maxZ);
            }
            ringsX[part][point] = projected.faceX();
            ringsZ[part][point] = projected.faceZ();
         }
      }
      List<FaceCell> cells = new ArrayList<>();
      ScanlinePolygonRasterizer.fill(ringsX, ringsZ, minX, minZ, maxX, maxZ,
         (x, z) -> cells.add(new FaceCell(face.index(), x, z)));
      return List.copyOf(cells);
   }

   private static List<FaceCell> roadAreaCellsByGeodeticMembership(
      SphereWorldModel model, RoadAreaFeature area, CubeNetLayout.Face face,
      int minX, int minZ, int maxX, int maxZ
   ) {
      List<FaceCell> cells = new ArrayList<>();
      for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
         SphereWorldModel.GeoPoint point = model.exactSurfacePoint(face.index(), x + 0.5, z + 0.5);
         if (area.containsLonLat(point.longitude(), point.latitude())) {
            cells.add(new FaceCell(face.index(), x, z));
         }
      }
      return List.copyOf(cells);
   }

   /**
    * Cell-centre fallback for a footprint whose vertices occupy more than one
    * canonical face. This keeps each face's raster independent and naturally
    * handles a footprint that only clips a small seam strip. OSM building
    * rings are already geodetic, so no planar-net interpolation is introduced.
    */
   private static List<FaceCell> buildingCellsByGeodeticMembership(
      SphereWorldModel model, OsmBuildingFeature building, CubeNetLayout.Face face,
      int minX, int minZ, int maxX, int maxZ
   ) {
      List<FaceCell> cells = new ArrayList<>();
      for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
         SphereWorldModel.GeoPoint point = model.exactSurfacePoint(face.index(), x + 0.5, z + 0.5);
         if (building.containsLonLat(point.longitude(), point.latitude())) {
            cells.add(new FaceCell(face.index(), x, z));
         }
      }
      return List.copyOf(cells);
   }

   private static void addSegmentCells(
      LinkedHashSet<FaceCell> cells, int face, double x1, double z1, double x2, double z2,
      double radius, int minX, int minZ, int maxX, int maxZ
   ) {
      int fromX = Math.max(minX, (int)Math.floor(Math.min(x1, x2) - radius));
      int toX = Math.min(maxX, (int)Math.ceil(Math.max(x1, x2) + radius));
      int fromZ = Math.max(minZ, (int)Math.floor(Math.min(z1, z2) - radius));
      int toZ = Math.min(maxZ, (int)Math.ceil(Math.max(z1, z2) + radius));
      double dx = x2 - x1, dz = z2 - z1, lengthSq = dx * dx + dz * dz;
      double radiusSq = radius * radius;
      for (int z = fromZ; z <= toZ; z++) for (int x = fromX; x <= toX; x++) {
         double px = x + 0.5, pz = z + 0.5;
         double t = lengthSq <= 1.0E-12 ? 0.0 : Math.max(0.0, Math.min(1.0,
            ((px - x1) * dx + (pz - z1) * dz) / lengthSq));
         double ex = x1 + t * dx, ez = z1 + t * dz;
         if (Math.hypot(px - ex, pz - ez) <= radius) cells.add(new FaceCell(face, x, z));
      }
   }

   public record FaceCell(int face, int x, int z) {
      public FaceCell {
         if (face < 0 || face >= SphereWorldModel.FACE_COUNT) throw new IllegalArgumentException("face");
      }
   }
}
