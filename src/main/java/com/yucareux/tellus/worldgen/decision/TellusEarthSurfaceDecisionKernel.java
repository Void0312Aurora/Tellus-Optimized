package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import java.util.Objects;

/**
 * Minecraft-free counterpart of the native Earth surface palette selection.
 *
 * <p>The kernel consumes values already resolved by the caller. It deliberately
 * does not read source data, consult registries, derive heights, or sample
 * noise; the native integration layer remains responsible for those concerns.</p>
 */
public final class TellusEarthSurfaceDecisionKernel {
    private TellusEarthSurfaceDecisionKernel() {
    }

    /** The native palette branch selected for an Earth surface column. */
    public enum PaletteKind {
        OCEAN_FLOOR,
        COVER,
        COVER_WITH_GRASS_GRAVEL_CHECK,
        MUSHROOM_FIELDS,
        BEACH,
        STRATA,
        BADLANDS,
        DESERT,
        MANGROVE,
        SWAMP,
        GRAVELLY,
        DEFAULT_OVERWORLD,
        STEPPE,
        PODZOLIC,
        ROOTED,
        STONY_PEAKS,
        SNOWY,
        WETLAND,
        MOSSY
    }

    /** Inputs corresponding to the already-resolved native biome and cover predicates. */
    public record BaseInput(
        int coverClass,
        boolean randomLandPatch,
        boolean ocean,
        boolean river,
        boolean mushroomFields,
        boolean beach,
        boolean stonyShore,
        boolean badlands,
        boolean desert,
        boolean mangrove,
        boolean swamp,
        boolean stonyPeaks,
        boolean gravellyHills
    ) {
    }

    /** Selects the native base palette branch, preserving native condition order. */
    public static PaletteKind selectBase(BaseInput input) {
        Objects.requireNonNull(input, "input");

        if (input.ocean()
            || input.river() && MountainSurfaceRules.isWaterLikeCoverClass(input.coverClass())) {
            return PaletteKind.OCEAN_FLOOR;
        } else if (!input.randomLandPatch()
            && input.coverClass() == MountainSurfaceRules.ESA_TREE_COVER) {
            return PaletteKind.COVER;
        } else if (input.mushroomFields()) {
            return PaletteKind.MUSHROOM_FIELDS;
        } else if (input.beach()) {
            return PaletteKind.BEACH;
        } else if (input.stonyShore()) {
            return PaletteKind.STRATA;
        } else if (input.badlands()) {
            return PaletteKind.BADLANDS;
        } else if (input.desert()) {
            return PaletteKind.DESERT;
        } else if (input.mangrove()) {
            return PaletteKind.MANGROVE;
        } else if (input.swamp()) {
            return PaletteKind.SWAMP;
        } else if (input.stonyPeaks()) {
            return PaletteKind.STRATA;
        } else if (input.gravellyHills()) {
            return PaletteKind.GRAVELLY;
        }
        return PaletteKind.COVER_WITH_GRASS_GRAVEL_CHECK;
    }

    /**
     * Selects the exact native cover-driven palette branch for a supplied
     * 0..99 surface roll and precomputed zone height.
     */
    public static PaletteKind cover(int coverClass, byte climateGroup, int roll, int zoneHeight) {
        requireRoll(roll);
        boolean alpine = zoneHeight >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA;
        boolean cold = climateGroup == 4 || climateGroup == 5;

        return switch (coverClass) {
            case MountainSurfaceRules.ESA_TREE_COVER -> climateGroup != 4 && climateGroup != 5
                ? climateGroup == 2
                    ? roll < 70 ? PaletteKind.STEPPE : PaletteKind.DEFAULT_OVERWORLD
                    : climateGroup == 1
                        ? roll < 35 ? PaletteKind.ROOTED : PaletteKind.DEFAULT_OVERWORLD
                        : roll < 20 ? PaletteKind.ROOTED : PaletteKind.DEFAULT_OVERWORLD
                : roll < 65 ? PaletteKind.PODZOLIC : PaletteKind.DEFAULT_OVERWORLD;
            case MountainSurfaceRules.ESA_SHRUBLAND -> alpine && roll < 70
                ? PaletteKind.GRAVELLY
                : climateGroup == 2
                    ? PaletteKind.STEPPE
                    : !cold
                        ? roll < 40 ? PaletteKind.STEPPE : PaletteKind.DEFAULT_OVERWORLD
                        : PaletteKind.PODZOLIC;
            case MountainSurfaceRules.ESA_GRASSLAND -> alpine && roll < 35
                ? PaletteKind.GRAVELLY
                : climateGroup == 2
                    ? roll < 65 ? PaletteKind.STEPPE : PaletteKind.DEFAULT_OVERWORLD
                    : !cold
                        ? PaletteKind.DEFAULT_OVERWORLD
                        : roll < 40 ? PaletteKind.PODZOLIC : PaletteKind.DEFAULT_OVERWORLD;
            case MountainSurfaceRules.ESA_CROPLAND -> climateGroup == 2
                ? PaletteKind.STEPPE
                : roll < 65 ? PaletteKind.ROOTED : PaletteKind.DEFAULT_OVERWORLD;
            case MountainSurfaceRules.ESA_BUILT -> roll < 45
                ? PaletteKind.GRAVELLY
                : PaletteKind.STONY_PEAKS;
            case MountainSurfaceRules.ESA_BARE -> alpine
                ? PaletteKind.STRATA
                : climateGroup == 2
                    ? roll < 55 ? PaletteKind.DESERT : PaletteKind.BADLANDS
                    : !cold
                        ? PaletteKind.GRAVELLY
                        : roll < 70 ? PaletteKind.GRAVELLY : PaletteKind.STRATA;
            case MountainSurfaceRules.ESA_SNOW_ICE -> PaletteKind.SNOWY;
            case MountainSurfaceRules.ESA_WETLAND -> roll < 40
                ? PaletteKind.SWAMP
                : PaletteKind.WETLAND;
            case MountainSurfaceRules.ESA_MANGROVES -> PaletteKind.MANGROVE;
            case MountainSurfaceRules.ESA_MOSS_LICHEN -> alpine && roll < 60
                ? PaletteKind.STRATA
                : roll < 35 ? PaletteKind.MOSSY : PaletteKind.DEFAULT_OVERWORLD;
            default -> !cold ? PaletteKind.DEFAULT_OVERWORLD : PaletteKind.PODZOLIC;
        };
    }

    /** Selects the native ocean-floor material from a supplied 0..99 roll. */
    public static SemanticMaterial oceanFloor(int roll) {
        requireRoll(roll);
        if (roll < 10) {
            return SemanticMaterial.GRAVEL;
        }
        return roll < 15 ? SemanticMaterial.CLAY : SemanticMaterial.SAND;
    }

    private static void requireRoll(int roll) {
        if (roll < 0 || roll > 99) {
            throw new IllegalArgumentException("roll must be in [0, 99]: " + roll);
        }
    }
}
