package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

class SphereChunkSamplerOceanAuthorityTest {
   private static final SphereWorldModel.GeoPoint POINT =
      new SphereWorldModel.GeoPoint(12.0, 34.0, 0.0);

   @Test
   void overtureHitWinsOverLegacyLandMaskAndUnknownFallsBack() {
      GeodeticOceanAuthority fakeOverture = (point, scale, resolution) ->
         point.equals(POINT)
            ? GeodeticOceanAuthority.Classification.OCEAN
            : GeodeticOceanAuthority.Classification.UNKNOWN;

      assertTrue(SphereChunkSampler.isOceanAuthorityResult(
         fakeOverture.classify(POINT, 1.0, 1.0),
         TellusLandMaskSource.LandMaskSample.known(true), 30));
      assertTrue(SphereChunkSampler.isOceanAuthorityResult(
         GeodeticOceanAuthority.Classification.UNKNOWN,
         TellusLandMaskSource.LandMaskSample.known(false), 80));
      assertFalse(SphereChunkSampler.isOceanAuthorityResult(
         GeodeticOceanAuthority.Classification.UNKNOWN,
         TellusLandMaskSource.LandMaskSample.known(true), 80));
      assertFalse(SphereChunkSampler.isOceanAuthorityResult(
         GeodeticOceanAuthority.Classification.LAND,
         TellusLandMaskSource.LandMaskSample.known(false), 80));
   }
}
