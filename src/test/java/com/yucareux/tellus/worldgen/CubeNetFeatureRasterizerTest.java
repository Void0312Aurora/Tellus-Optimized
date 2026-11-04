package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmBuildingKind;
import com.yucareux.tellus.world.data.osm.OsmBuildingMetadata;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import com.yucareux.tellus.worldgen.global.CubeNetFeatureRasterizer;
import com.yucareux.tellus.worldgen.global.CubeNetFeatureProjector;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubeNetFeatureRasterizerTest {
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1000.0);
   private static final CubeNetLayout LAYOUT = CubeNetLayout.standard(MODEL.config().faceBlocks());

   @Test
   void roadCellsStayInTheCanonicalFaceWindow() {
      RoadFeature road = new RoadFeature(7L, RoadClass.NORMAL, RoadMode.NORMAL, 0, "residential",
         new double[]{89.0, 89.1}, new double[]{0.0, 0.0});
      var face = CubeNetFeatureProjector.project(MODEL, LAYOUT, 0.0, 89.0).orElseThrow();
      var cells = CubeNetFeatureRasterizer.roadCells(MODEL, LAYOUT, road, CubeNetLayout.Face.of(face.face()),
         (int)Math.floor(face.faceX()) - 8, (int)Math.floor(face.faceZ()) - 8,
         (int)Math.floor(face.faceX()) + 8, (int)Math.floor(face.faceZ()) + 8, 1000.0);
      assertFalse(cells.isEmpty());
      assertTrue(cells.stream().allMatch(cell -> cell.face() == face.face()));
   }

   @Test
   void aCrossFaceSegmentIsSplitInsteadOfDrawingAcrossThePlanarNetGap() {
      RoadFeature road = new RoadFeature(8L, RoadClass.MAIN, RoadMode.NORMAL, 0, "primary",
         new double[]{0.0, 90.0}, new double[]{0.0, 0.0});
      var start = CubeNetFeatureProjector.project(MODEL, LAYOUT, 0.0, 0.0).orElseThrow();
      var end = CubeNetFeatureProjector.project(MODEL, LAYOUT, 0.0, 90.0).orElseThrow();
      assertNotEquals(start.face(), end.face());
      var face = CubeNetLayout.Face.of(start.face());
      var cells = CubeNetFeatureRasterizer.roadCells(MODEL, LAYOUT, road, face, 0, 0,
         LAYOUT.faceSize(), LAYOUT.faceSize(), 1000.0);
      assertTrue(cells.stream().allMatch(cell -> cell.face() == face.index()));
   }

   @Test
   void aCrossFaceBuildingUsesGeodeticCellMembershipPerFace() {
      double[] crossing = findFaceChange();
      double lat = crossing[0], lon = crossing[1], delta = crossing[2];
      var left = CubeNetFeatureProjector.project(MODEL, LAYOUT, lat, lon).orElseThrow();
      var right = CubeNetFeatureProjector.project(MODEL, LAYOUT, lat, lon + delta).orElseThrow();
      assertNotEquals(left.face(), right.face());
      OsmBuildingFeature building = new OsmBuildingFeature(OsmBuildingKind.FOOTPRINT, 99L,
         "cross-face", false, new OsmBuildingMetadata("residential", null, null, null, 1,
            null, null, null, null, null), 6.0, 0.0,
         new double[][]{{lon, lon + delta, lon + delta, lon, lon}},
         new double[][]{{lat - delta, lat - delta, lat + delta, lat + delta, lat - delta}});
      CubeNetLayout.Face face = CubeNetLayout.Face.of(left.face());
      int minX = Math.max(0, (int)Math.floor(left.faceX()) - 8);
      int minZ = Math.max(0, (int)Math.floor(left.faceZ()) - 8);
      var cells = CubeNetFeatureRasterizer.buildingCells(MODEL, LAYOUT, building, face,
         minX, minZ, minX + 32, minZ + 32);
      assertFalse(cells.isEmpty());
      assertTrue(cells.stream().allMatch(cell -> cell.face() == face.index()));
   }

   private static double[] findFaceChange() {
      for (double lat = -80.0; lat <= 80.0; lat += 1.0) {
         for (double lon = -179.0; lon < 180.0; lon += 1.0) {
            var first = CubeNetFeatureProjector.project(MODEL, LAYOUT, lat, lon).orElse(null);
            var second = CubeNetFeatureProjector.project(MODEL, LAYOUT, lat, lon + 0.5).orElse(null);
            if (first != null && second != null && first.face() != second.face()) {
               return new double[]{lat, lon, 0.5};
            }
         }
      }
      throw new AssertionError("test grid did not find a cube-net face seam");
   }
}
