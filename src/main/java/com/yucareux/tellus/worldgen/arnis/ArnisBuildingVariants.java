package com.yucareux.tellus.worldgen.arnis;

import com.yucareux.tellus.world.data.osm.OsmBuildingMetadata;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import java.util.Locale;

/**
 * Deterministic high-rise variant selection adapted from Arnis 3.x.
 *
 * <p>This product includes code derived from Arnis.
 * Copyright (c) 2022-2026 Louis Erbkamm (louis-e).
 * Licensed under the Apache License, Version 2.0.
 * Source: https://github.com/louis-e/arnis</p>
 *
 * <p>Tellus does not use Arnis' photograph manifest. This class ports the
 * portable part of its building pipeline: style hints, footprint proportions,
 * and a stable weighted variant choice shared by all chunks of one blueprint.</p>
 */
public final class ArnisBuildingVariants {
   private ArnisBuildingVariants() {
   }

   public enum HighRiseVariant {
      NONE,
      GLASS,
      GLASS_CORNER,
      GRID,
      CONTEMPORARY,
      MODERN,
      MASONRY
   }

   public static HighRiseVariant resolve(
      BuildingProfile profile,
      OsmBuildingMetadata metadata,
      double areaSquareMeters,
      int widthBlocks,
      int depthBlocks,
      long blueprintSeed
   ) {
      if (profile == null || profile.category() != BuildingProfile.BuildingCategory.TALL_BUILDING) {
         return switch (profile == null ? BuildingProfile.BuildingCategory.GENERIC : profile.category()) {
            case GLASSY_SKYSCRAPER -> HighRiseVariant.GLASS;
            case MODERN_SKYSCRAPER -> HighRiseVariant.MODERN;
            default -> HighRiseVariant.NONE;
         };
      }

      String hint = metadata == null ? "" : (text(metadata.wallMaterial()) + " " + text(metadata.roofMaterial()) + " "
         + text(metadata.wallColor()) + " " + text(metadata.combinedTypeText()));
      if (containsAny(hint, "stone", "masonry", "brick", "historic")) {
         return HighRiseVariant.MASONRY;
      }
      if (containsAny(hint, "glass", "curtain")) {
         long cleanSeed = mixSeed(blueprintSeed, 17);
         return positiveMod(cleanSeed, 5) == 0 ? HighRiseVariant.GLASS_CORNER : HighRiseVariant.GLASS;
      }

      int heightBlocks = Math.max(1, profile.floorCount() * profile.storeyHeightBlocks());
      int longestSide = Math.max(1, Math.max(widthBlocks, depthBlocks));
      boolean trueSkyscraper = heightBlocks >= 40 && heightBlocks >= 2 * longestSide;
      if (!trueSkyscraper) {
         return HighRiseVariant.MODERN;
      }

      long mixed = mixSeed(blueprintSeed, 29);
      int roll = positiveMod(mixed, 100);
      if (roll < 18) return HighRiseVariant.GLASS;
      if (roll < 30) return HighRiseVariant.GLASS_CORNER;
      if (roll < 45) return HighRiseVariant.GRID;
      if (roll < 70) return HighRiseVariant.CONTEMPORARY;
      if (roll < 85) return HighRiseVariant.MODERN;
      return HighRiseVariant.MASONRY;
   }

   private static long mixSeed(long seed, int salt) {
      long mixed = seed ^ (long)salt * 341873128712L;
      mixed ^= mixed >>> 33;
      mixed *= -49064778989728563L;
      mixed ^= mixed >>> 33;
      mixed *= -4265267296055464877L;
      mixed ^= mixed >>> 33;
      return mixed;
   }

   private static int positiveMod(long value, int modulus) {
      return Math.floorMod((int)(value ^ value >>> 32), modulus);
   }

   private static String text(String value) {
      return value == null ? "" : value.toLowerCase(Locale.ROOT);
   }

   private static boolean containsAny(String value, String... parts) {
      for (String part : parts) {
         if (value.contains(part)) return true;
      }
      return false;
   }
}
