package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import com.yucareux.tellus.worldgen.global.CubeNetFeatureRasterizer;
import com.yucareux.tellus.worldgen.global.CubeNetFeatureProjector;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
   void aCrossFaceSegmentIsQuarantinedUntilItHasAnExplicitSeamSplit() {
      RoadFeature road = new RoadFeature(8L, RoadClass.MAIN, RoadMode.NORMAL, 0, "primary",
         new double[]{0.0, 90.0}, new double[]{0.0, 0.0});
      var face = CubeNetLayout.Face.POS_X;
      var cells = CubeNetFeatureRasterizer.roadCells(MODEL, LAYOUT, road, face, 0, 0, LAYOUT.faceSize(), LAYOUT.faceSize(), 1000.0);
      assertTrue(cells.isEmpty());
   }
}
