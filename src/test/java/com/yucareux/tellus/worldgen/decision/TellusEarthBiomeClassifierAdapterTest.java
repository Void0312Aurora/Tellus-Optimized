package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Provider/exact/fallback ordering tests for the actual Earth classifier adapter. */
class TellusEarthBiomeClassifierAdapterTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void providerWinsBeforeExactAndFallbackWithOriginalInputs() {
        List<String> calls = new ArrayList<>();
        List<Object> forwarded = new ArrayList<>();
        TellusEarthBiomeClassifierAdapter adapter = new TellusEarthBiomeClassifierAdapter(
            (cover, koppen, x, z, scale) -> {
                calls.add("provider");
                forwarded.addAll(List.of(cover, koppen, x, z, scale));
                return Biomes.DESERT;
            },
            (cover, koppen) -> {
                calls.add("exact");
                return Biomes.BADLANDS;
            },
            cover -> {
                calls.add("fallback");
                return Biomes.PLAINS;
            });

        assertEquals("minecraft:desert", adapter.resolve(60, "BWh", 12L, -8L, 1.5));
        assertEquals(List.of("provider"), calls);
        assertEquals(List.of(60, "BWh", 12, -8, 1.5), forwarded);
    }

    @Test
    void exactThenFallbackOrderMatchesLegacyClassifier() {
        List<String> exactCalls = new ArrayList<>();
        TellusEarthBiomeClassifierAdapter exactAdapter = new TellusEarthBiomeClassifierAdapter(
            (cover, koppen, x, z, scale) -> null,
            (cover, koppen) -> {
                exactCalls.add(koppen);
                return Biomes.BADLANDS;
            },
            cover -> {
                throw new AssertionError("fallback must not run after exact match");
            });
        assertEquals("minecraft:badlands", exactAdapter.resolve(20, "BWk", 0L, 0L, 1.0));
        assertEquals(List.of("BWk"), exactCalls);

        TellusEarthBiomeClassifierAdapter fallbackAdapter = new TellusEarthBiomeClassifierAdapter(
            (cover, koppen, x, z, scale) -> null,
            (cover, koppen) -> null,
            cover -> {
                assertEquals(60, cover);
                return Biomes.PLAINS;
            });
        assertEquals("minecraft:plains", fallbackAdapter.resolve(60, "BSh", 0L, 0L, 1.0));
    }

    @Test
    void nullResolutionStaysUnavailableAndOutOfRangeCoordinatesDoNotTruncate() {
        int[] calls = {0};
        TellusEarthBiomeClassifierAdapter adapter = new TellusEarthBiomeClassifierAdapter(
            (cover, koppen, x, z, scale) -> {
                calls[0]++;
                return null;
            },
            (cover, koppen) -> {
                calls[0]++;
                return null;
            },
            cover -> {
                calls[0]++;
                return null;
            });
        assertNull(adapter.resolve(30, "Csa", Long.MAX_VALUE, 0L, 1.0));
        assertEquals(0, calls[0]);
        assertNull(adapter.resolve(30, "Csa", 1L, 2L, 1.0));
        assertEquals(3, calls[0]);
    }

    @Test
    void productionAdapterRetainsExistingExactAndFallbackFixtures() {
        TellusEarthBiomeClassifierAdapter production = TellusEarthBiomeClassifierAdapter.production();
        String exact = production.resolve(20, "BWh", 0L, 0L, 1.0);
        String exactCold = production.resolve(20, "BWk", 0L, 0L, 1.0);
        String exactSavanna = production.resolve(20, "BSh", 0L, 0L, 1.0);
        String exactMediterranean = production.resolve(20, "Csa", 0L, 0L, 1.0);

        assertTrue(exact != null || exactCold != null || exactSavanna != null || exactMediterranean != null,
            "existing classifier fixtures should resolve at least one known exact case");
        assertEquals("minecraft:stony_peaks", production.resolve(60, null, 0L, 0L, 1.0));
    }
}
