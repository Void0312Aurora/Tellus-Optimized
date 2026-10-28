package com.yucareux.tellus.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TellusRenderCompatibilityTest {
   @Test
   void enablesOnlyTheExactNativeRendererVersion() {
      assertTrue(TellusRenderCompatibility.supportsSodiumVersion("0.5.13+mc1.20.1"));
      for (String version : new String[] {null, "", "0.5.13", "0.5.12+mc1.20.1",
         "0.5.13+mc1.20.6", "0.6.0+mc1.20.1", "0.5.13+mc1.20.1-dev"}) {
         assertFalse(TellusRenderCompatibility.supportsSodiumVersion(version), String.valueOf(version));
      }
   }
}
