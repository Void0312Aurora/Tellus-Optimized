package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CubeNetUndergroundPolicyTest {
   @Test
   void elevatedInlandWaterProtectsItsOwnWaterLevel() {
      assertEquals(296, CubeNetUndergroundPolicy.floodGuardY(299, 300, false));
      assertEquals(-104, CubeNetUndergroundPolicy.floodGuardY(-101, -100, false));
   }

   @Test
   void deepOceanKeepsASeabedCoverInsteadOfUsingOnlySeaLevel() {
      assertEquals(-203, CubeNetUndergroundPolicy.floodGuardY(-200, 64, true));
      assertEquals(60, CubeNetUndergroundPolicy.floodGuardY(63, 64, true));
   }

   @Test
   void depthLimitIsSurfaceRelativeAndKeepsTheDimensionFloor() {
      assertEquals(744, CubeNetUndergroundPolicy.generationFloorY(1000, 256, -64));
      assertEquals(-64, CubeNetUndergroundPolicy.generationFloorY(100, 256, -64));
      assertEquals(100, CubeNetUndergroundPolicy.generationFloorY(100, 0, -64));
   }
}
