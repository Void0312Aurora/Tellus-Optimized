package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.CubeNetBiomeSource;
import com.yucareux.tellus.worldgen.CubeNetChunkGenerator;
import com.yucareux.tellus.worldgen.CubeNetGeneratorSettingsViewFactory;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 1.21.1 typed Cube-Net settings projection. This layer intentionally keeps
 * the same view contract while following the post-1.20.1 generator API.
 */
public final class CubeNetGeneratorSettingsViewAdapter implements TellusGeneratorSettingsView {
   private static final String SOURCE_LAYER = "mc1211";
   private final CubeNetChunkGenerator generator;
   private final CubeNetBiomeSource.Settings settings;
   private final VerticalRange verticalRange;
   private final Set<Capability> capabilities;

   public CubeNetGeneratorSettingsViewAdapter(CubeNetChunkGenerator generator) {
      this(generator, CubeNetGeneratorSettingsViewFactory.settingsFor(generator));
   }

   public CubeNetGeneratorSettingsViewAdapter(
      CubeNetChunkGenerator generator, CubeNetBiomeSource.Settings settings
   ) {
      this.generator = java.util.Objects.requireNonNull(generator, "generator");
      this.settings = CubeNetGeneratorSettingsViewFactory.requireMatchingSettings(generator, settings);
      this.verticalRange = new VerticalRange(generator.getMinY(), generator.getGenDepth(), generator.getGenDepth());
      this.capabilities = Set.copyOf(EnumSet.of(Capability.GEODETIC_POSITION, Capability.SEAM_NAVIGATION));
   }

   public String sourceLayer() { return SOURCE_LAYER; }
   public CubeNetChunkGenerator generator() { return this.generator; }
   public CubeNetBiomeSource.Settings sourceSettings() { return this.settings; }

   @Override public String generatorId() { return "tellus:cube_net"; }
   @Override public Kind kind() { return Kind.CUBE_NET; }
   @Override public String geometryId() { return this.generator.model().mappingId(); }
   @Override public double worldScale() { return this.generator.worldScale(); }
   @Override public VerticalRange verticalRange() { return this.verticalRange; }
   @Override public int seaLevel() { return this.generator.seaLevel(); }
   @Override public int heightOffset() { return this.generator.heightOffset(); }

   @Override
   public Optional<SpawnPoint> spawn() {
      return Optional.of(new SpawnPoint(this.settings.spawnLatitude(), this.settings.spawnLongitude()));
   }

   @Override
   public Optional<TerrainOptions> terrain() {
      return Optional.of(new TerrainOptions(
         this.settings.caveGeneration(), this.settings.oreVeins(), this.settings.geologicalStonePatches(),
         this.settings.lavaPools(), this.settings.geodes(), this.settings.deepDark(), this.settings.enableWater(),
         this.settings.roads(), this.settings.buildings(), this.settings.cavesReachSurface(), this.settings.undergroundDepth(),
         false, false, this.settings.lakeFreezing(), false, false
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
      return Optional.of(new RandomBiomeOptions(this.settings.randomBiomes(), this.settings.randomBiomeDensity(),
         this.settings.randomBiomeSeed(), this.settings.randomBiomeIds()));
   }

   @Override public Optional<RealtimeOptions> realtime() { return Optional.empty(); }
   @Override public Optional<DistantHorizonsOptions> distantHorizons() { return Optional.empty(); }
   @Override public Optional<VoxyOptions> voxy() { return Optional.empty(); }
   @Override public Optional<ManagedTerrainOptions> managedTerrain() { return Optional.empty(); }
   @Override public Optional<ExperimentalHeightOptions> experimentalHeight() { return Optional.empty(); }
   @Override public Set<Capability> capabilities() { return this.capabilities; }
}
