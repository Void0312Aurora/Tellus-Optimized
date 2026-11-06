package com.yucareux.tellus.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pure-Java writer used by the Cube-Net datapack command. */
public final class CubeNetDatapackWriter {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
   private static final int PACK_FORMAT = 15;
   private static final int DEFAULT_MIN_Y = -2032;
   private static final int DEFAULT_HEIGHT = 4064;
   private static final int DEFAULT_APRON_CHUNKS = 5;

   private CubeNetDatapackWriter() {
   }

   public static Result write(Path output, String world, double scale, int seaLevel) throws IOException {
      if (!isIdentifier(world)) throw new IllegalArgumentException("world must be a namespace:path identifier");
      if (!(scale > 0.0) || !Double.isFinite(scale)) throw new IllegalArgumentException("scale");
      if (!isSupportedScale(scale)) throw new IllegalArgumentException("scale produces an unsupported face size");
      if (seaLevel < DEFAULT_MIN_Y || seaLevel >= DEFAULT_MIN_Y + DEFAULT_HEIGHT) {
         throw new IllegalArgumentException("seaLevel must be within the generated dimension height range");
      }
      requireEmptyDirectory(output);
      String[] split = world.split(":", 2);
      String namespace = split[0];
      String name = split[1];
      String dimensionType = world + "_surface";
      CubeNetBiomeSource.Settings defaults = CubeNetBiomeSource.Settings.DEFAULT;
      JsonElement encodedSettings = CubeNetBiomeSource.Settings.CODEC.encodeStart(JsonOps.INSTANCE, defaults)
         .resultOrPartial(message -> {
            throw new IllegalStateException("Unable to encode Cube-Net settings: " + message);
         })
         .orElseThrow(() -> new IllegalStateException("Unable to encode Cube-Net settings"));
      JsonObject settings = encodedSettings.isJsonObject() ? encodedSettings.getAsJsonObject() : new JsonObject();
      // Settings.CODEC omits optional defaults by design; datapack output keeps
      // the explicit field parity of tools/create_cube_net_datapack.py.
      addSettingsFields(settings, defaults);

      JsonObject dimensionTypeJson = new JsonObject();
      dimensionTypeJson.addProperty("ultrawarm", false);
      dimensionTypeJson.addProperty("natural", false);
      dimensionTypeJson.addProperty("coordinate_scale", 1.0);
      dimensionTypeJson.addProperty("has_skylight", true);
      dimensionTypeJson.addProperty("has_ceiling", false);
      dimensionTypeJson.addProperty("ambient_light", 0.0);
      dimensionTypeJson.addProperty("piglin_safe", false);
      dimensionTypeJson.addProperty("bed_works", true);
      dimensionTypeJson.addProperty("respawn_anchor_works", false);
      dimensionTypeJson.addProperty("has_raids", false);
      dimensionTypeJson.addProperty("logical_height", DEFAULT_HEIGHT);
      dimensionTypeJson.addProperty("min_y", DEFAULT_MIN_Y);
      dimensionTypeJson.addProperty("height", DEFAULT_HEIGHT);
      dimensionTypeJson.addProperty("infiniburn", "#minecraft:infiniburn_overworld");
      dimensionTypeJson.addProperty("effects", "minecraft:overworld");
      dimensionTypeJson.addProperty("monster_spawn_block_light_limit", 0);
      dimensionTypeJson.addProperty("monster_spawn_light_level", 0);

      JsonObject biomeSource = new JsonObject();
      biomeSource.addProperty("world_scale", scale);
      biomeSource.addProperty("apron_chunks", DEFAULT_APRON_CHUNKS);
      biomeSource.addProperty("excluded_radius_meters", 300000.0);
      biomeSource.addProperty("transition_width_meters", 50000.0);
      JsonObject fallback = new JsonObject();
      fallback.addProperty("biome", "minecraft:plains");
      biomeSource.add("fallback", fallback);
      biomeSource.add("settings", settings);

      JsonObject generator = new JsonObject();
      generator.addProperty("type", "tellus:cube_net");
      generator.addProperty("geometry_version", 3);
      generator.addProperty("mapping", SphereWorldModel.MAPPING_ID);
      generator.addProperty("excluded_radius_meters", 300000.0);
      generator.addProperty("transition_width_meters", 50000.0);
      generator.add("biome_source", biomeSource);
      generator.addProperty("world_scale", scale);
      generator.addProperty("sea_level", seaLevel);
      generator.addProperty("min_y", DEFAULT_MIN_Y);
      generator.addProperty("height", DEFAULT_HEIGHT);
      generator.addProperty("experimental_apron_chunks", DEFAULT_APRON_CHUNKS);
      JsonObject dimension = new JsonObject();
      dimension.addProperty("type", dimensionType);
      dimension.add("generator", generator);

      JsonObject packMeta = new JsonObject();
      JsonObject pack = new JsonObject();
      pack.addProperty("pack_format", PACK_FORMAT);
      pack.addProperty("description", "Tellus cube-net world " + world + ", scale " + scale);
      packMeta.add("pack", pack);

      List<Path> files = new ArrayList<>(3);
      files.add(writeJson(output.resolve("pack.mcmeta"), packMeta));
      files.add(writeJson(output.resolve("data").resolve(namespace).resolve("dimension_type").resolve(name + "_surface.json"), dimensionTypeJson));
      files.add(writeJson(output.resolve("data").resolve(namespace).resolve("dimension").resolve(name + "_cube_net.json"), dimension));
      return new Result(output, List.copyOf(files));
   }

