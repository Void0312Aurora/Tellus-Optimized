package com.yucareux.tellus.worldgen.global;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadSurfaceStyle;
import com.yucareux.tellus.worldgen.ScanlinePolygonRasterizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Rasterizes OSM geometry in canonical face coordinates. It deliberately
 * refuses a segment or polygon that crosses a face: a later seam splitter can
 * opt in once its ownership policy is explicit, while this path never draws a
 * long false line across the planar net.
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
         var start = CubeNetFeatureProjector.project(model, layout, road.latAt(i - 1), road.lonAt(i - 1)).orElse(null);
         var end = CubeNetFeatureProjector.project(model, layout, road.latAt(i), road.lonAt(i)).orElse(null);
         if (start == null || end == null || start.face() != face.index() || end.face() != face.index()) continue;
         addSegmentCells(cells, face.index(), start.faceX(), start.faceZ(), end.faceX(), end.faceZ(),
            radius, minX, minZ, maxX, maxZ);
      }
      return List.copyOf(cells);
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
            if (projected == null || projected.face() != face.index()) return List.of();
            ringsX[part][point] = projected.faceX();
            ringsZ[part][point] = projected.faceZ();
         }
      }
      List<FaceCell> cells = new ArrayList<>();
      ScanlinePolygonRasterizer.fill(ringsX, ringsZ, minX, minZ, maxX, maxZ,
         (x, z) -> cells.add(new FaceCell(face.index(), x, z)));
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
