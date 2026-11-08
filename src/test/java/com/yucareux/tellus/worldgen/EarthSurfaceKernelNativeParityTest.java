package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.decision.TellusEarthSurfaceDecisionKernel;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Native six-field parity against the frozen pre-migration git49a8 generator.
 *
 * <p>The oracle was transcribed from tellus-round36-native-oracle/
 * EarthChunkGenerator.baseline.java: cover branches 11554..11618, factories
 * 14971..15084, ocean floor 11692..11698, and salts 375..376. It intentionally
 * does not select an expected PaletteKind or call any native palette factory.
 * Only badlandsPlateauTop remains a native source/noise boundary: its returned
 * state is supplied to the otherwise literal badlands factory oracle.</p>
 */
class EarthSurfaceKernelNativeParityTest {
    // Frozen baseline dependency; the oracle must not silently follow a changed kernel threshold.
    private static final int FROZEN_ALPINE_HEIGHT = 200;
    private static final long FROZEN_COVER_SALT = 2870177441569192885L ^ 23L * -7046029254386353131L;
    private static final long FROZEN_OCEAN_SALT = 8006659316467387678L;
    private static final int[] COVER_CLASSES = {
        Integer.MIN_VALUE, -1, 0, 1, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 100, 255, Integer.MAX_VALUE
    };
    private static final byte[] CLIMATES = {Byte.MIN_VALUE, -1, 0, 1, 2, 3, 4, 5, 6, Byte.MAX_VALUE};
    private static final int[] ZONE_HEIGHTS = {
        FROZEN_ALPINE_HEIGHT - 1, FROZEN_ALPINE_HEIGHT, FROZEN_ALPINE_HEIGHT + 1
    };
    private static final Column[] MATRIX_COLUMNS = {
        new Column(0, 0), new Column(37, -91), new Column(-4097, 8193), new Column(1200000, -2300000)
    };
    private static final int[] REPRESENTATIVE_ROLLS = {
        0, 19, 20, 34, 35, 39, 40, 44, 45, 54, 55, 59, 60, 64, 65, 69, 70, 99
    };
    private static final String[] PALETTE_FIELDS = {
        "top", "underwaterTop", "filler", "depth", "landformSurface", "strata"
    };

    @TempDir
    static Path gameDirectory;

    private static Method coverMapper;
    private static Method plateauTop;
    private static Method[] paletteAccessors;
    private static String fixturePreviousGameDir;
    private static String fixturePreviousConfigDir;
    private static ClassLoader fixturePreviousLoader;

    @BeforeAll
    static void bootstrapAndResolveNativeBoundary() throws Exception {
        fixturePreviousGameDir = System.getProperty("tellus.gameDir");
        fixturePreviousConfigDir = System.getProperty("tellus.configDir");
        fixturePreviousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(EarthSurfaceKernelNativeParityTest.class.getClassLoader());
        System.setProperty("tellus.gameDir", gameDirectory.toString());
        System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Native static source construction needs the same explicit platform fixture as LodBiomeSnowTest.
        try (@SuppressWarnings("unused") var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            Class.forName(EarthChunkGenerator.class.getName(), true,
                EarthSurfaceKernelNativeParityTest.class.getClassLoader());
        }
        assertEquals(FROZEN_ALPINE_HEIGHT, MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA,
            "The frozen git49a8 alpine boundary changed");
        coverMapper = EarthChunkGenerator.class.getDeclaredMethod("surfacePaletteForCoverDecision",
            TellusEarthSurfaceDecisionKernel.PaletteKind.class, int.class, int.class);
        assertTrue(Modifier.isPrivate(coverMapper.getModifiers()), "The native cover mapper is private");
        assertTrue(Modifier.isStatic(coverMapper.getModifiers()), "The native cover matrix needs no generator instance");
        coverMapper.setAccessible(true);
        plateauTop = EarthChunkGenerator.class.getDeclaredMethod("badlandsPlateauTop", int.class, int.class);
        assertTrue(Modifier.isStatic(plateauTop.getModifiers()));
        plateauTop.setAccessible(true);

        Class<?> nativePalette = coverMapper.getReturnType();
        assertTrue(nativePalette.isRecord(), "Inspect every native SurfacePalette record field");
        assertEquals(Arrays.asList(PALETTE_FIELDS),
            Arrays.stream(nativePalette.getRecordComponents()).map(component -> component.getName()).toList());
        paletteAccessors = new Method[PALETTE_FIELDS.length];
        for (int index = 0; index < PALETTE_FIELDS.length; index++) {
            paletteAccessors[index] = nativePalette.getDeclaredMethod(PALETTE_FIELDS[index]);
            paletteAccessors[index].setAccessible(true);
        }
    }

