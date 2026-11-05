package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;

/**
 * Shared spherical surface decision.  It deliberately returns version-neutral
 * material names; the platform materializer maps those names to block states.
 * This keeps the native column and distant-horizon paths on the same rule.
 */
public final class CubeNetSurfacePolicy {
   private CubeNetSurfacePolicy() { }

   public static Decision resolve(
      SphereWorldModel.GeoPoint point,
      SphereChunkSampler.SurfaceData surface,
      double worldScale,
      long worldSeed
   ) {
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)
         || !surface.elevationAvailable()) {
         return Decision.DEFAULT;
      }
      if (surface.waterAvailable() && surface.waterSurfaceMeters() > surface.elevationMeters()) {
         return baseline(surface.coverClass(), 0, Double.NaN, MountainSurfaceRules.MountainForm.NONE);
      }
      int[] coordinates = canonicalNoiseCoordinates(point);
      int heightAboveSea = clampInt((int)Math.round(surface.elevationMeters() / worldScale), -4096, 4096);
      TerrainSlopePolicy.Neighbourhood neighbourhood = surface.neighbourhood();
      double slopeDegrees = neighbourhood.gradient().slopeDegrees();
      int slopeDiff = finite(slopeDegrees) ? clampInt((int)Math.round(slopeDegrees / 9.0), 0, 8) : 0;
      int convexity = 0;
      boolean snowLike = surface.coverClass() == MountainSurfaceRules.ESA_SNOW_ICE;
      MountainSurfaceRules.ApproximateSurface mountain = MountainSurfaceRules.classifyApproximateSurface(
         surface.coverClass(), surface.coverClass(), heightAboveSea, slopeDiff, convexity,
         snowLike, coordinates[0], coordinates[1]
      );
      if (mountain.palette() == MountainSurfaceRules.ApproximatePalette.SNOW) {
         SurfaceMaterialDepthPolicy.SnowColumn snow = SurfaceMaterialDepthPolicy.snowColumn(
            coordinates[0], coordinates[1], worldSeed, slopeDiff <= 2
         );
         return new Decision(
            snow.powderSnow() ? Material.POWDER_SNOW : Material.SNOW,
            Material.SNOW,
            snow.depth(), slopeDiff, slopeDegrees, mountain.form()
         );
      }
      if (mountain.palette() == MountainSurfaceRules.ApproximatePalette.SNOW_STREAK) {
         return new Decision(Material.SNOW, Material.SNOW, 1, slopeDiff, slopeDegrees, mountain.form());
      }
      if (mountain.palette() == MountainSurfaceRules.ApproximatePalette.STONE) {
         Material material = materialForMountainForm(mountain.form(), coordinates[0], coordinates[1]);
         int depth = slopeDiff >= 4 && finite(slopeDegrees) && slopeDegrees > 40.0
            ? DeepslateSlopePolicy.surfaceDepthForRelief(12, slopeDiff) : 3;
         return new Decision(material, material, depth, slopeDiff, slopeDegrees, mountain.form());
      }
      // Overture sand polygons are queried in the same geodetic source used by
      // the planar generator.  The lookup is non-blocking: a cache miss queues
      // the tile and leaves this column on the ordinary Tellus surface rules.
      if (!surface.waterAvailable() && finite(slopeDegrees) && slopeDegrees < 35.0
         && overtureSandAt(point, worldScale)) {
         return new Decision(Material.SAND, Material.SANDSTONE, 4, slopeDiff, slopeDegrees, mountain.form());
      }
      if (surface.coverClass() != MountainSurfaceRules.ESA_TREE_COVER
         && finite(slopeDegrees) && DeepslateSlopePolicy.shouldCover(
            coordinates[0], coordinates[1], slopeDegrees)) {
         int depth = DeepslateSlopePolicy.surfaceDepthForRelief(12, slopeDiff);
         return new Decision(Material.STONE, Material.STONE, depth, slopeDiff, slopeDegrees, mountain.form());
      }
      return baseline(surface.coverClass(), slopeDiff, slopeDegrees, mountain.form());
   }

   /**
    * Earth-equivalent surface selection for the native Cube-Net path. The
    * caller supplies the Earth-grid cover/climate and shoreline context after
    * resolving the spherical column; this keeps the material rule identical
    * inside a face without using planar coordinates as the ownership system.
    */
   public static Decision resolveEarthEquivalent(
      SphereWorldModel.GeoPoint point,
      SphereChunkSampler.SurfaceData surface,
      double worldScale,
      long worldSeed,
      int visualCover,
      byte climateGroup,
      int zoneHeight,
      int slopeDiff,
      int convexity,
      Shoreline shoreline,
      BiomeSurface biomeSurface,
      int earthX,
      int earthZ
   ) {
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale) || !surface.elevationAvailable()) {
         return Decision.DEFAULT;
      }
      if (surface.waterAvailable() && surface.waterSurfaceMeters() > surface.elevationMeters()) {
         return baseline(surface.coverClass(), 0, Double.NaN, MountainSurfaceRules.MountainForm.NONE);
      }
      int[] coordinates = new int[]{earthX, earthZ};
      int heightAboveSea = clampInt((int)Math.round(surface.elevationMeters() / worldScale), -4096, 4096);
      int boundedZoneHeight = clampInt(zoneHeight, -4096, 4096);
      double slopeDegrees = surface.neighbourhood().gradient().slopeDegrees();
      boolean snowLike = visualCover == MountainSurfaceRules.ESA_SNOW_ICE;
      MountainSurfaceRules.ApproximateSurface mountain = MountainSurfaceRules.classifyApproximateSurface(
         surface.coverClass(), visualCover, boundedZoneHeight, slopeDiff, convexity,
         snowLike, coordinates[0], coordinates[1]
      );
      if (mountain.palette() == MountainSurfaceRules.ApproximatePalette.SNOW) {
         SurfaceMaterialDepthPolicy.SnowColumn snow = SurfaceMaterialDepthPolicy.snowColumn(
            coordinates[0], coordinates[1], worldSeed, slopeDiff <= 2
         );
         return new Decision(snow.powderSnow() ? Material.POWDER_SNOW : Material.SNOW,
            Material.SNOW, snow.depth(), slopeDiff, slopeDegrees, mountain.form());
      }
      if (mountain.palette() == MountainSurfaceRules.ApproximatePalette.SNOW_STREAK) {
         return new Decision(Material.SNOW, Material.SNOW, 1, slopeDiff, slopeDegrees, mountain.form());
      }
      if (mountain.palette() == MountainSurfaceRules.ApproximatePalette.STONE) {
         Material material = materialForMountainForm(mountain.form(), coordinates[0], coordinates[1]);
         int depth = slopeDiff >= 4 && finite(slopeDegrees) && slopeDegrees > 40.0
            ? DeepslateSlopePolicy.surfaceDepthForRelief(12, slopeDiff) : 3;
         return new Decision(material, material, depth, slopeDiff, slopeDegrees, mountain.form());
      }

      if (shoreline != null && shoreline.kind() != MountainSurfaceRules.ShorelineKind.NONE) {
         MountainSurfaceRules.ShorelineMaterial material = MountainSurfaceRules.classifyShorelineMaterial(
            visualCover, climateGroup, boundedZoneHeight, slopeDiff, convexity,
            shoreline.kind(), shoreline.distance(), biomeSurface == BiomeSurface.BADLANDS);
         switch (material) {
            case GRAVEL -> {
               return new Decision(Material.GRAVEL, Material.STONE, 4, slopeDiff, slopeDegrees, mountain.form());
            }
            case SAND -> {
               return new Decision(Material.SAND, Material.SAND, 4, slopeDiff, slopeDegrees, mountain.form());
            }
            case RED_SAND -> {
               return new Decision(Material.SAND, Material.SANDSTONE, 4, slopeDiff, slopeDegrees, mountain.form());
            }
            case PRESERVE_WETLAND, NONE -> {
               // Continue with the ordinary biome/cover palette.
            }
         }
      }
      Decision biome = biomeBaseline(surface.coverClass(), visualCover, biomeSurface, slopeDiff, slopeDegrees, mountain.form());
      if (biome != null) return biome;
      if (!surface.waterAvailable() && finite(slopeDegrees) && slopeDegrees < 35.0
         && overtureSandAt(point, worldScale)) {
         return new Decision(Material.SAND, Material.SANDSTONE, 4, slopeDiff, slopeDegrees, mountain.form());
      }
      if (surface.coverClass() != MountainSurfaceRules.ESA_TREE_COVER && finite(slopeDegrees)
         && DeepslateSlopePolicy.shouldCover(coordinates[0], coordinates[1], slopeDegrees)) {
         int depth = DeepslateSlopePolicy.surfaceDepthForRelief(12, slopeDiff);
         return new Decision(Material.STONE, Material.STONE, depth, slopeDiff, slopeDegrees, mountain.form());
      }
      return coverDrivenPalette(visualCover, climateGroup, boundedZoneHeight,
         earthX, earthZ, slopeDiff, slopeDegrees, mountain.form());
   }

   private static Decision biomeBaseline(int rawCover, int visualCover, BiomeSurface biome,
      int slopeDiff, double slopeDegrees, MountainSurfaceRules.MountainForm form) {
      return switch (biome) {
         case BEACH, SNOWY_BEACH, DESERT -> new Decision(Material.SAND, Material.SANDSTONE, 5, slopeDiff, slopeDegrees, form);
         case STONY_SHORE, STONY_PEAKS -> new Decision(Material.STONE, Material.STONE, 12, slopeDiff, slopeDegrees, form);
         case GRAVELLY -> new Decision(Material.GRAVEL, Material.STONE, 4, slopeDiff, slopeDegrees, form);
         case MANGROVE -> new Decision(Material.MUD, Material.DIRT, 4, slopeDiff, slopeDegrees, form);
         case SWAMP -> new Decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form);
         default -> null;
      };
   }

   /** Direct counterpart of Earth's coverDrivenSurfacePalette. */
   private static Decision coverDrivenPalette(int coverClass, byte climateGroup, int zoneHeight,
      int earthX, int earthZ, int slopeDiff, double slopeDegrees,
      MountainSurfaceRules.MountainForm form) {
      int roll = SurfacePatchNoise.percent(earthX, earthZ, 23L);
      return switch (coverClass) {
         case MountainSurfaceRules.ESA_TREE_COVER -> climateGroup != 4 && climateGroup != 5
            ? climateGroup == 2
               ? (roll < 70 ? decision(Material.COARSE_DIRT, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
                  : decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form))
               : climateGroup == 1
                  ? (roll < 35 ? decision(Material.ROOTED_DIRT, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
                     : decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form))
                  : (roll < 20 ? decision(Material.ROOTED_DIRT, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
                     : decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form))
            : (roll < 65 ? decision(Material.PODZOL, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
               : decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form));
         case MountainSurfaceRules.ESA_SHRUBLAND -> zoneHeight >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 70
            ? decision(Material.GRAVEL, Material.STONE, 4, slopeDiff, slopeDegrees, form)
            : climateGroup == 2
               ? decision(Material.COARSE_DIRT, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
               : climateGroup != 4 && climateGroup != 5
                  ? (roll < 40 ? decision(Material.COARSE_DIRT, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
                     : decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form))
                  : decision(Material.PODZOL, Material.DIRT, 4, slopeDiff, slopeDegrees, form);
         case MountainSurfaceRules.ESA_GRASSLAND -> zoneHeight >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 35
            ? decision(Material.GRAVEL, Material.STONE, 4, slopeDiff, slopeDegrees, form)
            : climateGroup == 2
               ? (roll < 65 ? decision(Material.COARSE_DIRT, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
                  : decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form))
               : climateGroup != 4 && climateGroup != 5
                  ? decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
                  : (roll < 40 ? decision(Material.PODZOL, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
                     : decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form));
         case MountainSurfaceRules.ESA_CROPLAND -> climateGroup == 2
            ? decision(Material.COARSE_DIRT, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
            : decision(roll < 65 ? Material.ROOTED_DIRT : Material.GRASS, Material.DIRT, 4,
               slopeDiff, slopeDegrees, form);
         case MountainSurfaceRules.ESA_BUILT -> roll < 45
            ? decision(Material.GRAVEL, Material.STONE, 4, slopeDiff, slopeDegrees, form)
            : decision(Material.STONE, Material.STONE, 12, slopeDiff, slopeDegrees, form);
         case MountainSurfaceRules.ESA_BARE -> zoneHeight >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA
            ? decision(Material.STONE, Material.STONE, 12, slopeDiff, slopeDegrees, form)
            : climateGroup == 2
               ? (roll < 55 ? decision(Material.SAND, Material.SANDSTONE, 5, slopeDiff, slopeDegrees, form)
                  : decision(Material.RED_SAND, Material.TERRACOTTA, 4, slopeDiff, slopeDegrees, form))
               : climateGroup != 4 && climateGroup != 5
                  ? decision(Material.GRAVEL, Material.STONE, 4, slopeDiff, slopeDegrees, form)
                  : (roll < 70 ? decision(Material.GRAVEL, Material.STONE, 4, slopeDiff, slopeDegrees, form)
                     : decision(Material.STONE, Material.STONE, 12, slopeDiff, slopeDegrees, form));
         case MountainSurfaceRules.ESA_SNOW_ICE -> decision(Material.SNOW, Material.SNOW, 4, slopeDiff, slopeDegrees, form);
         case MountainSurfaceRules.ESA_WETLAND -> roll < 40
            ? decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
            : decision(Material.MUD, Material.PACKED_MUD, 4, slopeDiff, slopeDegrees, form);
         case MountainSurfaceRules.ESA_MANGROVES -> decision(Material.MUD, Material.DIRT, 4, slopeDiff, slopeDegrees, form);
         case MountainSurfaceRules.ESA_MOSS_LICHEN -> zoneHeight >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA && roll < 60
            ? decision(Material.STONE, Material.STONE, 12, slopeDiff, slopeDegrees, form)
            : (roll < 35 ? decision(Material.MOSS, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
               : decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form));
         default -> climateGroup != 4 && climateGroup != 5
            ? decision(Material.GRASS, Material.DIRT, 4, slopeDiff, slopeDegrees, form)
            : decision(Material.PODZOL, Material.DIRT, 4, slopeDiff, slopeDegrees, form);
      };
   }

   private static Decision decision(Material top, Material filler, int depth,
      int slopeDiff, double slopeDegrees, MountainSurfaceRules.MountainForm form) {
      return new Decision(top, filler, depth, slopeDiff, slopeDegrees, form);
   }

   private static boolean overtureSandAt(SphereWorldModel.GeoPoint point, double worldScale) {
      double blockX = EarthProjection.longitudeToBlockX(point.longitude(), worldScale);
      double blockZ = EarthProjection.latToBlockZ(point.latitude(), worldScale);
      if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)
         || blockX < Integer.MIN_VALUE || blockX > Integer.MAX_VALUE
         || blockZ < Integer.MIN_VALUE || blockZ > Integer.MAX_VALUE) {
         return false;
      }
      return TellusWorldgenSources.osmSand().containsSand(
         (int)Math.floor(blockX), (int)Math.floor(blockZ), worldScale, OsmQueryMode.NON_BLOCKING);
   }

   private static Decision baseline(int coverClass, int slopeDiff, double slopeDegrees,
      MountainSurfaceRules.MountainForm form) {
      return switch (coverClass) {
         case MountainSurfaceRules.ESA_TREE_COVER,
            MountainSurfaceRules.ESA_SHRUBLAND,
            MountainSurfaceRules.ESA_GRASSLAND,
            MountainSurfaceRules.ESA_CROPLAND,
            MountainSurfaceRules.ESA_MOSS_LICHEN ->
            new Decision(Material.GRASS, Material.DIRT, 3, slopeDiff, slopeDegrees, form);
         case MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_WATER ->
            new Decision(Material.SAND, Material.SANDSTONE, 3, slopeDiff, slopeDegrees, form);
         case MountainSurfaceRules.ESA_SNOW_ICE ->
            new Decision(Material.SNOW, Material.SNOW, 3, slopeDiff, slopeDegrees, form);
         case MountainSurfaceRules.ESA_WETLAND, MountainSurfaceRules.ESA_MANGROVES ->
            new Decision(Material.MUD, Material.MUD, 3, slopeDiff, slopeDegrees, form);
         default -> new Decision(Material.STONE, Material.STONE, 3, slopeDiff, slopeDegrees, form);
      };
   }

   private static Material materialForMountainForm(MountainSurfaceRules.MountainForm form, int x, int z) {
      return switch (MountainSurfaceRules.formSurfaceMaterial(form, x, z)) {
         case MountainSurfaceRules.MATERIAL_GRAVEL -> Material.GRAVEL;
         case MountainSurfaceRules.MATERIAL_ANDESITE -> Material.ANDESITE;
         case MountainSurfaceRules.MATERIAL_DIORITE -> Material.DIORITE;
         case MountainSurfaceRules.MATERIAL_COBBLESTONE -> Material.COBBLESTONE;
         case MountainSurfaceRules.MATERIAL_PACKED_ICE -> Material.PACKED_ICE;
         case MountainSurfaceRules.MATERIAL_BLUE_ICE -> Material.BLUE_ICE;
         case MountainSurfaceRules.MATERIAL_SNOW -> Material.SNOW;
         default -> Material.STONE;
      };
   }

   /** ECEF-derived coordinates stay continuous across the ±180° meridian and cube-face seams. */
   private static int[] canonicalNoiseCoordinates(SphereWorldModel.GeoPoint point) {
      double latitude = Math.toRadians(point.latitude());
      double longitude = Math.toRadians(point.longitude());
      double cos = Math.cos(latitude);
      double x = cos * Math.cos(longitude);
      double y = Math.sin(latitude);
      double z = cos * Math.sin(longitude);
      return new int[]{
         safeCoordinate(x * 1_000_000.0 + y * 37_000.0),
         safeCoordinate(z * 1_000_000.0 + y * 91_000.0)
      };
   }

   private static int safeCoordinate(double value) {
      if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
      if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
      return (int)Math.round(value);
   }

   private static boolean finite(double value) { return Double.isFinite(value); }

   private static int clampInt(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }

   public enum Material {
      GRASS, DIRT, COARSE_DIRT, PODZOL, ROOTED_DIRT, MOSS, MYCELIUM,
      SAND, SANDSTONE, RED_SAND, SNOW, POWDER_SNOW, STONE, GRAVEL,
      ANDESITE, DIORITE, COBBLESTONE, PACKED_ICE, BLUE_ICE, MUD, PACKED_MUD, TERRACOTTA
   }

   public record Shoreline(MountainSurfaceRules.ShorelineKind kind, int distance) {
      public static final Shoreline NONE = new Shoreline(MountainSurfaceRules.ShorelineKind.NONE, -1);
   }

   public enum BiomeSurface {
      DEFAULT, OCEAN, RIVER, MUSHROOM, BEACH, SNOWY_BEACH, STONY_SHORE,
      BADLANDS, DESERT, MANGROVE, SWAMP, STONY_PEAKS, GRAVELLY
   }

   public record Decision(
      Material top,
      Material filler,
      int depth,
      int slopeDiff,
      double slopeDegrees,
      MountainSurfaceRules.MountainForm form
   ) {
      public static final Decision DEFAULT = new Decision(
         Material.STONE, Material.STONE, 3, 0, Double.NaN, MountainSurfaceRules.MountainForm.NONE
      );

      public Decision {
         if (depth < 1) throw new IllegalArgumentException("depth");
         if (top == null || filler == null || form == null) throw new NullPointerException();
      }
   }
}
