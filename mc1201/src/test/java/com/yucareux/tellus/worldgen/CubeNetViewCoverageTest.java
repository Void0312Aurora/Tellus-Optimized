package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubeNetViewCoverageTest {
   @Test
   void coversEveryChunkPhaseAtTheHandoffThreshold() {
      for (int requested : new int[]{2, 4, 8, 32}) {
         var coverage = CubeNetViewCoverage.forDistances(requested, 32, 4, 24);
         assertEquals(requested, coverage.viewRadius());
         assertEquals(requested + 1, coverage.dataRadius());
         for (int phase = 0; phase < 16; phase++) {
            int position = 16 + phase;
            var phased = CubeNetViewCoverage.forDistances(requested, 32, 4, position);
            int farthestRequiredBlock = (position / 16 + Math.max(requested, 4) + 1) * 16 + 15;
            assertTrue(phased.requiredApronChunks() * 16 > farthestRequiredBlock,
               "apron misses the far edge of the view/simulation halo");
         }
      }
   }

   @Test
   void usesTheServerCapAndKeepsSimulationCoverage() {
      var coverage = CubeNetViewCoverage.forDistances(12, 4, 6, 24);
      assertEquals(4, coverage.viewRadius());
      assertEquals(5, coverage.dataRadius());
      assertEquals(9, coverage.requiredApronChunks());
   }

   @Test
   void rejectsInvalidEngineDistances() {
      assertThrows(IllegalArgumentException.class,
         () -> CubeNetViewCoverage.forDistances(1, 4, 4, 24));
      assertThrows(IllegalArgumentException.class,
         () -> CubeNetViewCoverage.forDistances(4, 4, 4, -1));
   }
}
