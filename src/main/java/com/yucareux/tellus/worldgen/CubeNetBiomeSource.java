package com.yucareux.tellus.worldgen;

import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.world.data.biome.BiomeClassification;
import com.yucareux.tellus.world.data.biome.BiomeClassificationProviders;
import com.yucareux.tellus.world.data.ocean.OisstOceanClimateSource;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.caves.TellusCaveBiomeDepthPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.util.Mth;

/**
 * Geodetic surface biome source for the Cube-Net coordinate view.
 *
 * <p>The codec receives biome holders from the current world registry. The
 * source resolves a planar quart position to one canonical spherical column,
 * then applies the first-stage cover/water policy. More detailed climate and
 * inland-water rules can extend this policy without changing coordinate
 * ownership.</p>
 */
public final class CubeNetBiomeSource extends TellusBiomeSourceBridge {
   /** Settings that belong to the spherical view rather than the planar Earth generator. */
   public record Settings(
      boolean randomBiomes,
      double randomBiomeDensity,
      long randomBiomeSeed,
      List<String> randomBiomeIds,
      boolean caveGeneration,
      boolean deepDark,
      int undergroundDepth,
      boolean oreVeins,
      boolean geologicalStonePatches,
      boolean cavesReachSurface,
      boolean roads,
       boolean buildings,
       boolean addStrongholds,
       boolean addVillages,
       boolean addMineshafts,
       boolean addOceanMonuments,
       boolean addWoodlandMansions,
       boolean addDesertTemples,
       boolean addJungleTemples,
       boolean addPillagerOutposts,
       boolean addRuinedPortals,
       boolean addShipwrecks,
       boolean addOceanRuins,
       boolean addBuriedTreasure,
       boolean addIgloos,
       boolean addWitchHuts,
       boolean addAncientCities,
       boolean addTrialChambers,
       boolean addTrailRuins,
       boolean addAbandonedCamps
   ) {
      public static final Settings DEFAULT = new Settings(
         false,
         EarthGeneratorSettings.DEFAULT_RANDOM_BIOME_DENSITY,
         EarthGeneratorSettings.DEFAULT_RANDOM_BIOME_SEED,
         MinecraftVersionCompat.defaultRandomBiomeIds(),
         true,
         false,
         EarthGeneratorSettings.DEFAULT_UNDERGROUND_DEPTH,
         Boolean.parseBoolean(System.getProperty("tellus.cubeNet.oreVeins", "true")),
         Boolean.parseBoolean(System.getProperty("tellus.cubeNet.geologicalStonePatches", "true")),
         Boolean.parseBoolean(System.getProperty("tellus.cubeNet.cavesReachSurface", "false")),
         Boolean.parseBoolean(System.getProperty("tellus.cubeNet.roads", "true")),
         Boolean.parseBoolean(System.getProperty("tellus.cubeNet.buildings", "true")),
          EarthGeneratorSettings.DEFAULT.addStrongholds(),
          EarthGeneratorSettings.DEFAULT.addVillages(),
          EarthGeneratorSettings.DEFAULT.addMineshafts(),
          EarthGeneratorSettings.DEFAULT.addOceanMonuments(),
          EarthGeneratorSettings.DEFAULT.addWoodlandMansions(),
          EarthGeneratorSettings.DEFAULT.addDesertTemples(),
          EarthGeneratorSettings.DEFAULT.addJungleTemples(),
          EarthGeneratorSettings.DEFAULT.addPillagerOutposts(),
          EarthGeneratorSettings.DEFAULT.addRuinedPortals(),
          EarthGeneratorSettings.DEFAULT.addShipwrecks(),
          EarthGeneratorSettings.DEFAULT.addOceanRuins(),
          EarthGeneratorSettings.DEFAULT.addBuriedTreasure(),
          EarthGeneratorSettings.DEFAULT.addIgloos(),
          EarthGeneratorSettings.DEFAULT.addWitchHuts(),
          EarthGeneratorSettings.DEFAULT.addAncientCities(),
          EarthGeneratorSettings.DEFAULT.addTrialChambers(),
          EarthGeneratorSettings.DEFAULT.addTrailRuins(),
          EarthGeneratorSettings.DEFAULT.addAbandonedCamps()
      );
       private record BaseSettings(boolean randomBiomes, double randomBiomeDensity, long randomBiomeSeed,
          List<String> randomBiomeIds, boolean caveGeneration, boolean deepDark, int undergroundDepth,
          boolean oreVeins, boolean geologicalStonePatches, boolean cavesReachSurface, boolean roads, boolean buildings) { }
       private record StructureSettingsA(boolean addStrongholds, boolean addVillages, boolean addMineshafts,
          boolean addOceanMonuments, boolean addWoodlandMansions, boolean addDesertTemples,
          boolean addJungleTemples, boolean addPillagerOutposts, boolean addRuinedPortals) { }
       private record StructureSettingsB(boolean addShipwrecks, boolean addOceanRuins, boolean addBuriedTreasure,
          boolean addIgloos, boolean addWitchHuts, boolean addAncientCities, boolean addTrialChambers,
          boolean addTrailRuins, boolean addAbandonedCamps) { }
       private static final MapCodec<BaseSettings> BASE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
          Codec.BOOL.optionalFieldOf("random_biomes", DEFAULT.randomBiomes()).forGetter(BaseSettings::randomBiomes),
          Codec.DOUBLE.optionalFieldOf("random_biome_density", DEFAULT.randomBiomeDensity()).forGetter(BaseSettings::randomBiomeDensity),
          Codec.LONG.optionalFieldOf("random_biome_seed", DEFAULT.randomBiomeSeed()).forGetter(BaseSettings::randomBiomeSeed),
          Codec.STRING.listOf().optionalFieldOf("random_biome_ids", DEFAULT.randomBiomeIds()).forGetter(BaseSettings::randomBiomeIds),
          Codec.BOOL.optionalFieldOf("cave_generation", DEFAULT.caveGeneration()).forGetter(BaseSettings::caveGeneration),
          Codec.BOOL.optionalFieldOf("deep_dark", DEFAULT.deepDark()).forGetter(BaseSettings::deepDark),
          Codec.intRange(EarthGeneratorSettings.MIN_UNDERGROUND_DEPTH, EarthGeneratorSettings.MAX_UNDERGROUND_DEPTH)
             .optionalFieldOf("underground_depth", DEFAULT.undergroundDepth()).forGetter(BaseSettings::undergroundDepth),
          Codec.BOOL.optionalFieldOf("ore_veins", DEFAULT.oreVeins()).forGetter(BaseSettings::oreVeins),
          Codec.BOOL.optionalFieldOf("geological_stone_patches", DEFAULT.geologicalStonePatches()).forGetter(BaseSettings::geologicalStonePatches),
          Codec.BOOL.optionalFieldOf("caves_reach_surface", DEFAULT.cavesReachSurface()).forGetter(BaseSettings::cavesReachSurface),
          Codec.BOOL.optionalFieldOf("roads", DEFAULT.roads()).forGetter(BaseSettings::roads),
          Codec.BOOL.optionalFieldOf("buildings", DEFAULT.buildings()).forGetter(BaseSettings::buildings)
       ).apply(instance, BaseSettings::new));
       private static final MapCodec<StructureSettingsA> STRUCTURES_A_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
          Codec.BOOL.optionalFieldOf("add_strongholds", DEFAULT.addStrongholds()).forGetter(StructureSettingsA::addStrongholds),
          Codec.BOOL.optionalFieldOf("add_villages", DEFAULT.addVillages()).forGetter(StructureSettingsA::addVillages),
          Codec.BOOL.optionalFieldOf("add_mineshafts", DEFAULT.addMineshafts()).forGetter(StructureSettingsA::addMineshafts),
          Codec.BOOL.optionalFieldOf("add_ocean_monuments", DEFAULT.addOceanMonuments()).forGetter(StructureSettingsA::addOceanMonuments),
          Codec.BOOL.optionalFieldOf("add_woodland_mansions", DEFAULT.addWoodlandMansions()).forGetter(StructureSettingsA::addWoodlandMansions),
          Codec.BOOL.optionalFieldOf("add_desert_temples", DEFAULT.addDesertTemples()).forGetter(StructureSettingsA::addDesertTemples),
          Codec.BOOL.optionalFieldOf("add_jungle_temples", DEFAULT.addJungleTemples()).forGetter(StructureSettingsA::addJungleTemples),
          Codec.BOOL.optionalFieldOf("add_pillager_outposts", DEFAULT.addPillagerOutposts()).forGetter(StructureSettingsA::addPillagerOutposts),
          Codec.BOOL.optionalFieldOf("add_ruined_portals", DEFAULT.addRuinedPortals()).forGetter(StructureSettingsA::addRuinedPortals)
       ).apply(instance, StructureSettingsA::new));
       private static final MapCodec<StructureSettingsB> STRUCTURES_B_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
          Codec.BOOL.optionalFieldOf("add_shipwrecks", DEFAULT.addShipwrecks()).forGetter(StructureSettingsB::addShipwrecks),
          Codec.BOOL.optionalFieldOf("add_ocean_ruins", DEFAULT.addOceanRuins()).forGetter(StructureSettingsB::addOceanRuins),
          Codec.BOOL.optionalFieldOf("add_buried_treasure", DEFAULT.addBuriedTreasure()).forGetter(StructureSettingsB::addBuriedTreasure),
          Codec.BOOL.optionalFieldOf("add_igloos", DEFAULT.addIgloos()).forGetter(StructureSettingsB::addIgloos),
          Codec.BOOL.optionalFieldOf("add_witch_huts", DEFAULT.addWitchHuts()).forGetter(StructureSettingsB::addWitchHuts),
          Codec.BOOL.optionalFieldOf("add_ancient_cities", DEFAULT.addAncientCities()).forGetter(StructureSettingsB::addAncientCities),
          Codec.BOOL.optionalFieldOf("add_trial_chambers", DEFAULT.addTrialChambers()).forGetter(StructureSettingsB::addTrialChambers),
          Codec.BOOL.optionalFieldOf("add_trail_ruins", DEFAULT.addTrailRuins()).forGetter(StructureSettingsB::addTrailRuins),
          Codec.BOOL.optionalFieldOf("add_abandoned_camps", DEFAULT.addAbandonedCamps()).forGetter(StructureSettingsB::addAbandonedCamps)
       ).apply(instance, StructureSettingsB::new));
       private record BaseAndA(BaseSettings base, StructureSettingsA structures) { }
       public static final Codec<Settings> CODEC = MapCodec.of(
          new com.mojang.serialization.MapEncoder.Implementation<Settings>() {
             public <T> RecordBuilder<T> encode(Settings input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
                BaseSettings base = new BaseSettings(input.randomBiomes(), input.randomBiomeDensity(), input.randomBiomeSeed(), input.randomBiomeIds(),
                   input.caveGeneration(), input.deepDark(), input.undergroundDepth(), input.oreVeins(), input.geologicalStonePatches(),
                   input.cavesReachSurface(), input.roads(), input.buildings());
                StructureSettingsA a = new StructureSettingsA(input.addStrongholds(), input.addVillages(), input.addMineshafts(), input.addOceanMonuments(),
                   input.addWoodlandMansions(), input.addDesertTemples(), input.addJungleTemples(), input.addPillagerOutposts(), input.addRuinedPortals());
                StructureSettingsB b = new StructureSettingsB(input.addShipwrecks(), input.addOceanRuins(), input.addBuriedTreasure(), input.addIgloos(),
                   input.addWitchHuts(), input.addAncientCities(), input.addTrialChambers(), input.addTrailRuins(), input.addAbandonedCamps());
                return STRUCTURES_B_CODEC.encode(b, ops, STRUCTURES_A_CODEC.encode(a, ops, BASE_CODEC.encode(base, ops, prefix)));
             }
             public <T> Stream<T> keys(DynamicOps<T> ops) {
                return Stream.concat(BASE_CODEC.keys(ops), Stream.concat(STRUCTURES_A_CODEC.keys(ops), STRUCTURES_B_CODEC.keys(ops)));
             }
          },
          new com.mojang.serialization.MapDecoder.Implementation<Settings>() {
             public <T> com.mojang.serialization.DataResult<Settings> decode(DynamicOps<T> ops, com.mojang.serialization.MapLike<T> input) {
                return BASE_CODEC.decode(ops, input).apply2(BaseAndA::new, STRUCTURES_A_CODEC.decode(ops, input)).apply2(
                   (baseAndA, b) -> new Settings(baseAndA.base().randomBiomes(), baseAndA.base().randomBiomeDensity(), baseAndA.base().randomBiomeSeed(),
                      baseAndA.base().randomBiomeIds(), baseAndA.base().caveGeneration(), baseAndA.base().deepDark(), baseAndA.base().undergroundDepth(),
                      baseAndA.base().oreVeins(), baseAndA.base().geologicalStonePatches(), baseAndA.base().cavesReachSurface(), baseAndA.base().roads(),
                      baseAndA.base().buildings(), baseAndA.structures().addStrongholds(), baseAndA.structures().addVillages(), baseAndA.structures().addMineshafts(),
                      baseAndA.structures().addOceanMonuments(), baseAndA.structures().addWoodlandMansions(), baseAndA.structures().addDesertTemples(),
                      baseAndA.structures().addJungleTemples(), baseAndA.structures().addPillagerOutposts(), baseAndA.structures().addRuinedPortals(),
                      b.addShipwrecks(), b.addOceanRuins(), b.addBuriedTreasure(), b.addIgloos(), b.addWitchHuts(), b.addAncientCities(),
                      b.addTrialChambers(), b.addTrailRuins(), b.addAbandonedCamps()), STRUCTURES_B_CODEC.decode(ops, input));
             }
             public <T> Stream<T> keys(DynamicOps<T> ops) {
                return Stream.concat(BASE_CODEC.keys(ops), Stream.concat(STRUCTURES_A_CODEC.keys(ops), STRUCTURES_B_CODEC.keys(ops)));
             }
          }
       ).codec();
       TellusStructurePolicy.StructureToggles structureToggles() {
          return new TellusStructurePolicy.StructureToggles(
             EarthGeneratorSettings.DEFAULT.suppressesUndergroundGenerationForTerrainShell(),
             this.addStrongholds(),
             this.addVillages(),
             this.addMineshafts(),
             this.addOceanMonuments(),
             this.addWoodlandMansions(),
             this.addDesertTemples(),
             this.addJungleTemples(),
             this.addPillagerOutposts(),
             this.addRuinedPortals(),
             this.addShipwrecks(),
             this.addOceanRuins(),
             this.addBuriedTreasure(),
             this.addIgloos(),
             this.addWitchHuts(),
             this.addAncientCities(),
             this.addTrialChambers(),
             this.addTrailRuins(),
             this.addAbandonedCamps()
          );
       }

       public Settings {
         randomBiomeIds = List.copyOf(randomBiomeIds == null ? List.of() : randomBiomeIds);
         if (!Double.isFinite(randomBiomeDensity) || randomBiomeDensity < 0.0 || randomBiomeDensity > 0.4) {
            throw new IllegalArgumentException("random_biome_density");
         }
         if (undergroundDepth < EarthGeneratorSettings.MIN_UNDERGROUND_DEPTH
            || undergroundDepth > EarthGeneratorSettings.MAX_UNDERGROUND_DEPTH) {
            throw new IllegalArgumentException("underground_depth");
         }
      }
   }

   public static final MapCodec<CubeNetBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
      RegistryOps.retrieveGetter(Registries.BIOME),
      com.mojang.serialization.Codec.DOUBLE.fieldOf("world_scale").forGetter(source -> source.model.config().worldScale()),
      com.mojang.serialization.Codec.intRange(0, 35).optionalFieldOf("apron_chunks", 5)
         .forGetter(source -> source.apronChunks),
      com.mojang.serialization.Codec.DOUBLE.optionalFieldOf("excluded_radius_meters",
         SphereSingularityPolicy.DEFAULT_CONFIG.excludedRadiusMeters())
         .forGetter(source -> source.policy.config().excludedRadiusMeters()),
      com.mojang.serialization.Codec.DOUBLE.optionalFieldOf("transition_width_meters",
         SphereSingularityPolicy.DEFAULT_CONFIG.transitionWidthMeters())
         .forGetter(source -> source.policy.config().transitionWidthMeters()),
      FixedBiomeSource.CODEC.fieldOf("fallback").forGetter(source -> source.fallback),
      Settings.CODEC.optionalFieldOf("settings", Settings.DEFAULT).forGetter(source -> source.settings)
   ).apply(instance, (biomes, scale, apronChunks, excludedRadius, transitionWidth, fallback, settings) ->
      new CubeNetBiomeSource(biomes, scale, apronChunks,
         new SphereSingularityPolicy.Config(excludedRadius, transitionWidth), fallback, null, null, null, settings)));

   private final SphereWorldModel model;
   private final CubeNetLayout layout;
   private final CubeNetApronResolver apronResolver;
   private final SphereSingularityPolicy policy;
   private final int apronChunks;
   private final HolderGetter<Biome> biomes;
   private final FixedBiomeSource fallback;
   private final Holder<Biome> plains;
   private final Holder<Biome> ocean;
   private final Holder<Biome> warmOcean;
   private final Holder<Biome> lukewarmOcean;
   private final Holder<Biome> coldOcean;
   private final Holder<Biome> frozenOcean;
   private final Holder<Biome> deepOcean;
   private final Holder<Biome> deepLukewarmOcean;
   private final Holder<Biome> deepColdOcean;
   private final Holder<Biome> deepFrozenOcean;
   private final Holder<Biome> snowyPlains;
   private final Holder<Biome> frozenPeaks;
   private final Holder<Biome> snowySlopes;
   private final Holder<Biome> jaggedPeaks;
   private final Holder<Biome> sunflowerPlains;
   private final Holder<Biome> flowerForest;
   private final Holder<Biome> cherryGrove;
   private final Holder<Biome> dappledForest;
   private final Holder<Biome> desert;
   private final Holder<Biome> swamp;
   private final Holder<Biome> mangrove;
   private final Holder<Biome> river;
   private final Holder<Biome> lushCaves;
   private final Holder<Biome> dripstoneCaves;
   private final Holder<Biome> deepDark;
   private final Supplier<SphereChunkSampler> sampler;
   private final ClimateSampler climateSampler;
   private final InlandWaterSampler inlandWaterSampler;
   private final Settings settings;
   private final RandomBiomeMixer randomBiomeMixer;
   private final BadlandsRegionField badlandsRegion;

   @FunctionalInterface
   interface ClimateSampler {
      ClimateData sample(SphereWorldModel.GeoPoint point, double worldScale, boolean water);
   }

   record ClimateData(String koppen, OisstOceanClimateSource.Sample ocean) { }

   @FunctionalInterface
   interface InlandWaterSampler {
      WaterData sample(SphereWorldModel.GeoPoint point, double worldScale);
   }

   record WaterData(boolean hasWater, boolean ocean) {
      static WaterData dry() { return new WaterData(false, false); }
   }

   CubeNetBiomeSource(
      HolderGetter<Biome> biomes, double scale, int apronChunks,
      SphereSingularityPolicy.Config policy, FixedBiomeSource fallback,
      SphereChunkSampler.PointSampler pointSampler
   ) {
      this(biomes, scale, apronChunks, policy, fallback, pointSampler, null, null, Settings.DEFAULT);
   }

   CubeNetBiomeSource(
      HolderGetter<Biome> biomes, double scale, int apronChunks,
      SphereSingularityPolicy.Config policy, FixedBiomeSource fallback,
      SphereChunkSampler.PointSampler pointSampler, ClimateSampler climateSampler
   ) {
      this(biomes, scale, apronChunks, policy, fallback, pointSampler, climateSampler, null, Settings.DEFAULT);
   }

   CubeNetBiomeSource(
      HolderGetter<Biome> biomes, double scale, int apronChunks,
      SphereSingularityPolicy.Config policy, FixedBiomeSource fallback,
      SphereChunkSampler.PointSampler pointSampler, ClimateSampler climateSampler,
      InlandWaterSampler inlandWaterSampler
   ) {
      this(biomes, scale, apronChunks, policy, fallback, pointSampler, climateSampler, inlandWaterSampler, Settings.DEFAULT);
   }

   CubeNetBiomeSource(
      HolderGetter<Biome> biomes, double scale, int apronChunks,
      SphereSingularityPolicy.Config policy, FixedBiomeSource fallback,
      SphereChunkSampler.PointSampler pointSampler, ClimateSampler climateSampler,
      InlandWaterSampler inlandWaterSampler, Settings settings
   ) {
      this.model = SphereWorldModel.forWorldScale(scale);
      if (apronChunks < 0 || apronChunks > 35 || (long)apronChunks * 16L > this.model.config().faceBlocks()) {
         throw new IllegalArgumentException("apron_chunks");
      }
      this.layout = CubeNetLayout.standard(this.model.config().faceBlocks());
      this.apronChunks = apronChunks;
      this.apronResolver = new CubeNetApronResolver(this.layout, Math.max(16, apronChunks * 16));
      this.policy = new SphereSingularityPolicy(this.model, Objects.requireNonNull(policy, "policy"));
      this.biomes = Objects.requireNonNull(biomes, "biomes");
      this.fallback = Objects.requireNonNull(fallback, "fallback");
      this.settings = Objects.requireNonNull(settings, "settings");
      this.plains = biomes.getOrThrow(Biomes.PLAINS);
      this.ocean = biomes.getOrThrow(Biomes.OCEAN);
      this.warmOcean = this.resolveBiome(Biomes.WARM_OCEAN, this.ocean);
      this.lukewarmOcean = this.resolveBiome(Biomes.LUKEWARM_OCEAN, this.ocean);
      this.coldOcean = this.resolveBiome(Biomes.COLD_OCEAN, this.ocean);
      this.frozenOcean = this.resolveBiome(Biomes.FROZEN_OCEAN, this.ocean);
      this.deepOcean = this.resolveBiome(Biomes.DEEP_OCEAN, this.ocean);
      this.deepLukewarmOcean = this.resolveBiome(Biomes.DEEP_LUKEWARM_OCEAN, this.deepOcean);
      this.deepColdOcean = this.resolveBiome(Biomes.DEEP_COLD_OCEAN, this.deepOcean);
      this.deepFrozenOcean = this.resolveBiome(Biomes.DEEP_FROZEN_OCEAN, this.deepOcean);
      this.snowyPlains = biomes.getOrThrow(Biomes.SNOWY_PLAINS);
      this.frozenPeaks = this.resolveBiome(Biomes.FROZEN_PEAKS, this.snowyPlains);
      this.snowySlopes = this.resolveBiome(Biomes.SNOWY_SLOPES, this.frozenPeaks);
      this.jaggedPeaks = this.resolveBiome(Biomes.JAGGED_PEAKS, this.frozenPeaks);
      this.sunflowerPlains = this.resolveBiome(Biomes.SUNFLOWER_PLAINS, this.plains);
      this.flowerForest = this.resolveBiome(Biomes.FLOWER_FOREST, this.plains);
      this.cherryGrove = this.resolveBiome(Biomes.CHERRY_GROVE, this.flowerForest);
      this.dappledForest = this.resolveOptionalBiome(BiomeClassification.toBiomeKey("dappled_forest"));
      this.desert = biomes.getOrThrow(Biomes.DESERT);
      this.swamp = biomes.getOrThrow(Biomes.SWAMP);
      this.mangrove = biomes.getOrThrow(Biomes.MANGROVE_SWAMP);
      this.river = biomes.getOrThrow(Biomes.RIVER);
      this.lushCaves = this.resolveOptionalBiome(Biomes.LUSH_CAVES);
      this.dripstoneCaves = this.resolveOptionalBiome(Biomes.DRIPSTONE_CAVES);
      this.deepDark = this.resolveOptionalBiome(Biomes.DEEP_DARK);
      this.sampler = Suppliers.memoize(() -> pointSampler == null
         ? TellusWorldgenSources.sphericalSampler(this.model)
         : new SphereChunkSampler(this.model, pointSampler));
      this.climateSampler = climateSampler == null ? CubeNetBiomeSource::sampleDefaultClimate : climateSampler;
      this.inlandWaterSampler = inlandWaterSampler == null
         ? CubeNetBiomeSource::sampleDefaultInlandWater : inlandWaterSampler;
      this.randomBiomeMixer = new RandomBiomeMixer(
         this.biomes, this.settings.randomBiomeDensity(), this.settings.randomBiomeSeed(), this.settings.randomBiomeIds());
      this.badlandsRegion = new BadlandsRegionField(this.model.config().worldScale());
   }

   public CubeNetBiomeSource(HolderGetter<Biome> biomes, double scale, int apronChunks,
      FixedBiomeSource fallback) {
      this(biomes, scale, apronChunks, SphereSingularityPolicy.DEFAULT_CONFIG, fallback, null);
   }

   public CubeNetBiomeSource(HolderGetter<Biome> biomes, double scale, int apronChunks,
      FixedBiomeSource fallback, Settings settings) {
      this(biomes, scale, apronChunks, SphereSingularityPolicy.DEFAULT_CONFIG, fallback, null, null, null, settings);
   }

   Settings settings() {
      return this.settings;
   }

   FixedBiomeSource fallback() {
      return this.fallback;
   }

   double worldScale() {
      return this.model.config().worldScale();
   }

   int apronChunks() {
      return this.apronChunks;
   }

   SphereSingularityPolicy.Config singularityConfig() {
      return this.policy.config();
   }

   @Override
   protected MapCodec<? extends BiomeSource> tellusBiomeSourceMapCodec() {
      return CODEC;
   }

   @Override
   protected Stream<Holder<Biome>> collectPossibleBiomes() {
      Set<Holder<Biome>> result = new HashSet<>();
      result.add(this.fallback.getNoiseBiome(0, 0, 0));
      result.add(this.plains);
      result.add(this.ocean);
      result.add(this.warmOcean);
      result.add(this.lukewarmOcean);
      result.add(this.coldOcean);
      result.add(this.frozenOcean);
      result.add(this.deepOcean);
      result.add(this.deepLukewarmOcean);
      result.add(this.deepColdOcean);
      result.add(this.deepFrozenOcean);
      result.add(this.snowyPlains);
      result.add(this.frozenPeaks);
      result.add(this.snowySlopes);
      result.add(this.jaggedPeaks);
      result.add(this.sunflowerPlains);
      result.add(this.flowerForest);
      result.add(this.cherryGrove);
      result.add(this.desert);
      result.add(this.swamp);
      result.add(this.mangrove);
      result.add(this.river);
      if (this.dappledForest != null) result.add(this.dappledForest);
      for (ResourceKey<Biome> key : BiomeClassification.allBiomeKeys()) result.add(this.resolveBiome(key, this.plains));
      for (ResourceKey<Biome> key : BiomeClassificationProviders.allBiomeKeys()) result.add(this.resolveBiome(key, this.plains));
      if (this.lushCaves != null) result.add(this.lushCaves);
      if (this.dripstoneCaves != null) result.add(this.dripstoneCaves);
      if (this.deepDark != null) result.add(this.deepDark);
      result.addAll(this.randomBiomeMixer.possibleBiomes());
      return result.stream();
   }

   @Override
   public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
      int blockX = QuartPos.toBlock(quartX);
      int blockZ = QuartPos.toBlock(quartZ);
      Optional<SphereWorldModel.SphereBlockAddress> address = this.resolveAddress(blockX, blockZ);
      if (address.isEmpty()) return this.fallback.getNoiseBiome(quartX, quartY, quartZ);
      SphereWorldModel.SphereBlockAddress canonical = address.get();
      if (!this.policy.admitsTerrainMaterialization(
         new SphereWorldModel.SphereChunkAddress(canonical.face(),
            Math.floorDiv(canonical.blockX(), 16L), Math.floorDiv(canonical.blockZ(), 16L)))) {
         return this.fallback.getNoiseBiome(quartX, quartY, quartZ);
      }
      SphereChunkSampler.SurfaceData surface = this.sampler.get().samplePoint(this.model.surfacePoint(canonical));
      if (!surface.elevationAvailable()) return this.fallback.getNoiseBiome(quartX, quartY, quartZ);
      SphereWorldModel.GeoPoint point = this.model.surfacePoint(canonical);
      int[] noiseCoordinates = this.noiseCoordinates(point);
      boolean surfaceWater = surface.waterAvailable();
      if (surfaceWater) {
         if (!surface.oceanWater()) return this.applyRandomRiverBiome(noiseCoordinates[0], noiseCoordinates[1]);
         ClimateData climate = Objects.requireNonNull(this.climateSampler.sample(
            point, this.model.config().worldScale(), true), "climateData");
         return this.resolveCaveAwareBiome(
            this.resolveOceanBiome(surface, climate.ocean(), noiseCoordinates[0], noiseCoordinates[1]),
            canonical, surface, quartY, noiseCoordinates[0], noiseCoordinates[1]);
      }
      if (isWaterCandidate(surface.coverClass())) {
         WaterData mappedWater = Objects.requireNonNull(this.inlandWaterSampler.sample(
            point, this.model.config().worldScale()), "waterData");
         if (mappedWater.hasWater()) {
            if (!mappedWater.ocean()) return this.applyRandomRiverBiome(noiseCoordinates[0], noiseCoordinates[1]);
            ClimateData climate = Objects.requireNonNull(this.climateSampler.sample(
               point, this.model.config().worldScale(), true), "climateData");
            return this.resolveCaveAwareBiome(
               this.resolveOceanBiome(surface, climate.ocean(), noiseCoordinates[0], noiseCoordinates[1]),
               canonical, surface, quartY, noiseCoordinates[0], noiseCoordinates[1]);
         }
      }
      ClimateData climate = Objects.requireNonNull(this.climateSampler.sample(
         point, this.model.config().worldScale(), false), "climateData");
      String koppen = climate.koppen();
      double earthBlockX = EarthProjection.longitudeToBlockX(point.longitude(), this.model.config().worldScale());
      double earthBlockZ = EarthProjection.latToBlockZ(point.latitude(), this.model.config().worldScale());
      int visualCover = this.sampleVisualCover(point, surface.coverClass(), earthBlockX, earthBlockZ);
      Holder<Biome> land = this.resolveLandBiome(
         surface.coverClass(), visualCover, koppen, point, noiseCoordinates[0], noiseCoordinates[1]);
      return this.resolveCaveAwareBiome(
         land, canonical, surface, quartY, noiseCoordinates[0], noiseCoordinates[1]);
   }

   private Holder<Biome> resolveOceanBiome(SphereChunkSampler.SurfaceData surface,
      OisstOceanClimateSource.Sample climate, int blockX, int blockZ) {
      if (climate == null || !climate.available()) return this.ocean;
      boolean deep = surface.elevationMeters() <= -200.0;
      Holder<Biome> biome;
      if (climate.maxIceFraction() >= 0.15 || climate.meanSstC() <= -1.5) {
         biome = deep ? this.deepFrozenOcean : this.frozenOcean;
      } else if (climate.meanSstC() >= 23.0) {
         biome = deep ? this.deepLukewarmOcean : this.warmOcean;
      } else if (climate.meanSstC() >= 13.0) {
         biome = deep ? this.deepLukewarmOcean : this.lukewarmOcean;
      } else {
         biome = deep ? this.deepColdOcean : this.coldOcean;
      }
      return this.settings.randomBiomes() ? this.randomBiomeMixer.mixOcean(biome, blockX, blockZ, deep) : biome;
   }

   private Holder<Biome> applyRandomRiverBiome(int blockX, int blockZ) {
      return this.settings.randomBiomes() ? this.randomBiomeMixer.mixRiver(this.river, blockX, blockZ) : this.river;
   }

   private Holder<Biome> resolveLandBiome(
      int rawCover, int visualCover, String koppen, SphereWorldModel.GeoPoint point, int blockX, int blockZ
   ) {
      if (rawCover == 95) return this.mangrove;
      if (visualCover == 70 || this.isAntarcticSnow(rawCover, point)) {
         Holder<Biome> snow = this.resolveSnowBiome(point, blockX, blockZ);
         return this.settings.randomBiomes() ? this.randomBiomeMixer.mixLand(snow, blockX, blockZ, koppen) : snow;
      }

      String climate = koppen;
      if (BadlandsTerrainPolicy.isDryCanyonCover(visualCover)) {
         String coherent = TellusWorldgenSources.koppen().sampleCoherentCode(
            EarthProjection.longitudeToBlockX(point.longitude(), this.model.config().worldScale()),
            EarthProjection.latToBlockZ(point.latitude(), this.model.config().worldScale()),
            this.model.config().worldScale());
         if (BadlandsTerrainPolicy.shouldUseCoherentAridClimate(visualCover, coherent)) climate = coherent;
      }

      ResourceKey<Biome> key = BiomeClassificationProviders.findBiomeKey(visualCover, climate, blockX, blockZ,
         this.model.config().worldScale());
      if (key == null) key = BiomeClassification.findBiomeKey(visualCover, climate);
      if (key == null) key = BiomeClassification.findFallbackKey(visualCover);
      Holder<Biome> biome = this.resolveBiome(key, this.plains);

      if (BadlandsTerrainPolicy.isDryCanyonCover(visualCover)
         && BadlandsTerrainPolicy.shouldPromoteToBadlands(visualCover, climate,
            this.sampleRegionalRelief(point, blockX, blockZ))) {
         biome = this.resolveBiome(Biomes.BADLANDS, biome);
      }
      biome = this.applyRareBiomeVariants(biome, visualCover, climate, point, blockX, blockZ);
      return this.settings.randomBiomes() ? this.randomBiomeMixer.mixLand(biome, blockX, blockZ, climate) : biome;
   }

   private Holder<Biome> applyRareBiomeVariants(Holder<Biome> biome, int coverClass, String koppen,
      SphereWorldModel.GeoPoint point, int blockX, int blockZ) {
      if (koppen == null) return biome;
      String climate = koppen.toUpperCase(Locale.ROOT);
      if (coverClass == 10 && isTemperateForest(biome)) {
         if (this.settings.randomBiomes() && this.settings.randomBiomeIds().contains("cherry_grove")
            && isCherryClimate(climate) && sampleValueNoise(blockX, blockZ, 448, 7999243172794174439L) >= 0.78
            && this.isCherryTerrain(point, blockX, blockZ)) {
            return this.cherryGrove;
         }
         if (this.dappledForest != null && isDappledClimate(climate)
            && sampleValueNoise(blockX, blockZ, 32, 843186567711051837L) * 0.65
               + sampleValueNoise(blockX, blockZ, 11, -6070240617245934207L) * 0.35 >= 0.45) {
            return this.dappledForest;
         }
         if (isFlowerClimate(climate)
            && sampleValueNoise(blockX, blockZ, 320, -3588256909144307243L) >= 0.72) {
            return this.flowerForest;
         }
      } else if ((coverClass == 30 || coverClass == 40) && biome.is(Biomes.PLAINS)
         && isSunflowerClimate(climate)
         && sampleValueNoise(blockX, blockZ, 384, 5012456494189417561L) >= 0.72) {
         return this.sunflowerPlains;
      }
      return biome;
   }

   private Holder<Biome> resolveCaveAwareBiome(Holder<Biome> surfaceBiome,
      SphereWorldModel.SphereBlockAddress address, SphereChunkSampler.SurfaceData surface,
      int quartY, int blockX, int blockZ) {
      if (!this.settings.caveGeneration() || !surface.elevationAvailable()) return surfaceBiome;
      int blockY = QuartPos.toBlock(quartY);
      double topMeters = surface.waterAvailable() ? Math.max(surface.elevationMeters(), surface.waterSurfaceMeters())
         : surface.elevationMeters();
      int surfaceY = (int)Math.round(topMeters / Math.max(0.0001, this.model.config().worldScale()));
      int depth = surfaceY - blockY;
      if (!TellusCaveBiomeDepthPolicy.isCaveBiomeDepth(depth, this.settings.undergroundDepth())) return surfaceBiome;
      if (this.settings.randomBiomes()) {
         Holder<Biome> selected = this.randomBiomeMixer.mixCave(surfaceBiome, blockX, blockY, blockZ);
         if (selected != surfaceBiome) return selected;
      }
      if (this.deepDark != null && this.settings.deepDark()
         && TellusCaveBiomeDepthPolicy.isDeepDarkDepth(depth, this.settings.undergroundDepth())
         && this.caveNoise(address, blockY, 96, 48) < 0.28
            + TellusCaveBiomeDepthPolicy.normalizedDepthFactor(depth, this.settings.undergroundDepth()) * 0.22) {
         return this.deepDark;
      }
      double depthFactor = TellusCaveBiomeDepthPolicy.normalizedDepthFactor(depth, this.settings.undergroundDepth());
      double noise = this.caveNoise(address, blockY, 48, 32);
      double lushChance = (isLushSurface(surfaceBiome) ? 0.45 : 0.25) * (1.0 - depthFactor * 0.35);
      double dripChance = (isDrySurface(surfaceBiome) ? 0.45 : 0.25) * (0.7 + depthFactor * 0.5);
      if (depth < 12) lushChance = 0.0;
      if (depth < 16) dripChance = 0.0;
      double total = lushChance + dripChance;
      if (total > 0.0 && noise <= 0.55) {
         double pick = noise * total / 0.55;
         if (this.lushCaves != null && pick < lushChance) return this.lushCaves;
         if (this.dripstoneCaves != null && pick < lushChance + dripChance) return this.dripstoneCaves;
      }
      return surfaceBiome;
   }

   private Holder<Biome> resolveSnowBiome(SphereWorldModel.GeoPoint point, int blockX, int blockZ) {
      int baseline = SnowBiomePolicy.baselineBlocks(this.model.config().worldScale());
      double center = this.sampleElevation(point);
      double east = this.sampleElevationAtPlanar(blockX + baseline, blockZ);
      double west = this.sampleElevationAtPlanar(blockX - baseline, blockZ);
      double north = this.sampleElevationAtPlanar(blockX, blockZ - baseline);
      double south = this.sampleElevationAtPlanar(blockX, blockZ + baseline);
      double metersX = baseline * EarthProjection.groundMetersPerBlockX(blockZ, this.model.config().worldScale());
      double metersZ = baseline * EarthProjection.groundMetersPerBlockZ(blockZ, this.model.config().worldScale());
      return switch (SnowBiomePolicy.classify(center, east, west, north, south, metersX, metersZ)) {
         case JAGGED_PEAKS -> this.jaggedPeaks;
         case SNOWY_SLOPES -> this.snowySlopes;
         case FROZEN_PEAKS -> this.frozenPeaks;
      };
   }

   private double sampleRegionalRelief(SphereWorldModel.GeoPoint point, int blockX, int blockZ) {
      int offset = Math.max(16, (int)Math.ceil(BadlandsTerrainPolicy.CANYON_RELIEF_SAMPLE_METERS
         / Math.max(1.0, this.model.config().worldScale())));
      double center = this.sampleElevation(point);
      double east = this.sampleElevationAtPlanar(blockX + offset, blockZ);
      double west = this.sampleElevationAtPlanar(blockX - offset, blockZ);
      double north = this.sampleElevationAtPlanar(blockX, blockZ - offset);
      double south = this.sampleElevationAtPlanar(blockX, blockZ + offset);
      double min = center;
      double max = center;
      for (double value : new double[]{east, west, north, south}) {
         if (Double.isFinite(value)) { min = Math.min(min, value); max = Math.max(max, value); }
      }
      return max - min;
   }

   private double sampleElevation(SphereWorldModel.GeoPoint point) {
      return this.sampler.get().samplePoint(point).elevationMeters();
   }

   private double sampleElevationAtPlanar(int blockX, int blockZ) {
      return this.resolveAddress(blockX, blockZ)
         .map(address -> this.sampleElevation(this.model.surfacePoint(address)))
         .orElse(Double.NaN);
   }

   private boolean isAntarcticSnow(int rawCover, SphereWorldModel.GeoPoint point) {
      return rawCover == 0 && point.latitude() <= -60.0;
   }

   private int sampleVisualCover(SphereWorldModel.GeoPoint point, int rawCover, double earthBlockX, double earthBlockZ) {
      if (this.model.config().worldScale() < 10.0 && Double.isFinite(earthBlockX) && Double.isFinite(earthBlockZ)) {
         return TellusWorldgenSources.landCover().sampleVisualCoverClass(
            coordinateForClassification(earthBlockX), coordinateForClassification(earthBlockZ),
            this.model.config().worldScale());
      }
      return rawCover;
   }

   private double caveNoise(SphereWorldModel.SphereBlockAddress address, int blockY, int gridXZ, int gridY) {
      SphereWorldModel.GeoPoint point = this.model.surfacePoint(address);
      int[] coordinates = this.noiseCoordinates(point);
      long seed = Math.floorDiv(coordinates[0], gridXZ) * 341873128712L
         + Math.floorDiv(coordinates[1], gridXZ) * 132897987541L
         + Math.floorDiv(blockY, gridY) * 42317861L;
      return hashToUnit(seed);
   }

   private int[] noiseCoordinates(SphereWorldModel.GeoPoint point) {
      double latitude = Math.toRadians(point.latitude());
      double longitude = Math.toRadians(point.longitude());
      double cos = Math.cos(latitude);
      return new int[]{
         coordinateForClassification(cos * Math.cos(longitude) * 1_000_000.0 + Math.sin(latitude) * 37_000.0),
         coordinateForClassification(cos * Math.sin(longitude) * 1_000_000.0 + Math.sin(latitude) * 91_000.0)
      };
   }

   private static boolean isTemperateForest(Holder<Biome> biome) {
      return biome.is(Biomes.FOREST) || biome.is(Biomes.BIRCH_FOREST)
         || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST) || biome.is(Biomes.DARK_FOREST)
         || biome.is(Biomes.FLOWER_FOREST) || biome.is(Biomes.CHERRY_GROVE);
   }

   private static boolean isLushSurface(Holder<Biome> biome) {
      return biome.is(Biomes.JUNGLE) || biome.is(Biomes.SPARSE_JUNGLE) || biome.is(Biomes.BAMBOO_JUNGLE)
         || biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP) || biome.is(Biomes.DARK_FOREST)
         || biome.is(Biomes.FOREST) || biome.is(Biomes.FLOWER_FOREST) || biome.is(Biomes.CHERRY_GROVE)
         || biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)
         || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
         || biome.is(Biomes.TAIGA);
   }

   private static boolean isDrySurface(Holder<Biome> biome) {
      return biome.is(Biomes.DESERT) || biome.is(Biomes.BADLANDS) || biome.is(Biomes.WOODED_BADLANDS)
         || biome.is(Biomes.ERODED_BADLANDS) || biome.is(Biomes.SAVANNA)
         || biome.is(Biomes.SAVANNA_PLATEAU) || biome.is(Biomes.WINDSWEPT_SAVANNA);
   }

   private static boolean isSunflowerClimate(String code) {
      return switch (code) { case "CFA", "CWA", "DFA", "DWA" -> true; default -> false; };
   }
   private static boolean isFlowerClimate(String code) {
      return switch (code) { case "CFA", "CFB", "CWA", "CWB", "DFA", "DFB", "DWA", "DWB" -> true; default -> false; };
   }
   private static boolean isCherryClimate(String code) {
      return switch (code) { case "CFA", "CFB", "CWA", "CWB", "DFA", "DFB" -> true; default -> false; };
   }
   private static boolean isDappledClimate(String code) {
      return switch (code) { case "DFB", "DWB", "DSB" -> true; default -> false; };
   }

   private boolean isCherryTerrain(SphereWorldModel.GeoPoint point, int blockX, int blockZ) {
      double elevation = this.sampleElevation(point);
      if (!Double.isFinite(elevation) || elevation < 180.0 || elevation > 1800.0) return false;
      return this.sampleRegionalRelief(point, blockX, blockZ) >= 35.0;
   }

   private static double sampleValueNoise(int x, int z, int grid, long salt) {
      int gx = Math.floorDiv(x, grid), gz = Math.floorDiv(z, grid);
      double fx = Math.floorMod(x, grid) / (double)grid, fz = Math.floorMod(z, grid) / (double)grid;
      double sx = fx * fx * (3.0 - 2.0 * fx), sz = fz * fz * (3.0 - 2.0 * fz);
      double n00 = hashToUnit(gx * 341873128712L + gz * 132897987541L + salt);
      double n10 = hashToUnit((gx + 1L) * 341873128712L + gz * 132897987541L + salt);
      double n01 = hashToUnit(gx * 341873128712L + (gz + 1L) * 132897987541L + salt);
      double n11 = hashToUnit((gx + 1L) * 341873128712L + (gz + 1L) * 132897987541L + salt);
      return Mth.lerp(sz, Mth.lerp(sx, n00, n10), Mth.lerp(sx, n01, n11));
   }

   private static double hashToUnit(long seed) {
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      return (seed >>> 11) * 0x1.0p-53;
   }

   private Holder<Biome> resolveBiome(ResourceKey<Biome> key, Holder<Biome> fallback) {
      return key == null ? fallback : this.biomes.get(key).map(holder -> (Holder<Biome>)holder).orElse(fallback);
   }

   private Holder<Biome> resolveOptionalBiome(ResourceKey<Biome> key) {
      return key == null ? null : this.biomes.get(key).map(holder -> (Holder<Biome>)holder).orElse(null);
   }

   private static ClimateData sampleDefaultClimate(SphereWorldModel.GeoPoint point, double worldScale, boolean water) {
      double blockX = EarthProjection.longitudeToBlockX(point.longitude(), worldScale);
      double blockZ = EarthProjection.latToBlockZ(point.latitude(), worldScale);
      return new ClimateData(
         water ? null : TellusWorldgenSources.koppen().sampleDitheredCode(blockX, blockZ, worldScale),
         water ? TellusWorldgenSources.oceanClimate().sample(blockX, blockZ, worldScale) : null
      );
   }

   private static WaterData sampleDefaultInlandWater(SphereWorldModel.GeoPoint point, double worldScale) {
      double earthBlockX = EarthProjection.longitudeToBlockX(point.longitude(), worldScale);
      double earthBlockZ = EarthProjection.latToBlockZ(point.latitude(), worldScale);
      if (!Double.isFinite(earthBlockX) || !Double.isFinite(earthBlockZ)
         || earthBlockX < Integer.MIN_VALUE || earthBlockX > Integer.MAX_VALUE
         || earthBlockZ < Integer.MIN_VALUE || earthBlockZ > Integer.MAX_VALUE) {
         return WaterData.dry();
      }
      TellusOsmWaterSource.FastWaterSample sample = TellusWorldgenSources.osmWater().sampleWater(
         (int)Math.floor(earthBlockX), (int)Math.floor(earthBlockZ), worldScale, OsmQueryMode.NON_BLOCKING);
      return new WaterData(sample.hasWater(), sample.ocean());
   }

   private static boolean isWaterCandidate(int coverClass) {
      return coverClass == 0 || coverClass == 80;
   }

   private static int coordinateForClassification(double coordinate) {
      if (!Double.isFinite(coordinate)) return 0;
      return coordinate <= Integer.MIN_VALUE ? Integer.MIN_VALUE
         : coordinate >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)coordinate;
   }

   private Optional<SphereWorldModel.SphereBlockAddress> resolveAddress(int blockX, int blockZ) {
      double planarX = blockX + 0.5;
      double planarZ = blockZ + 0.5;
      try {
         CubeNetLayout.FacePoint point = this.layout.fromPlanar(new CubeNetLayout.PlanarPoint(planarX, planarZ));
         return Optional.of(new SphereWorldModel.SphereBlockAddress(
            point.face().index(), (long)Math.floor(point.u()), (long)Math.floor(point.v())));
      } catch (IllegalArgumentException ignored) {
         return this.apronResolver.resolve(planarX, planarZ).map(apron ->
            new SphereWorldModel.SphereBlockAddress(apron.targetFace().index(), apron.blockX(), apron.blockZ()));
      }
   }
}
