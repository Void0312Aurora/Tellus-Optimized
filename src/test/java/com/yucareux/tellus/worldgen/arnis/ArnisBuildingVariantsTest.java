package com.yucareux.tellus.worldgen.arnis;

import com.yucareux.tellus.world.data.osm.OsmBuildingMetadata;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArnisBuildingVariantsTest {
   @Test
   void styleHintWinsOverRandomHighRiseVariant() {
      BuildingProfile profile = profile(20);
      OsmBuildingMetadata glass = metadata("glass");
      OsmBuildingMetadata masonry = metadata("stone");

      ArnisBuildingVariants.HighRiseVariant glassVariant = ArnisBuildingVariants.resolve(profile, glass, 900.0, 16, 16, 42L);
      assertTrue(
         glassVariant == ArnisBuildingVariants.HighRiseVariant.GLASS
            || glassVariant == ArnisBuildingVariants.HighRiseVariant.GLASS_CORNER
      );
      assertEquals(
         ArnisBuildingVariants.HighRiseVariant.MASONRY,
         ArnisBuildingVariants.resolve(profile, masonry, 900.0, 16, 16, 42L)
      );
   }

   @Test
   void trueSkyscraperVariantsAreDeterministicAndNotSingleStyle() {
      BuildingProfile profile = profile(50);
      Set<ArnisBuildingVariants.HighRiseVariant> variants = new HashSet<>();
      for (long seed = 0; seed < 100; seed++) {
         ArnisBuildingVariants.HighRiseVariant first = ArnisBuildingVariants.resolve(profile, null, 900.0, 16, 16, seed);
         assertEquals(first, ArnisBuildingVariants.resolve(profile, null, 900.0, 16, 16, seed));
         variants.add(first);
      }
      assertTrue(variants.size() >= 3, "seeded high-rise selection should exercise multiple Arnis variants");
   }

   private static BuildingProfile profile(int floors) {
      return new BuildingProfile(
         BuildingProfile.Archetype.TOWER,
         BuildingProfile.BuildingCategory.TALL_BUILDING,
         BuildingProfile.RoofProfile.FLAT,
         BuildingProfile.ClimateFamily.TEMPERATE,
         floors,
         4,
         false,
         2,
         0,
         0,
         0,
         4
      );
   }

   private static OsmBuildingMetadata metadata(String material) {
      return new OsmBuildingMetadata(
         null, "office", "office", null, 1, null, material, null, material, null,
         null, null, null, null, null, null, null, 0, 0
      );
   }
}
