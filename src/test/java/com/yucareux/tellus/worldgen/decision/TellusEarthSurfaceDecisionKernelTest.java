package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import org.junit.jupiter.api.Test;

/** Literal boundary and precedence coverage for the Minecraft-free Earth surface kernel. */
class TellusEarthSurfaceDecisionKernelTest {
    private static final int LOW = MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA - 1;
    private static final int ALPINE = MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA;

    @Test
    void selectBaseRejectsNullAndPreservesNativePrecedence() {
        assertThrows(NullPointerException.class, () -> TellusEarthSurfaceDecisionKernel.selectBase(null));

        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.OCEAN_FLOOR,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, true, true, true, true, true, true, true, true, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.OCEAN_FLOOR,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_WATER,
                false, false, true, true, true, true, true, true, true, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.OCEAN_FLOOR,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_MANGROVES,
                false, false, true, true, true, true, true, true, true, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.OCEAN_FLOOR,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_NO_DATA,
                false, false, true, true, true, true, true, true, true, true, true, true)));

        // Forest cover wins over every specialized biome branch when no random patch is active.
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.COVER,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_TREE_COVER,
                false, false, false, true, true, true, true, true, true, true, true, true)));
        // A random land patch bypasses that forest-cover branch and continues in native order.
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.MUSHROOM_FIELDS,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_TREE_COVER,
                true, false, false, true, true, true, true, true, true, true, true, true)));

        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.MUSHROOM_FIELDS,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, true, true, true, true, true, true, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.BEACH,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, false, true, true, true, true, true, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.STRATA,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, false, false, true, true, true, true, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.BADLANDS,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, false, false, false, true, true, true, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.DESERT,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, false, false, false, false, true, true, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.MANGROVE,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, false, false, false, false, false, true, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.SWAMP,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, false, false, false, false, false, false, true, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.STRATA,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, false, false, false, false, false, false, false, true, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, false, false, false, false, false, false, false, false, true)));
        assertEquals(TellusEarthSurfaceDecisionKernel.PaletteKind.COVER_WITH_GRASS_GRAVEL_CHECK,
            TellusEarthSurfaceDecisionKernel.selectBase(input(MountainSurfaceRules.ESA_GRASSLAND,
                false, false, false, false, false, false, false, false, false, false, false, false)));
    }

    @Test
    void oceanFloorUsesLiteralNativeBoundariesAndValidatesRoll() {
        assertEquals(SemanticMaterial.GRAVEL, TellusEarthSurfaceDecisionKernel.oceanFloor(0));
        assertEquals(SemanticMaterial.GRAVEL, TellusEarthSurfaceDecisionKernel.oceanFloor(9));
        assertEquals(SemanticMaterial.CLAY, TellusEarthSurfaceDecisionKernel.oceanFloor(10));
        assertEquals(SemanticMaterial.CLAY, TellusEarthSurfaceDecisionKernel.oceanFloor(14));
        assertEquals(SemanticMaterial.SAND, TellusEarthSurfaceDecisionKernel.oceanFloor(15));
        assertEquals(SemanticMaterial.SAND, TellusEarthSurfaceDecisionKernel.oceanFloor(99));
        assertThrows(IllegalArgumentException.class, () -> TellusEarthSurfaceDecisionKernel.oceanFloor(-1));
        assertThrows(IllegalArgumentException.class, () -> TellusEarthSurfaceDecisionKernel.oceanFloor(100));
    }

    @Test
    void treeCoverMatrixUsesClimateSpecificThresholds() {
        for (int climate = 0; climate <= 5; climate++) {
            if (climate == 2) {
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 0, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE);
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 69, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE);
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 70, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else if (climate == 1) {
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 0, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.ROOTED);
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 34, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.ROOTED);
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 35, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else if (climate == 4 || climate == 5) {
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 0, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC);
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 64, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC);
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 65, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else {
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 0, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.ROOTED);
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 19, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.ROOTED);
                assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 20, LOW,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            }
            assertCover(MountainSurfaceRules.ESA_TREE_COVER, climate, 99, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
        }
    }

    @Test
    void shrublandAndGrasslandCoverMatrixChecksAlpineBoundary() {
        for (int climate = 0; climate <= 5; climate++) {
            assertCover(MountainSurfaceRules.ESA_SHRUBLAND, climate, 69, LOW,
                climate == 2 ? TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE
                    : climate == 4 || climate == 5
                        ? TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC
                        : TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            assertCover(MountainSurfaceRules.ESA_SHRUBLAND, climate, 70, LOW,
                climate == 2 ? TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE
                    : climate == 4 || climate == 5
                        ? TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC
                        : TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            assertCover(MountainSurfaceRules.ESA_SHRUBLAND, climate, 0, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            assertCover(MountainSurfaceRules.ESA_SHRUBLAND, climate, 69, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            assertCover(MountainSurfaceRules.ESA_SHRUBLAND, climate, 70, ALPINE,
                climate == 2 ? TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE
                    : climate == 4 || climate == 5
                        ? TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC
                        : TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);

            assertCover(MountainSurfaceRules.ESA_GRASSLAND, climate, 34, LOW,
                climate == 2 ? TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE
                    : climate == 4 || climate == 5
                        ? TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC
                        : TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            assertCover(MountainSurfaceRules.ESA_GRASSLAND, climate, 35, LOW,
                climate == 2 ? TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE
                    : climate == 4 || climate == 5
                        ? TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC
                        : TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            assertCover(MountainSurfaceRules.ESA_GRASSLAND, climate, 0, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            assertCover(MountainSurfaceRules.ESA_GRASSLAND, climate, 34, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            assertCover(MountainSurfaceRules.ESA_GRASSLAND, climate, 35, ALPINE,
                climate == 2 ? TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE
                    : climate == 4 || climate == 5
                        ? TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC
                        : TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
        }
    }

    @Test
    void remainingCoverClassesUseExactClimateAndRollBoundaries() {
        for (int climate = 0; climate <= 5; climate++) {
            assertCover(MountainSurfaceRules.ESA_CROPLAND, climate, 0, LOW,
                climate == 2 ? TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE
                    : TellusEarthSurfaceDecisionKernel.PaletteKind.ROOTED);
            assertCover(MountainSurfaceRules.ESA_CROPLAND, climate, 64, LOW,
                climate == 2 ? TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE
                    : TellusEarthSurfaceDecisionKernel.PaletteKind.ROOTED);
            assertCover(MountainSurfaceRules.ESA_CROPLAND, climate, 65, LOW,
                climate == 2 ? TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE
                    : TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);

            assertCover(MountainSurfaceRules.ESA_BUILT, climate, 44, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            assertCover(MountainSurfaceRules.ESA_BUILT, climate, 45, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.STONY_PEAKS);

            assertCover(MountainSurfaceRules.ESA_SNOW_ICE, climate, 0, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.SNOWY);
            assertCover(MountainSurfaceRules.ESA_SNOW_ICE, climate, 99, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.SNOWY);

            assertCover(MountainSurfaceRules.ESA_WETLAND, climate, 39, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.SWAMP);
            assertCover(MountainSurfaceRules.ESA_WETLAND, climate, 40, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.WETLAND);
            assertCover(MountainSurfaceRules.ESA_MANGROVES, climate, 0, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.MANGROVE);
            assertCover(MountainSurfaceRules.ESA_MANGROVES, climate, 99, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.MANGROVE);
        }

        for (int climate : new int[]{0, 1, 3}) {
            assertCover(MountainSurfaceRules.ESA_BARE, climate, 0, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            assertCover(MountainSurfaceRules.ESA_BARE, climate, 99, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
        }
        for (int climate : new int[]{4, 5}) {
            assertCover(MountainSurfaceRules.ESA_BARE, climate, 69, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            assertCover(MountainSurfaceRules.ESA_BARE, climate, 70, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.STRATA);
        }
        assertCover(MountainSurfaceRules.ESA_BARE, 2, 54, LOW,
            TellusEarthSurfaceDecisionKernel.PaletteKind.DESERT);
        assertCover(MountainSurfaceRules.ESA_BARE, 2, 55, LOW,
            TellusEarthSurfaceDecisionKernel.PaletteKind.BADLANDS);
        for (int climate = 0; climate <= 5; climate++) {
            assertCover(MountainSurfaceRules.ESA_BARE, climate, 0, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.STRATA);
            assertCover(MountainSurfaceRules.ESA_BARE, climate, 99, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.STRATA);
        }

        for (int climate = 0; climate <= 5; climate++) {
            assertCover(MountainSurfaceRules.ESA_MOSS_LICHEN, climate, 34, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.MOSSY);
            assertCover(MountainSurfaceRules.ESA_MOSS_LICHEN, climate, 35, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            assertCover(MountainSurfaceRules.ESA_MOSS_LICHEN, climate, 59, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.STRATA);
            assertCover(MountainSurfaceRules.ESA_MOSS_LICHEN, climate, 60, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
        }
    }

    @Test
    void everyRollInEveryClimateAndAlpineZoneMatchesTheNativeMatrix() {
        for (int climate = 0; climate <= 5; climate++) {
            if (climate == 2) {
                assertRange(MountainSurfaceRules.ESA_TREE_COVER, climate, LOW, 0, 69,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE);
                assertRange(MountainSurfaceRules.ESA_TREE_COVER, climate, LOW, 70, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else if (climate == 1) {
                assertRange(MountainSurfaceRules.ESA_TREE_COVER, climate, LOW, 0, 34,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.ROOTED);
                assertRange(MountainSurfaceRules.ESA_TREE_COVER, climate, LOW, 35, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else if (climate == 4 || climate == 5) {
                assertRange(MountainSurfaceRules.ESA_TREE_COVER, climate, LOW, 0, 64,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC);
                assertRange(MountainSurfaceRules.ESA_TREE_COVER, climate, LOW, 65, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else {
                assertRange(MountainSurfaceRules.ESA_TREE_COVER, climate, LOW, 0, 19,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.ROOTED);
                assertRange(MountainSurfaceRules.ESA_TREE_COVER, climate, LOW, 20, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            }

            assertRange(MountainSurfaceRules.ESA_SHRUBLAND, climate, ALPINE, 0, 69,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            if (climate == 2) {
                assertRange(MountainSurfaceRules.ESA_SHRUBLAND, climate, ALPINE, 70, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE);
            } else if (climate == 4 || climate == 5) {
                assertRange(MountainSurfaceRules.ESA_SHRUBLAND, climate, ALPINE, 70, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC);
            } else {
                assertRange(MountainSurfaceRules.ESA_SHRUBLAND, climate, ALPINE, 70, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            }
            if (climate == 2) {
                assertRange(MountainSurfaceRules.ESA_SHRUBLAND, climate, LOW, 0, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE);
            } else if (climate == 4 || climate == 5) {
                assertRange(MountainSurfaceRules.ESA_SHRUBLAND, climate, LOW, 0, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC);
            } else {
                assertRange(MountainSurfaceRules.ESA_SHRUBLAND, climate, LOW, 0, 39,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE);
                assertRange(MountainSurfaceRules.ESA_SHRUBLAND, climate, LOW, 40, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            }

            assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, ALPINE, 0, 34,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            if (climate == 2) {
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, ALPINE, 35, 64,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE);
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, ALPINE, 65, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else if (climate == 4 || climate == 5) {
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, ALPINE, 35, 39,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC);
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, ALPINE, 40, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else {
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, ALPINE, 35, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            }
            if (climate == 2) {
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, LOW, 0, 64,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE);
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, LOW, 65, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else if (climate == 4 || climate == 5) {
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, LOW, 0, 39,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC);
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, LOW, 40, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            } else {
                assertRange(MountainSurfaceRules.ESA_GRASSLAND, climate, LOW, 0, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            }

            if (climate == 2) {
                assertRange(MountainSurfaceRules.ESA_CROPLAND, climate, LOW, 0, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.STEPPE);
            } else {
                assertRange(MountainSurfaceRules.ESA_CROPLAND, climate, LOW, 0, 64,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.ROOTED);
                assertRange(MountainSurfaceRules.ESA_CROPLAND, climate, LOW, 65, 99,
                    TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            }
            assertRange(MountainSurfaceRules.ESA_BUILT, climate, LOW, 0, 44,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            assertRange(MountainSurfaceRules.ESA_BUILT, climate, LOW, 45, 99,
                TellusEarthSurfaceDecisionKernel.PaletteKind.STONY_PEAKS);
            assertRange(MountainSurfaceRules.ESA_SNOW_ICE, climate, LOW, 0, 99,
                TellusEarthSurfaceDecisionKernel.PaletteKind.SNOWY);
            assertRange(MountainSurfaceRules.ESA_WETLAND, climate, LOW, 0, 39,
                TellusEarthSurfaceDecisionKernel.PaletteKind.SWAMP);
            assertRange(MountainSurfaceRules.ESA_WETLAND, climate, LOW, 40, 99,
                TellusEarthSurfaceDecisionKernel.PaletteKind.WETLAND);
            assertRange(MountainSurfaceRules.ESA_MANGROVES, climate, LOW, 0, 99,
                TellusEarthSurfaceDecisionKernel.PaletteKind.MANGROVE);
            assertRange(MountainSurfaceRules.ESA_MOSS_LICHEN, climate, LOW, 0, 34,
                TellusEarthSurfaceDecisionKernel.PaletteKind.MOSSY);
            assertRange(MountainSurfaceRules.ESA_MOSS_LICHEN, climate, LOW, 35, 99,
                TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            assertRange(MountainSurfaceRules.ESA_MOSS_LICHEN, climate, ALPINE, 0, 59,
                TellusEarthSurfaceDecisionKernel.PaletteKind.STRATA);
            assertRange(MountainSurfaceRules.ESA_MOSS_LICHEN, climate, ALPINE, 60, 99,
                TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
        }

        for (int climate : new int[]{0, 1, 3}) {
            assertRange(MountainSurfaceRules.ESA_BARE, climate, LOW, 0, 99,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
        }
        assertRange(MountainSurfaceRules.ESA_BARE, 2, LOW, 0, 54,
            TellusEarthSurfaceDecisionKernel.PaletteKind.DESERT);
        assertRange(MountainSurfaceRules.ESA_BARE, 2, LOW, 55, 99,
            TellusEarthSurfaceDecisionKernel.PaletteKind.BADLANDS);
        for (int climate : new int[]{4, 5}) {
            assertRange(MountainSurfaceRules.ESA_BARE, climate, LOW, 0, 69,
                TellusEarthSurfaceDecisionKernel.PaletteKind.GRAVELLY);
            assertRange(MountainSurfaceRules.ESA_BARE, climate, LOW, 70, 99,
                TellusEarthSurfaceDecisionKernel.PaletteKind.STRATA);
        }
        for (int climate = 0; climate <= 5; climate++) {
            assertRange(MountainSurfaceRules.ESA_BARE, climate, ALPINE, 0, 99,
                TellusEarthSurfaceDecisionKernel.PaletteKind.STRATA);
        }
    }

    @Test
    void defaultCoverAndRollValidationMatchNativeDomain() {
        for (int climate = 0; climate <= 3; climate++) {
            assertCover(123456, climate, 0, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
            assertCover(123456, climate, 99, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.DEFAULT_OVERWORLD);
        }
        for (int climate : new int[]{4, 5}) {
            assertCover(123456, climate, 0, LOW,
                TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC);
            assertCover(123456, climate, 99, ALPINE,
                TellusEarthSurfaceDecisionKernel.PaletteKind.PODZOLIC);
        }
        assertThrows(IllegalArgumentException.class,
            () -> TellusEarthSurfaceDecisionKernel.cover(MountainSurfaceRules.ESA_TREE_COVER, (byte)0, -1, LOW));
        assertThrows(IllegalArgumentException.class,
            () -> TellusEarthSurfaceDecisionKernel.cover(MountainSurfaceRules.ESA_TREE_COVER, (byte)0, 100, LOW));
    }

    private static void assertRange(int coverClass, int climateGroup, int zoneHeight,
                                    int firstRoll, int lastRoll,
                                    TellusEarthSurfaceDecisionKernel.PaletteKind expected) {
        for (int roll = firstRoll; roll <= lastRoll; roll++) {
            assertCover(coverClass, climateGroup, roll, zoneHeight, expected);
        }
    }

    private static void assertCover(int coverClass, int climateGroup, int roll, int zoneHeight,
                                    TellusEarthSurfaceDecisionKernel.PaletteKind expected) {
        assertEquals(expected,
            TellusEarthSurfaceDecisionKernel.cover(coverClass, (byte)climateGroup, roll, zoneHeight),
            "cover=" + coverClass + ", climate=" + climateGroup + ", roll=" + roll + ", zone=" + zoneHeight);
    }

    private static TellusEarthSurfaceDecisionKernel.BaseInput input(
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
        return new TellusEarthSurfaceDecisionKernel.BaseInput(
            coverClass, randomLandPatch, ocean, river, mushroomFields, beach, stonyShore,
            badlands, desert, mangrove, swamp, stonyPeaks, gravellyHills);
    }
}
