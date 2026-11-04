package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetRoadVerticalPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CubeNetRoadVerticalPolicyTest {
   @Test
   void bridgeKeepsMinimumClearanceAndCapsRise() {
      assertEquals(103, CubeNetRoadVerticalPolicy.bridgeDeckY(100, 0));
      assertEquals(106, CubeNetRoadVerticalPolicy.bridgeDeckY(100, 2));
      assertEquals(110, CubeNetRoadVerticalPolicy.bridgeDeckY(100, 99));
   }

   @Test
   void tunnelLeavesTheConfiguredInteriorHeightBelowSurface() {
      assertEquals(93, CubeNetRoadVerticalPolicy.tunnelDeckY(100));
   }
}
