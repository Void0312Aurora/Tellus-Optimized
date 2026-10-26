package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
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
   void minimumAndMaximumWidthsCoverTheirDeclaredDistances() {
      assertEquals(5, CubeNetViewCoverage.DEFAULT_APRON_CHUNKS);
      var minimum = CubeNetViewCoverage.forDistances(2, 2, 2, 24);
      assertEquals(5, minimum.requiredApronChunks());
      var maximum = CubeNetViewCoverage.forDistances(32, 32, 32, 24);
      assertEquals(35, maximum.requiredApronChunks());
      var messages = new ArrayList<String>();
      assertEquals(33, CubeNetRuntime.transferRadius(maximum, 35, messages::add));
      assertTrue(messages.isEmpty());
      assertEquals(-1, CubeNetRuntime.transferRadius(maximum, 34, messages::add));
      assertTrue(messages.get(0).contains("at least 35 chunks"));
   }

   @Test
   void defaultApronOnlyAdmitsMinimumEffectiveViewAndSimulationDistances() {
      var messages = new ArrayList<String>();
      var minimum = CubeNetViewCoverage.forDistances(2, 2, 2, 24);
      assertEquals(3, CubeNetRuntime.transferRadius(minimum,
         CubeNetViewCoverage.DEFAULT_APRON_CHUNKS, messages::add));
      // A higher advertised client distance still fits when the server caps it to 2.
      assertEquals(3, CubeNetRuntime.transferRadius(
         CubeNetViewCoverage.forDistances(16, 2, 2, 24), 5, messages::add));
      assertTrue(messages.isEmpty());
      assertEquals(-1, CubeNetRuntime.transferRadius(
         CubeNetViewCoverage.forDistances(3, 3, 2, 24), 5, messages::add));
      assertEquals(-1, CubeNetRuntime.transferRadius(
         CubeNetViewCoverage.forDistances(2, 2, 3, 24), 5, messages::add));
      assertEquals(2, messages.size());
   }

   @Test
   void manualViewFourApronRejectsDefaultWithAnActionableDiagnostic() {
      var coverage = CubeNetViewCoverage.forDistances(4, 4, 4, 24);
      var messages = new ArrayList<String>();
      assertEquals(-1, CubeNetRuntime.transferRadius(coverage, 5, messages::add));
      assertEquals(1, messages.size());
      assertTrue(messages.get(0).contains("at least 7 chunks"));
      assertTrue(messages.get(0).contains("this world has 5"));
      assertTrue(messages.get(0).contains("--apron-chunks 7"));
      messages.clear();
      assertEquals(5, CubeNetRuntime.transferRadius(coverage, 7, messages::add));
      assertTrue(messages.isEmpty());
   }

   @Test
   void manualApronAccountsForServerViewCapAndHigherSimulationDistance() {
      var coverage = CubeNetViewCoverage.forDistances(12, 4, 6, 24);
      var messages = new ArrayList<String>();
      assertEquals(-1, CubeNetRuntime.transferRadius(coverage, 8, messages::add));
      assertTrue(messages.get(0).contains("--apron-chunks 9"));
      messages.clear();
      assertEquals(5, CubeNetRuntime.transferRadius(coverage, 9, messages::add));
      assertTrue(messages.isEmpty());
   }

   @Test
   void rejectsInvalidEngineDistances() {
      assertThrows(IllegalArgumentException.class,
         () -> CubeNetViewCoverage.forDistances(1, 4, 4, 24));
      assertThrows(IllegalArgumentException.class,
         () -> CubeNetViewCoverage.forDistances(4, 4, 4, -1));
   }
}
