package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TellusStructurePolicyTest {
   @Test
   void mapsEveryEarthStructurePathFamilyToItsToggle() {
      TellusStructurePolicy.StructureToggles disabled = disabledToggles();
      TellusStructurePolicy policy = new TellusStructurePolicy(disabled);

      for (String path : new String[]{
         "village_plains", "stronghold", "mineshaft", "igloo", "ocean_monument", "monument",
         "mansion", "woodland_mansion", "desert_pyramid", "desert_temple", "jungle_pyramid",
         "jungle_temple", "pillager_outpost", "ruined_portal", "ruined_portal_desert",
         "shipwreck", "ocean_ruin_cold", "buried_treasure", "swamp_hut", "witch_hut",
         "ancient_city", "trial_chambers", "abandoned_camp_forest", "trail_ruins"
      }) {
         assertFalse(policy.enabled(path), path);
      }
      assertTrue(policy.enabled("custom_structure"));
   }

   @Test
   void shellSuppressionWinsBeforeTheNormalStructureToggle() {
      TellusStructurePolicy.StructureToggles toggles = new TellusStructurePolicy.StructureToggles(
         true, true, true, true, true, true, true, true, true, true,
         true, true, true, true, true, true, true, true, true
      );
      TellusStructurePolicy policy = new TellusStructurePolicy(toggles);

      assertFalse(policy.enabled("stronghold"));
      assertFalse(policy.enabled("mineshaft_mesa"));
      assertFalse(policy.enabled("ancient_city"));
      assertFalse(policy.enabled("trial_chambers"));
      assertFalse(policy.enabled("buried_treasure"));
      assertTrue(policy.enabled("village_plains"));
      assertTrue(policy.enabled("custom_structure"));
   }

   private static TellusStructurePolicy.StructureToggles disabledToggles() {
      return new TellusStructurePolicy.StructureToggles(
         false, false, false, false, false, false, false, false, false, false,
         false, false, false, false, false, false, false, false, false
      );
   }
}
