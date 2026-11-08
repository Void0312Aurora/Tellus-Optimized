package com.yucareux.tellus.worldgen.runtime;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, read-only projection of a Tellus generator's effective settings.
 *
 * <p>This is deliberately a view rather than a second codec or a mutable
 * configuration object. Runtime overrides (commands, DH API values and Voxy
 * overrides) belong to a runtime adapter and are not represented here.</p>
 */
public interface TellusGeneratorSettingsView {
   enum Kind { EARTH, CUBE_NET, SPHERE_FACE }

   enum Capability {
      GEODETIC_POSITION,
      REALTIME_TIME,
      REALTIME_WEATHER,
      HISTORICAL_SNOW,
      DISTANT_HORIZONS_LOD,
      VOXY_PREGEN,
      MANAGED_TERRAIN_DOWNLOADS,
      SEAM_NAVIGATION,
      FACE_ROUTING,
      EXPERIMENTAL_HEIGHT
   }

   record VerticalRange(int minY, int height, int logicalHeight) {
      public VerticalRange {
         if (height <= 0 || logicalHeight <= 0) {
            throw new IllegalArgumentException("height and logicalHeight must be positive");
         }
      }

      public int maxYExclusive() {
         return Math.addExact(this.minY, this.height);
      }
   }

   record SpawnPoint(double latitude, double longitude) {
      public SpawnPoint {
         if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude");
         }
         if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude");
         }
      }
   }

   record TerrainOptions(
      boolean caveGeneration,
      boolean oreGeneration,
      boolean geologicalStonePatches,
      boolean lavaPools,
      boolean geodes,
      boolean deepDark,
      boolean water,
      boolean roads,
      boolean buildings,
      boolean cavesReachSurface,
      int undergroundDepth,
      boolean thinShellTerrain,
      boolean climateBasedBuiltUpTerrain,
      boolean lakeFreezing,
      boolean customTrees,
      boolean hugeRedMushrooms
   ) { }

   /** Structure names are stable IDs, not Java enum ordinals. */
   record StructureOptions(Set<String> enabledIds) {
      public StructureOptions {
         enabledIds = Set.copyOf(enabledIds);
      }

      public boolean enabled(String id) {
         return this.enabledIds.contains(id);
      }
   }

   record RandomBiomeOptions(boolean enabled, double density, long seed, List<String> biomeIds) {
      public RandomBiomeOptions {
         biomeIds = List.copyOf(biomeIds);
      }
   }

   record RealtimeOptions(boolean time, boolean weather, boolean historicalSnow) { }

   record DistantHorizonsOptions(
      String renderMode,
      int cityLightsDistanceChunks,
      boolean osmFeatures,
      int osmRoadMaxDetail,
      int osmBuildingMaxDetail,
      boolean osmNonBlockingFetch
   ) { }

   record VoxyOptions(boolean enabled, int maxRadiusChunks, int chunksPerTick) { }

   record ManagedTerrainOptions(boolean enabled, boolean showOverlay) { }

   record ExperimentalHeightOptions(boolean enabled, boolean automaticScaling) { }

   String generatorId();

   Kind kind();

   /** Stable persisted mapping/projection identity. */
   String geometryId();

   /** Real-world metres represented by one horizontal block. */
   double worldScale();

   /** Effective, section-aligned generator range. */
   VerticalRange verticalRange();

   /** Effective sea level used by terrain materialization. */
   int seaLevel();

   /** Configured vertical origin before generator-specific sea-level policy. */
   int heightOffset();

   Optional<SpawnPoint> spawn();

   Optional<TerrainOptions> terrain();

   Optional<StructureOptions> structures();

   Optional<RandomBiomeOptions> randomBiomes();

   /** Configured values only; command/API overrides remain runtime state. */
   Optional<RealtimeOptions> realtime();

   Optional<DistantHorizonsOptions> distantHorizons();

   Optional<VoxyOptions> voxy();

   Optional<ManagedTerrainOptions> managedTerrain();

   Optional<ExperimentalHeightOptions> experimentalHeight();

   Set<Capability> capabilities();

   default boolean supports(Capability capability) {
      return this.capabilities().contains(java.util.Objects.requireNonNull(capability, "capability"));
   }
}