   private static void addSettingsFields(JsonObject json, CubeNetBiomeSource.Settings settings) {
      json.addProperty("random_biomes", settings.randomBiomes());
      json.addProperty("random_biome_density", settings.randomBiomeDensity());
      json.addProperty("random_biome_seed", settings.randomBiomeSeed());
      var randomIds = new com.google.gson.JsonArray();
      settings.randomBiomeIds().forEach(randomIds::add);
      json.add("random_biome_ids", randomIds);
      json.addProperty("cave_generation", settings.caveGeneration());
      json.addProperty("deep_dark", settings.deepDark());
      json.addProperty("underground_depth", settings.undergroundDepth());
      json.addProperty("ore_veins", settings.oreVeins());
      json.addProperty("geological_stone_patches", settings.geologicalStonePatches());
      json.addProperty("caves_reach_surface", settings.cavesReachSurface());
      json.addProperty("roads", settings.roads());
      json.addProperty("buildings", settings.buildings());
      json.addProperty("add_strongholds", settings.addStrongholds());
      json.addProperty("add_villages", settings.addVillages());
      json.addProperty("add_mineshafts", settings.addMineshafts());
      json.addProperty("add_ocean_monuments", settings.addOceanMonuments());
      json.addProperty("add_woodland_mansions", settings.addWoodlandMansions());
      json.addProperty("add_desert_temples", settings.addDesertTemples());
      json.addProperty("add_jungle_temples", settings.addJungleTemples());
      json.addProperty("add_pillager_outposts", settings.addPillagerOutposts());
      json.addProperty("add_ruined_portals", settings.addRuinedPortals());
      json.addProperty("add_shipwrecks", settings.addShipwrecks());
      json.addProperty("add_ocean_ruins", settings.addOceanRuins());
      json.addProperty("add_buried_treasure", settings.addBuriedTreasure());
      json.addProperty("add_igloos", settings.addIgloos());
      json.addProperty("add_witch_huts", settings.addWitchHuts());
      json.addProperty("add_ancient_cities", settings.addAncientCities());
      json.addProperty("add_trial_chambers", settings.addTrialChambers());
      json.addProperty("add_trail_ruins", settings.addTrailRuins());
      json.addProperty("add_abandoned_camps", settings.addAbandonedCamps());
      json.addProperty("lake_freezing", settings.lakeFreezing());
      json.addProperty("lava_pools", settings.lavaPools());
      json.addProperty("geodes", settings.geodes());
      json.addProperty("enable_water", settings.enableWater());
      json.addProperty("teleport_progress", settings.teleportProgress());
      json.addProperty("height_offset", settings.heightOffset());
   }

   private static boolean isSupportedScale(double scale) {
      double faceBlocks = Math.ceil(Math.PI * SphereWorldModel.EARTH_RADIUS_METERS * 0.5 / scale);
      return Double.isFinite(faceBlocks) && faceBlocks <= Integer.MAX_VALUE - 1024;
   }

   private static boolean isIdentifier(String value) {
      if (value == null || !IDENTIFIER.matcher(value).matches()) return false;
      String path = value.substring(value.indexOf(':') + 1);
      for (String part : path.split("/")) {
         if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
      }
      return true;
   }

   private static void requireEmptyDirectory(Path output) throws IOException {
      if (Files.exists(output)) {
         if (!Files.isDirectory(output)) throw new IOException("output must be a new or empty directory");
         try (DirectoryStream<Path> entries = Files.newDirectoryStream(output)) {
            if (entries.iterator().hasNext()) throw new IOException("output must be a new or empty directory");
         }
      }
      Files.createDirectories(output);
   }

   private static Path writeJson(Path path, JsonObject document) throws IOException {
      Files.createDirectories(path.getParent());
      try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
         GSON.toJson(document, writer);
         writer.write('\n');
      }
      return path;
   }

   public record Result(Path output, List<Path> files) {
   }
}
