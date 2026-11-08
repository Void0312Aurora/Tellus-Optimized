package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Read-only Phase 1 view over the existing Earth settings and generator. */
public final class EarthGeneratorSettingsViewAdapter implements TellusGeneratorSettingsView {
   private static final String GENERATOR_ID = "tellus:earth";
   private final EarthGeneratorSettings settings;
   private final VerticalRange verticalRange;
   private final int seaLevel;
   private final Set<Capability> capabilities;

   public EarthGeneratorSettingsViewAdapter(EarthChunkGenerator generator) {
      this(generator.settings(), generator.seaLevel(), generator.getMinY(), generator.getGenDepth());
   }

   public EarthGeneratorSettingsViewAdapter(EarthGeneratorSettings settings) {
      this(settings, settings.effectiveHeightOffset(), resolveLimits(settings).minY(), resolveLimits(settings).height());
   }

   private EarthGeneratorSettingsViewAdapter(EarthGeneratorSettings settings, int seaLevel, int minY, int height) {
      this.settings = java.util.Objects.requireNonNull(settings, "settings");
      this.seaLevel = seaLevel;
      this.verticalRange = new VerticalRange(minY, height, height);
      EnumSet<Capability> values = EnumSet.of(
         Capability.GEODETIC_POSITION,
         Capability.REALTIME_TIME,
         Capability.REALTIME_WEATHER,
         Capability.HISTORICAL_SNOW,
         Capability.DISTANT_HORIZONS_LOD,
         Capability.VOXY_PREGEN,
         Capability.MANAGED_TERRAIN_DOWNLOADS
      );
      if (settings.experimentalIncreaseHeight()) {
         values.add(Capability.EXPERIMENTAL_HEIGHT);
      }
      this.capabilities = Set.copyOf(values);
   }

   private static EarthGeneratorSettings.HeightLimits resolveLimits(EarthGeneratorSettings settings) {
      return EarthGeneratorSettings.resolveHeightLimits(settings);
   }

   public EarthGeneratorSettings sourceSettings() {
      return this.settings;
   }

   @Override
   public String generatorId() {
      return GENERATOR_ID;
   }

   @Override
   public Kind kind() {
      return Kind.EARTH;
   }

   @Override
   public String geometryId() {
      return "earth:" + EarthProjection.projectionModeId();
   }

   @Override
   public double worldScale() {
      return this.settings.worldScale();
   }

   @Override
   public VerticalRange verticalRange() {
      return this.verticalRange;
   }

   @Override
   public int seaLevel() {
      return this.seaLevel;
   }

   @Override
   public int heightOffset() {
      return this.settings.heightOffset();
   }

   @Override
   public Optional<SpawnPoint> spawn() {
      return Optional.of(new SpawnPoint(this.settings.spawnLatitude(), this.settings.spawnLongitude()));
   }

   @Override
   public Optional<TerrainOptions> terrain() {
      return Optional.of(new TerrainOptions(
         this.settings.caveGeneration(),
         this.settings.oreDistribution(),
         this.settings.geologicalStonePatches(),
         this.settings.lavaPools(),
         this.settings.geodes(),
         this.settings.deepDark(),
         this.settings.enableWater(),
         this.settings.enableRoads(),
         this.settings.enableBuildings(),
         this.settings.cavesReachSurface(),
         this.settings.undergroundDepth(),
         this.settings.thinShellTerrain(),
         this.settings.climateBasedBuiltUpTerrain(),
         this.settings.lakeFreezing(),
         this.settings.customTrees(),
         this.settings.hugeRedMushrooms()
      ));
   }

   @Override
   public Optional<StructureOptions> structures() {
      LinkedHashSet<String> enabled = new LinkedHashSet<>();
      add(enabled, "minecraft:stronghold", this.settings.addStrongholds());
      add(enabled, "minecraft:village", this.settings.addVillages());
      add(enabled, "minecraft:mineshaft", this.settings.addMineshafts());
      add(enabled, "minecraft:ocean_monument", this.settings.addOceanMonuments());
      add(enabled, "minecraft:woodland_mansion", this.settings.addWoodlandMansions());
      add(enabled, "minecraft:desert_pyramid", this.settings.addDesertTemples());
      add(enabled, "minecraft:jungle_temple", this.settings.addJungleTemples());
      add(enabled, "minecraft:pillager_outpost", this.settings.addPillagerOutposts());
      add(enabled, "minecraft:ruined_portal", this.settings.addRuinedPortals());
      add(enabled, "minecraft:shipwreck", this.settings.addShipwrecks());
      add(enabled, "minecraft:ocean_ruin", this.settings.addOceanRuins());
      add(enabled, "minecraft:buried_treasure", this.settings.addBuriedTreasure());
      add(enabled, "minecraft:igloo", this.settings.addIgloos());
      add(enabled, "minecraft:swamp_hut", this.settings.addWitchHuts());
      add(enabled, "minecraft:ancient_city", this.settings.addAncientCities());
      add(enabled, "minecraft:trial_chambers", this.settings.addTrialChambers());
      add(enabled, "minecraft:trail_ruins", this.settings.addTrailRuins());
      add(enabled, "tellus:abandoned_camp", this.settings.addAbandonedCamps());
      return Optional.of(new StructureOptions(enabled));
   }

   private static void add(Set<String> values, String id, boolean enabled) {
      if (enabled) values.add(id);
   }

   @Override
   public Optional<RandomBiomeOptions> randomBiomes() {
      return Optional.of(new RandomBiomeOptions(
         this.settings.randomBiomes(),
         this.settings.randomBiomeDensity(),
         this.settings.randomBiomeSeed(),
         this.settings.randomBiomeIds()
      ));
   }

   @Override
   public Optional<RealtimeOptions> realtime() {
      return Optional.of(new RealtimeOptions(
         this.settings.realtimeTime(), this.settings.realtimeWeather(), this.settings.historicalSnow()
      ));
   }

   @Override
   public Optional<DistantHorizonsOptions> distantHorizons() {
      return Optional.of(new DistantHorizonsOptions(
         this.settings.distantHorizonsRenderMode().id(),
         this.settings.distantHorizonsCityLightsDistance(),
         this.settings.distantHorizonsOsmFeatures(),
         this.settings.distantHorizonsOsmRoadMaxDetail(),
         this.settings.distantHorizonsOsmBuildingMaxDetail(),
         this.settings.distantHorizonsOsmNonBlockingFetch()
      ));
   }

   @Override
   public Optional<VoxyOptions> voxy() {
      return Optional.of(new VoxyOptions(
         this.settings.voxyChunkPregenEnabled(),
         this.settings.voxyChunkPregenMaxRadius(),
         this.settings.voxyChunkPregenChunksPerTick()
      ));
   }

   @Override
   public Optional<ManagedTerrainOptions> managedTerrain() {
      return Optional.of(new ManagedTerrainOptions(
         this.settings.tellusManagedTerrainDownloads(), this.settings.showTerrainDownloadOverlay()
      ));
   }

   @Override
   public Optional<ExperimentalHeightOptions> experimentalHeight() {
      return Optional.of(new ExperimentalHeightOptions(
         this.settings.experimentalIncreaseHeight(), this.settings.automaticHeightScaling()
      ));
   }

   @Override
   public Set<Capability> capabilities() {
      return this.capabilities;
   }
}