    @AfterAll
    static void restorePlatformFixture() {
        restoreProperty("tellus.gameDir", fixturePreviousGameDir);
        restoreProperty("tellus.configDir", fixturePreviousConfigDir);
        Thread.currentThread().setContextClassLoader(fixturePreviousLoader);
    }

    @Test
    void exhaustiveCoverDecisionsMatchFrozenNativeBranchesAndAllFactoryFields() throws Exception {
        ParityCounts counts = new ParityCounts();
        for (Column column : MATRIX_COLUMNS) {
            BlockState badlandsTop = nativePlateauTop(column);
            for (int cover : COVER_CLASSES) {
                for (byte climate : CLIMATES) {
                    for (int zoneHeight : ZONE_HEIGHTS) {
                        for (int roll = 0; roll < 100; roll++) {
                            Palette expected = frozenCover(cover, climate, roll, zoneHeight, badlandsTop);
                            var selected = TellusEarthSurfaceDecisionKernel.cover(cover, climate, roll, zoneHeight);
                            Palette actual = readPalette(coverMapper.invoke(null, selected, column.x(), column.z()));
                            if (counts.compare(expected, actual)) {
                                counts.describe("cover=" + cover + " climate=" + climate + " roll=" + roll
                                    + " zone=" + zoneHeight + " column=" + column + " selected=" + selected,
                                    expected, actual);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(204000, counts.columns, "The full cover/climate/zone/roll/coordinate matrix ran");
        counts.verify("EARTH_SURFACE_KERNEL_PARITY");
    }

    @Test
    void builtUpStonyPeaksAndAlpineStrataHaveDifferentNativeStrataFlags() throws Exception {
        Column column = MATRIX_COLUMNS[0];
        var stonyKind = TellusEarthSurfaceDecisionKernel.cover(50, (byte) 3, 45, FROZEN_ALPINE_HEIGHT);
        var strataKind = TellusEarthSurfaceDecisionKernel.cover(60, (byte) 3, 45, FROZEN_ALPINE_HEIGHT);
        Palette stony = readPalette(coverMapper.invoke(null, stonyKind, column.x(), column.z()));
        Palette strata = readPalette(coverMapper.invoke(null, strataKind, column.x(), column.z()));
        assertEquals(Frozen.STONY_PEAKS, stony, "Baseline stonyPeaks(): stone12, strata=false");
        assertEquals(Frozen.STRATA, strata, "Baseline strata(12): stone12, strata=true");
        assertFalse(stony.strata(), "Round33 regression: STONY_PEAKS must not be mapped to strata(12)");
        assertTrue(strata.strata());
        assertEquals(new Palette(stony.top(), stony.underwaterTop(), stony.filler(), stony.depth(),
            stony.landformSurface(), true), strata, "Only the strata flag differs between these native factories");
    }

    @Test
    void nativeCoverDriverAndOceanFloorMatchFrozenSaltsWithoutSourceReads() throws Exception {
        String previousGameDir = System.getProperty("tellus.gameDir");
        String previousConfigDir = System.getProperty("tellus.configDir");
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try (@SuppressWarnings("unused") var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            Thread.currentThread().setContextClassLoader(EarthSurfaceKernelNativeParityTest.class.getClassLoader());
            System.setProperty("tellus.gameDir", gameDirectory.toString());
            System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());
            var plains = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(plains), EarthGeneratorSettings.DEFAULT);
            Method nativeCover = EarthChunkGenerator.class.getDeclaredMethod("coverDrivenSurfacePalette",
                int.class, byte.class, int.class, int.class, int.class);
            nativeCover.setAccessible(true);
            Method nativeOcean = EarthChunkGenerator.class.getDeclaredMethod("oceanFloorPalette", int.class, int.class);
            nativeOcean.setAccessible(true);

            ParityCounts counts = new ParityCounts();
            Column[] coverColumns = columnsForEveryRoll(FROZEN_COVER_SALT);
            for (int roll : REPRESENTATIVE_ROLLS) {
                Column column = coverColumns[roll];
                BlockState badlandsTop = nativePlateauTop(column);
                for (int cover : COVER_CLASSES) {
                    for (byte climate : CLIMATES) {
                        // heightAboveSea=0 exits mountainZoneHeight before any source/treeline lookup.
                        Palette expected = frozenCover(cover, climate, roll, 0, badlandsTop);
                        Palette actual = readPalette(nativeCover.invoke(generator, cover, climate,
                            column.x(), column.z(), generator.seaLevel()));
                        if (counts.compare(expected, actual)) {
                            counts.describe("native cover=" + cover + " climate=" + climate + " roll=" + roll
                                + " column=" + column, expected, actual);
                        }
                    }
                }
            }
            Column[] oceanColumns = columnsForEveryRoll(FROZEN_OCEAN_SALT);
            for (int roll = 0; roll < 100; roll++) {
                Column column = oceanColumns[roll];
                Block top = roll < 10 ? Blocks.GRAVEL : roll < 15 ? Blocks.CLAY : Blocks.SAND;
                Palette expected = literal(top, top, top, 4, false, false);
                Palette actual = readPalette(nativeOcean.invoke(generator, column.x(), column.z()));
                if (counts.compare(expected, actual)) {
                    counts.describe("native ocean roll=" + roll + " column=" + column, expected, actual);
                }
            }
            assertEquals(3160, counts.columns, "3060 sea-level cover columns plus every ocean roll");
            counts.verify("EARTH_SURFACE_NATIVE_DRIVER_PARITY");
        } finally {
            restoreProperty("tellus.gameDir", previousGameDir);
            restoreProperty("tellus.configDir", previousConfigDir);
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    /** Frozen old switch: literal ESA IDs, climate IDs, thresholds and native factory values. */
    private static Palette frozenCover(int cover, byte climate, int roll, int zoneHeight, BlockState badlandsTop) {
        return switch (cover) {
            case 10 -> climate != 4 && climate != 5
                ? (climate == 2
                    ? (roll < 70 ? Frozen.STEPPE : Frozen.DEFAULT_OVERWORLD)
                    : (climate == 1
                        ? (roll < 35 ? Frozen.ROOTED : Frozen.DEFAULT_OVERWORLD)
                        : (roll < 20 ? Frozen.ROOTED : Frozen.DEFAULT_OVERWORLD)))
                : (roll < 65 ? Frozen.PODZOLIC : Frozen.DEFAULT_OVERWORLD);
            case 20 -> zoneHeight >= FROZEN_ALPINE_HEIGHT && roll < 70
                ? Frozen.GRAVELLY
                : (climate == 2
                    ? Frozen.STEPPE
                    : (climate != 4 && climate != 5
                        ? (roll < 40 ? Frozen.STEPPE : Frozen.DEFAULT_OVERWORLD)
                        : Frozen.PODZOLIC));
            case 30 -> zoneHeight >= FROZEN_ALPINE_HEIGHT && roll < 35
                ? Frozen.GRAVELLY
                : (climate == 2
                    ? (roll < 65 ? Frozen.STEPPE : Frozen.DEFAULT_OVERWORLD)
                    : (climate != 4 && climate != 5
                        ? Frozen.DEFAULT_OVERWORLD
                        : (roll < 40 ? Frozen.PODZOLIC : Frozen.DEFAULT_OVERWORLD)));
            case 40 -> climate == 2
                ? Frozen.STEPPE
                : (roll < 65 ? Frozen.ROOTED : Frozen.DEFAULT_OVERWORLD);
            case 50 -> roll < 45 ? Frozen.GRAVELLY : Frozen.STONY_PEAKS;
            case 60 -> zoneHeight >= FROZEN_ALPINE_HEIGHT
                ? Frozen.STRATA
                : (climate == 2
                    ? (roll < 55 ? Frozen.DESERT
                        : new Palette(badlandsTop, Blocks.RED_SAND.defaultBlockState(),
                            Blocks.TERRACOTTA.defaultBlockState(), 4, false, false))
                    : (climate != 4 && climate != 5
                        ? Frozen.GRAVELLY
                        : (roll < 70 ? Frozen.GRAVELLY : Frozen.STRATA)));
            case 70 -> Frozen.SNOWY;
            case 90 -> roll < 40 ? Frozen.SWAMP : Frozen.WETLAND;
            case 95 -> Frozen.MANGROVE;
            case 100 -> zoneHeight >= FROZEN_ALPINE_HEIGHT && roll < 60
                ? Frozen.STRATA
                : (roll < 35 ? Frozen.MOSSY : Frozen.DEFAULT_OVERWORLD);
            default -> climate != 4 && climate != 5 ? Frozen.DEFAULT_OVERWORLD : Frozen.PODZOLIC;
        };
    }

    private static BlockState nativePlateauTop(Column column) throws Exception {
        return (BlockState) plateauTop.invoke(null, column.x(), column.z());
    }

    private static Palette readPalette(Object palette) throws Exception {
        assertNotNull(palette, "Native SurfacePalette");
        return new Palette(
            (BlockState) paletteAccessors[0].invoke(palette),
            (BlockState) paletteAccessors[1].invoke(palette),
            (BlockState) paletteAccessors[2].invoke(palette),
            (int) paletteAccessors[3].invoke(palette),
            (boolean) paletteAccessors[4].invoke(palette),
            (boolean) paletteAccessors[5].invoke(palette));
    }

    private static Column[] columnsForEveryRoll(long salt) {
        Column[] columns = new Column[100];
        int remaining = columns.length;
        // At most 65,536 local noise samples; no terrain source, generator or live IO.
        for (int z = -128; z < 128 && remaining > 0; z++) {
            for (int x = -128; x < 128 && remaining > 0; x++) {
                Column column = new Column(x * 47, z * 53);
                int roll = TellusSurfaceVariantRoll.percent(column.x(), column.z(), salt);
                if (columns[roll] == null) {
                    columns[roll] = column;
                    remaining--;
                }
            }
        }
        assertEquals(0, remaining, "The bounded coordinate fixture must exercise all 100 native rolls");
        return columns;
    }

    private static Palette literal(Block top, Block underwaterTop, Block filler, int depth,
                                   boolean landformSurface, boolean strata) {
        return new Palette(top.defaultBlockState(), underwaterTop.defaultBlockState(), filler.defaultBlockState(),
            depth, landformSurface, strata);
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    private record Column(int x, int z) {}

    private record Palette(BlockState top, BlockState underwaterTop, BlockState filler,
                           int depth, boolean landformSurface, boolean strata) {}

    /** Lazy initialization keeps all Blocks access after Minecraft bootstrap. */
    private static final class Frozen {
        private static final Palette DEFAULT_OVERWORLD = literal(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.DIRT, 4, false, false);
        private static final Palette STEPPE = literal(Blocks.COARSE_DIRT, Blocks.COARSE_DIRT, Blocks.DIRT, 4, false, false);
        private static final Palette PODZOLIC = literal(Blocks.PODZOL, Blocks.PODZOL, Blocks.DIRT, 4, false, false);
        private static final Palette ROOTED = literal(Blocks.ROOTED_DIRT, Blocks.ROOTED_DIRT, Blocks.DIRT, 4, false, false);
        private static final Palette MOSSY = literal(Blocks.MOSS_BLOCK, Blocks.MOSS_BLOCK, Blocks.DIRT, 4, false, false);
        private static final Palette DESERT = literal(Blocks.SAND, Blocks.SAND, Blocks.SANDSTONE, 5, false, false);
        private static final Palette SNOWY = literal(Blocks.SNOW_BLOCK, Blocks.SNOW_BLOCK, Blocks.DIRT, 4, false, false);
        private static final Palette SWAMP = literal(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.DIRT, 4, false, false);
        private static final Palette WETLAND = literal(Blocks.MUD, Blocks.MUD, Blocks.PACKED_MUD, 4, false, false);
        private static final Palette MANGROVE = literal(Blocks.MUD, Blocks.MUD, Blocks.DIRT, 4, false, false);
        private static final Palette STONY_PEAKS = literal(Blocks.STONE, Blocks.STONE, Blocks.STONE, 12, false, false);
        private static final Palette STRATA = literal(Blocks.STONE, Blocks.STONE, Blocks.STONE, 12, false, true);
        private static final Palette GRAVELLY = literal(Blocks.GRAVEL, Blocks.GRAVEL, Blocks.STONE, 4, false, false);
    }

    private static final class ParityCounts {
        private int columns;
        private int mismatches;
        private final List<String> examples = new ArrayList<>();

        private boolean compare(Palette expected, Palette actual) {
            columns++;
            if (expected.equals(actual)) {
                return false;
            }
            mismatches++;
            return examples.size() < 8;
        }

        private void describe(String context, Palette expected, Palette actual) {
            examples.add(context + " expected=" + expected + " actual=" + actual);
        }

        private void verify(String marker) {
            System.out.println(marker + " columns=" + columns + " mismatches=" + mismatches);
            assertEquals(0, mismatches, () -> String.join("\n", examples));
        }
    }
}
