package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SphereTerrainMaterializerTest {
   @Test
   void appliesIndependentTerrestrialAndOceanicHeightScales() {
      SphereTerrainMaterializer scaled = new SphereTerrainMaterializer(1.0, 0, true, 2.0, 0.5);
      assertEquals(200, scaled.height(100.0));
      assertEquals(-50, scaled.height(-100.0));
   }

   @Test
   void unitHeightScalesPreserveTheLegacyMapping() {
      SphereTerrainMaterializer legacy = new SphereTerrainMaterializer(1.0, 0);
      SphereTerrainMaterializer explicit = new SphereTerrainMaterializer(1.0, 0, true, 1.0, 1.0);
      for (double elevation : new double[]{100.0, 0.0, -100.0, -0.1}) {
         assertEquals(legacy.height(elevation), explicit.height(elevation), "elevation=" + elevation);
      }
   }
}
