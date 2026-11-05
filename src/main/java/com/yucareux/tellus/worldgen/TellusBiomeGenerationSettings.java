package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.WorldgenApi.TreeFeatureHandle;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Shared vanilla biome-generation filter used by Tellus terrain generators. */
public final class TellusBiomeGenerationSettings {
   private static final Map<BiomeSettingsKey, BiomeGenerationSettings> FILTERED_SETTINGS = new ConcurrentHashMap<>();

   private TellusBiomeGenerationSettings() {
   }

   /**
    * Generation toggles used when copying a biome's vanilla settings. Tree
    * retention is intentionally derived from the biome, matching Earth's
    * mangrove-only exception.
    */
   public record Settings(
      boolean caveGeneration,
      boolean oreDistribution,
      boolean geologicalStonePatches,
      boolean lavaPools,
      boolean geodes,
      boolean deepDark,
      boolean suppressesUndergroundGenerationForTerrainShell
   ) {
   }

   public static Settings from(EarthGeneratorSettings settings) {
      Objects.requireNonNull(settings, "settings");
      return new Settings(
         settings.caveGeneration(),
         settings.oreDistribution(),
         settings.geologicalStonePatches(),
         settings.lavaPools(),
         settings.geodes(),
         settings.deepDark(),
         settings.suppressesUndergroundGenerationForTerrainShell()
      );
   }

   public static BiomeGenerationSettings forBiome(Holder<Biome> biome, Settings settings) {
      Objects.requireNonNull(biome, "biome");
      Objects.requireNonNull(settings, "settings");
      boolean keepTrees = biome.is(Biomes.MANGROVE_SWAMP);
      int flags = geologyFlags(settings, keepTrees);
      BiomeSettingsKey key = new BiomeSettingsKey(biome, flags);
      return FILTERED_SETTINGS.computeIfAbsent(key, ignored -> filterGenerationSettings(biome, settings, keepTrees));
   }

   private static BiomeGenerationSettings filterGenerationSettings(
      Holder<Biome> biome, Settings settings, boolean keepTrees
   ) {
      BiomeGenerationSettings original = ((Biome)biome.value()).getGenerationSettings();
      BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
      boolean keepBiomeDeepDarkFeatures = biome.is(Biomes.DEEP_DARK);

      MinecraftVersionCompat.copyCarvers(original, builder, carver -> shouldKeepCarver(carver, settings));

      List<HolderSet<PlacedFeature>> features = original.features();
      for (int step = 0; step < features.size(); step++) {
         for (Holder<PlacedFeature> feature : features.get(step)) {
            Holder<PlacedFeature> safeFeature = Objects.requireNonNull(feature, "feature");
            if ((keepTrees || !isTreeFeature((PlacedFeature)safeFeature.value()))
               && shouldKeepFeature(safeFeature, settings, keepBiomeDeepDarkFeatures)) {
               builder.addFeature(step, safeFeature);
            }
         }
      }

      return builder.build();
   }

   private static int geologyFlags(Settings settings, boolean keepTrees) {
      int flags = 0;
      if (settings.caveGeneration()) {
         flags |= 1;
      }
      if (settings.oreDistribution()) {
         flags |= 2;
      }
      if (settings.lavaPools()) {
         flags |= 4;
      }
      if (settings.deepDark()) {
         flags |= 8;
      }
      if (settings.geodes()) {
         flags |= 16;
      }
      if (keepTrees) {
         flags |= 32;
      }
      if (settings.suppressesUndergroundGenerationForTerrainShell()) {
         flags |= 64;
      }
      if (settings.geologicalStonePatches()) {
         flags |= 128;
      }
      return flags;
   }

   private static boolean shouldKeepCarver(Holder<?> carver, Settings settings) {
      return carver.unwrapKey()
         .map(MinecraftVersionCompat::keyPath)
         .map(path -> shouldKeepCarverId(path, settings))
         .orElse(true);
   }

   private static boolean shouldKeepCarverId(String path, Settings settings) {
      if (settings.suppressesUndergroundGenerationForTerrainShell()) {
         return false;
      }

      return settings.caveGeneration()
         || !path.equals("cave") && !path.equals("cave_extra_underground") && !path.equals("canyon");
   }

   private static boolean shouldKeepFeature(
      Holder<PlacedFeature> feature, Settings settings, boolean keepBiomeDeepDarkFeatures
   ) {
      UndergroundFeatureClassifier.Kind featureKind = UndergroundFeatureClassifier.classify(feature.value());
      return feature.unwrapKey()
         .map(MinecraftVersionCompat::keyPath)
         .map(path -> shouldKeepFeatureId(path, featureKind, settings, keepBiomeDeepDarkFeatures))
         .orElseGet(() -> shouldKeepFeatureId("", featureKind, settings, keepBiomeDeepDarkFeatures));
   }

   private static boolean shouldKeepFeatureId(
      String path,
      UndergroundFeatureClassifier.Kind featureKind,
      Settings settings,
      boolean keepBiomeDeepDarkFeatures
   ) {
      if (settings.suppressesUndergroundGenerationForTerrainShell() && isThinShellUndergroundFeatureId(path)) {
         return false;
      } else if (path.equals("freeze_top_layer") || path.equals("snow_and_freeze")) {
         return false;
      } else if (featureKind == UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE
         && !settings.geologicalStonePatches()) {
         return false;
      } else if (featureKind != UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE
         && !settings.oreDistribution()
         && (featureKind == UndergroundFeatureClassifier.Kind.MINEABLE_ORE || path.startsWith("ore_"))) {
         return false;
      } else if (!settings.geodes() && path.contains("geode")) {
         return false;
      } else if (settings.deepDark() || keepBiomeDeepDarkFeatures || !path.contains("sculk") && !path.contains("deep_dark")) {
         return settings.caveGeneration() || !path.contains("dripstone") && !path.startsWith("spring_water")
            ? settings.lavaPools() || !path.startsWith("lake_lava") && !path.startsWith("spring_lava")
            : false;
      } else {
         return false;
      }
   }

   private static boolean isThinShellUndergroundFeatureId(String path) {
      return path.startsWith("ore_")
         || path.contains("geode")
         || path.contains("sculk")
         || path.contains("deep_dark")
         || path.contains("dripstone")
         || path.startsWith("spring_")
         || path.startsWith("lake_lava")
         || path.startsWith("lake_water")
         || path.contains("lava")
         || path.contains("monster_room")
         || path.contains("fossil")
         || path.contains("underground")
         || path.contains("cave")
         || path.contains("amethyst");
   }

   private static boolean isTreeFeature(PlacedFeature feature) {
      return WorldgenApi.features(feature).anyMatch(TreeFeatureHandle::isTree);
   }

   private record BiomeSettingsKey(Holder<Biome> biome, int flags) {
   }
}
