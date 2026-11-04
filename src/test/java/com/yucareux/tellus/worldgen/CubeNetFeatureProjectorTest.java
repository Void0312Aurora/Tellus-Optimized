package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetFeatureProjector;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubeNetFeatureProjectorTest {
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1000.0);
   private static final CubeNetLayout LAYOUT = CubeNetLayout.standard(MODEL.config().faceBlocks());

   @Test
   void dateLineLongitudesResolveToTheSameCanonicalOwner() {
      var first = CubeNetFeatureProjector.project(MODEL, LAYOUT, 10.0, 180.0).orElseThrow();
      var second = CubeNetFeatureProjector.project(MODEL, LAYOUT, 10.0, -180.0).orElseThrow();
      assertEquals(first.face(), second.face());
      assertEquals(first.faceX(), second.faceX(), 1.0E-9);
      assertEquals(first.faceZ(), second.faceZ(), 1.0E-9);
   }

   @Test
   void projectionCarriesCanonicalAndPlanarCoordinatesTogether() {
      var projected = CubeNetFeatureProjector.project(MODEL, LAYOUT, 45.0, 90.0).orElseThrow();
      assertTrue(projected.faceX() >= 0.0 && projected.faceX() <= LAYOUT.faceSize());
      assertTrue(projected.faceZ() >= 0.0 && projected.faceZ() <= LAYOUT.faceSize());
      var owner = MODEL.blockForGeo(projected.point());
      assertEquals(projected.face(), owner.face());
   }
}
