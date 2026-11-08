package com.yucareux.tellus.world.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.runtime.EarthGeneratorSettingsViewAdapter;
import com.yucareux.tellus.worldgen.runtime.TellusGeneratorSettingsView;
import org.junit.jupiter.api.Test;

/** Focused contract for the realtime SettingsView projection and override precedence. */
class TellusRealtimeSettingsViewContractTest {
   @Test
   void earthRealtimeViewMatchesConfiguredFlags() {
      EarthGeneratorSettings configured = EarthGeneratorSettings.DEFAULT;
      TellusGeneratorSettingsView view = new EarthGeneratorSettingsViewAdapter(configured);
      TellusGeneratorSettingsView.RealtimeOptions realtime = view.realtime().orElseThrow();

      assertEquals(configured.realtimeTime(), realtime.time());
      assertEquals(configured.realtimeWeather(), realtime.weather());
      assertEquals(configured.historicalSnow(), realtime.historicalSnow());
   }

   @Test
   void nullSettingsUseLegacyCubeNetPropertyFallback() {
      TellusRealtimeManager manager = new TellusRealtimeManager();
      try {
         assertEquals(Boolean.getBoolean("tellus.cubeNet.realtimeTime"),
            manager.isRealtimeTimeEnabled(null));
         assertEquals(Boolean.getBoolean("tellus.cubeNet.realtimeWeather"),
            manager.isRealtimeWeatherEnabled(null));
      } finally {
         manager.shutdown();
      }
   }
   @Test
   void publicEarthResolversRetainCommandOverridePrecedence() {
      EarthGeneratorSettings configured = EarthGeneratorSettings.DEFAULT;
      TellusRealtimeManager manager = new TellusRealtimeManager();
      try {
         manager.setRealtimeTimeOverride(!configured.realtimeTime());
         manager.setRealtimeWeatherOverride(!configured.realtimeWeather());
         assertEquals(!configured.realtimeTime(), manager.isRealtimeTimeEnabled(configured));
         assertEquals(!configured.realtimeWeather(), manager.isRealtimeWeatherEnabled(configured));
         manager.clearOverrides();
         assertEquals(configured.realtimeTime(), manager.isRealtimeTimeEnabled(configured));
         assertEquals(configured.realtimeWeather(), manager.isRealtimeWeatherEnabled(configured));
      } finally {
         manager.shutdown();
      }
   }
}
