package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubeNetStructurePlacementTest {
   @Test
   void detectsSampledSteepFootprints() {
      assertFalse(CubeNetStructurePlacement.surfaceTooSteep(0, 8, 0, 8, 4, 6, (x, z) -> 64));
      assertTrue(CubeNetStructurePlacement.surfaceTooSteep(0, 8, 0, 8, 4, 6, (x, z) -> x >= 8 ? 72 : 64));
   }

   @Test
   void samplesTheFarFootprintEdgeWhenStrideDoesNotDivideIt() {
      assertTrue(CubeNetStructurePlacement.surfaceTooSteep(0, 9, 0, 9, 4, 6, (x, z) -> x == 9 ? 72 : 64));
   }

   @Test
   void detectsWaterAtInteriorOrFarEdge() {
      assertFalse(CubeNetStructurePlacement.footprintTouchesWater(0, 8, 0, 8, 4, (x, z) -> false));
      assertTrue(CubeNetStructurePlacement.footprintTouchesWater(0, 8, 0, 8, 4, (x, z) -> x == 8 && z == 8));
      assertTrue(CubeNetStructurePlacement.footprintTouchesWater(0, 9, 0, 9, 4, (x, z) -> x == 9 && z == 9));
   }
}
