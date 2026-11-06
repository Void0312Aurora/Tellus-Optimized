package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Generator-independent structure enablement policy.
 *
 * <p>The policy deliberately contains only the path-to-toggle mapping used
 * while constructing a structure registry view. Terrain-aware filtering and
 * post-generation collision handling remain generator responsibilities.</p>
 */
public final class TellusStructurePolicy {
   private final StructureToggles toggles;

   public TellusStructurePolicy(EarthGeneratorSettings settings) {
      this(StructureToggles.from(settings));
   }

   public TellusStructurePolicy(StructureToggles toggles) {
      this.toggles = Objects.requireNonNull(toggles, "toggles");
   }

   public StructureToggles toggles() {
      return this.toggles;
   }

   public boolean enabled(String structurePath) {
      return this.toggles.enabled(structurePath);
   }

   public boolean enabled(Holder<Structure> structure) {
      return this.toggles.enabled(structure);
   }

   public boolean enabled(StructureSet structureSet) {
      return this.toggles.enabled(structureSet);
   }

   public boolean enabledSet(Holder<StructureSet> structureSet) {
      return this.toggles.enabledSet(structureSet);
   }

   /** The immutable switch snapshot used by the pure mapping methods. */
   public record StructureToggles(
      boolean suppressesUndergroundGenerationForTerrainShell,
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
      public static StructureToggles from(EarthGeneratorSettings settings) {
         Objects.requireNonNull(settings, "settings");
         return new StructureToggles(
            settings.suppressesUndergroundGenerationForTerrainShell(),
            settings.addStrongholds(),
            settings.addVillages(),
            settings.addMineshafts(),
            settings.addOceanMonuments(),
            settings.addWoodlandMansions(),
            settings.addDesertTemples(),
            settings.addJungleTemples(),
            settings.addPillagerOutposts(),
            settings.addRuinedPortals(),
            settings.addShipwrecks(),
            settings.addOceanRuins(),
            settings.addBuriedTreasure(),
            settings.addIgloos(),
            settings.addWitchHuts(),
            settings.addAncientCities(),
            settings.addTrialChambers(),
            settings.addTrailRuins(),
            settings.addAbandonedCamps()
         );
      }

      public boolean enabled(String path) {
         if (this.suppressesUndergroundGenerationForTerrainShell && isThinShellUndergroundStructureId(path)) {
            return false;
         } else if (path.startsWith("village")) {
            return this.addVillages;
         } else if (path.equals("stronghold")) {
            return this.addStrongholds;
         } else if (path.startsWith("mineshaft")) {
            return this.addMineshafts;
         } else if (path.equals("igloo")) {
            return this.addIgloos;
         } else if (path.equals("ocean_monument") || path.equals("monument")) {
            return this.addOceanMonuments;
         } else if (VanillaStructurePlacement.isWoodlandMansionPath(path)) {
            return this.addWoodlandMansions;
         } else if (path.equals("desert_pyramid") || path.equals("desert_temple")) {
            return this.addDesertTemples;
         } else if (path.equals("jungle_pyramid") || path.equals("jungle_temple")) {
            return this.addJungleTemples;
         } else if (path.equals("pillager_outpost")) {
            return this.addPillagerOutposts;
         } else if (path.startsWith("ruined_portal")) {
            return this.addRuinedPortals;
         } else if (path.startsWith("shipwreck")) {
            return this.addShipwrecks;
         } else if (path.startsWith("ocean_ruin")) {
            return this.addOceanRuins;
         } else if (path.equals("buried_treasure")) {
            return this.addBuriedTreasure;
         } else if (path.equals("swamp_hut") || path.equals("witch_hut")) {
            return this.addWitchHuts;
         } else if (path.equals("ancient_city")) {
            return this.addAncientCities;
         } else if (path.equals("trial_chambers")) {
            return this.addTrialChambers;
         } else if (VanillaStructurePlacement.isAbandonedCampPath(path)) {
            return this.addAbandonedCamps;
         } else {
            return path.startsWith("trail_ruins") ? this.addTrailRuins : true;
         }
      }

      public boolean enabled(Holder<Structure> structure) {
         return structure.unwrapKey()
            .map(MinecraftVersionCompat::keyPath)
            .map(this::enabled)
            .orElse(true);
      }

      public boolean enabled(StructureSet structureSet) {
         for (StructureSet.StructureSelectionEntry entry : structureSet.structures()) {
            if (!this.enabled(entry.structure())) {
               return false;
            }
         }
         return true;
      }

      public boolean enabledSet(Holder<StructureSet> structureSet) {
         return this.enabled(structureSet.value());
      }

      private static boolean isThinShellUndergroundStructureId(String path) {
         return path.equals("stronghold")
            || path.startsWith("mineshaft")
            || path.equals("ancient_city")
            || path.equals("trial_chambers")
            || path.equals("buried_treasure");
      }
   }
}
