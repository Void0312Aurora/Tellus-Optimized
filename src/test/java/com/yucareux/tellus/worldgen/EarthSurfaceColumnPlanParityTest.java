package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.world.data.geology.Lithology;
import com.yucareux.tellus.worldgen.decision.BiomeDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.StrataDecision;
import com.yucareux.tellus.worldgen.decision.SurfaceDecision;
import com.yucareux.tellus.worldgen.decision.TellusEarthColumnDecisionKernel;
import com.yucareux.tellus.worldgen.decision.TellusEarthColumnSnapshot;
import com.yucareux.tellus.worldgen.decision.TellusEarthSurfaceColumnRules;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.WaterDecision;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import com.yucareux.tellus.worldgen.write.TellusColumnMaterializer;
import com.yucareux.tellus.worldgen.write.TellusNativeSemanticMaterials;
import com.yucareux.tellus.worldgen.write.WriteContext;
import com.yucareux.tellus.worldgen.write.WriteStatus;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual native preparation and both writers against the literal pre-extraction surface loops.
 *
 * <p>Frozen from EarthChunkGenerator lines 5914..6325 before the shared-plan extraction:
 * both applyThinShellSurface overloads, both applySurface overloads, badlands/strata/material
 * loops, the two bottom calculations and shallowSurfaceFiller. Expectations never call the
 * new surface rules. Existing natural-depth noise and StrataPolicy.Column.material remain
 * source boundaries; badlands relief arithmetic and native material tables are frozen here.
 * No GIS, biome palette selection or native full-chunk source resolver is invoked.</p>
 */
class EarthSurfaceColumnPlanParityTest {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 256;
    private static final long WORLD_SEED = 0L; // An unseeded native generator's original field value.
    private static final int[][] COLUMNS = {{0, 0}, {31, -17}, {-4097, 8193}};
    private static final int[] SURFACES = {-65, -64, -63, -49, 0, 127};

    @TempDir
    static Path gameDirectory;

    private static Constructor<?> paletteConstructor;
    private static Constructor<?> protoConstructor;
    private static Method prepare;
    private static Method ruleAccessor;
    private static Method applyCursor;
    private static Method applySections;
    private static Registry<Biome> biomeRegistry;
    private static net.minecraft.core.Holder<Biome> plains;
    private static String previousGameDir;
    private static String previousConfigDir;
    private static ClassLoader previousLoader;

    @BeforeAll
    static void bootstrapAndResolvePrivateNativeApis() throws Exception {
        previousGameDir = System.getProperty("tellus.gameDir");
        previousConfigDir = System.getProperty("tellus.configDir");
        previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(EarthSurfaceColumnPlanParityTest.class.getClassLoader());
        System.setProperty("tellus.gameDir", gameDirectory.toString());
        System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (@SuppressWarnings("unused") var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            Class.forName(EarthChunkGenerator.class.getName(), true,
                EarthSurfaceColumnPlanParityTest.class.getClassLoader());
        }
        plains = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        biomeRegistry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        Registry.register(biomeRegistry, Biomes.PLAINS, plains.value());
        // Vanilla 1.20/1.21 use this overload; reflecting it keeps constructor use isolated.
        protoConstructor = ProtoChunk.class.getDeclaredConstructor(ChunkPos.class, UpgradeData.class,
            LevelHeightAccessor.class, Registry.class,
            Class.forName("net.minecraft.world.level.levelgen.blending.BlendingData"));
        protoConstructor.setAccessible(true);
        Class<?> palette = nested("SurfacePalette");
        assertTrue(palette.isRecord());
        assertTrue(Modifier.isPrivate(palette.getModifiers()));
        assertEquals(List.of("top", "underwaterTop", "filler", "depth", "landformSurface", "strata"),
            Arrays.stream(palette.getRecordComponents()).map(component -> component.getName()).toList());
        paletteConstructor = palette.getDeclaredConstructor(BlockState.class, BlockState.class, BlockState.class,
            int.class, boolean.class, boolean.class);
        paletteConstructor.setAccessible(true);
        Class<?> resolved = nested("ResolvedSurfacePlan");
        assertTrue(resolved.isRecord());
        assertTrue(Modifier.isPrivate(resolved.getModifiers()));
        ruleAccessor = resolved.getDeclaredMethod("rule");
        assertEquals(TellusEarthSurfaceColumnRules.Plan.class, ruleAccessor.getReturnType());
        ruleAccessor.setAccessible(true);
        prepare = privateMethod("prepareResolvedSurfacePlan", palette, StrataPolicy.Column.class, BlockState.class,
            boolean.class, boolean.class, boolean.class, int.class, int.class, int.class, int.class, int.class);
        assertEquals(resolved, prepare.getReturnType());
        applyCursor = privateMethod("applyResolvedSurfacePlan", ChunkAccess.class, BlockPos.MutableBlockPos.class,
            int.class, int.class, resolved);
        applySections = privateMethod("applyResolvedSurfacePlan", EarthChunkGenerator.ChunkSectionWriter.class,
            int.class, int.class, resolved);
    }

    @AfterAll
    static void restorePlatformFixture() {
        restoreProperty("tellus.gameDir", previousGameDir);
        restoreProperty("tellus.configDir", previousConfigDir);
        Thread.currentThread().setContextClassLoader(previousLoader);
    }

    @Test
    void resolvedNativePlansAndBothActualBackendsMatchFrozenLoopsAndSemanticOverlay() throws Exception {
        Counts counts = new Counts();
        for (int base : new int[]{0, 4, 24, 65536, 70000}) {
            for (int relief : new int[]{Integer.MIN_VALUE, 2, 3, 15, 16, 17, 65527, 65528, Integer.MAX_VALUE}) {
                assertEquals(frozenCliffDepth(base, relief), BadlandsTerrainPolicy.cliffBandDepth(base, relief),
                    "Captured unchanged badlands relief-depth boundary");
            }
        }
        try (@SuppressWarnings("unused") var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            for (int undergroundDepth : new int[]{64, 96}) {
                EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(plains),
                    EarthGeneratorSettings.DEFAULT.withUndergroundDepth(undergroundDepth));
                SemanticFixture semantic = semanticFixture(generator);
                for (int[] column : COLUMNS) {
                    int worldX = column[0];
                    int worldZ = column[1];
                    ProtoChunk cursorChunk = chunk(worldX, worldZ);
                    ProtoChunk sectionChunk = chunk(worldX, worldZ);
                    for (Scenario scenario : scenarios(worldX, worldZ)) {
                        compare(generator, semantic, cursorChunk, sectionChunk,
                            worldX, worldZ, scenario, counts);
                    }
                }
            }
        }
        assertEquals(2622, counts.columns, "The entire fixed surface matrix ran");
        assertTrue(counts.columns > 1000 && counts.columns < 10000, "Bounded substantial surface matrix");
        assertEquals(EnumSet.allOf(TellusEarthSurfaceColumnRules.Mode.class), counts.modes);
        assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6), counts.badlandsMaterials,
            "All seven original badlands band indices reached actual native writes");
        assertTrue(counts.semanticColumns > 0);
        assertTrue(counts.nonDefaultColumns > 0, "Exact native property states stay representationally honest");
        assertTrue(counts.emptySpans > 0);
        assertTrue(counts.minimumThinTops > 0);
        assertTrue(counts.sandShallow > 0 && counts.redSandShallow > 0);
        assertTrue(counts.oreTops > 0 && counts.badlandsIgnoredOreTops > 0);
        System.out.println("EARTH_SURFACE_PLAN_PARITY columns=" + counts.columns
            + " mismatchBlocks=" + counts.mismatchBlocks + " semanticColumns=" + counts.semanticColumns
            + " nonDefaultColumns=" + counts.nonDefaultColumns);
        assertEquals(0, counts.mismatchBlocks, () -> String.join("\n", counts.examples));
    }

    private static List<Scenario> scenarios(int x, int z) {
        List<Scenario> cases = new ArrayList<>();
        List<Palette> palettes = List.of(
            palette(Blocks.SAND, Blocks.SAND, Blocks.SANDSTONE, 5, false, false),
            palette(Blocks.RED_SAND, Blocks.RED_SAND, Blocks.RED_SANDSTONE, 5, false, false),
            palette(Blocks.STONE, Blocks.GRAVEL, Blocks.STONE, 12, false, false),
            palette(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.DIRT, 4, false, false),
            palette(Blocks.MUD, Blocks.CLAY, Blocks.PACKED_MUD, 4, true, false),
            palette(Blocks.STONE, Blocks.CLAY, Blocks.STONE, 80, true, false),
            palette(Blocks.STONE, Blocks.GRAVEL, Blocks.STONE, 1, false, false),
            palette(Blocks.STONE, Blocks.GRAVEL, Blocks.STONE, 0, false, false),
            new Palette(Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true),
                state(Blocks.DIRT), state(Blocks.DIRT), 4, false, false),
            new Palette(Blocks.DEEPSLATE.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                state(Blocks.CLAY), state(Blocks.TUFF), 12, false, false));
        for (Palette palette : palettes) {
            for (boolean thin : new boolean[]{false, true}) {
                for (boolean underwater : new boolean[]{false, true}) {
                    for (int surface : SURFACES) {
                        cases.add(new Scenario("standard", palette, null,
                            underwater ? palette.underwaterTop() : palette.top(), thin, underwater, false, 2, surface));
                    }
                }
            }
        }
        for (boolean thin : new boolean[]{false, true}) {
            for (int surface : SURFACES) {
                cases.add(new Scenario("no-palette", null, null, state(Blocks.DEEPSLATE_COAL_ORE),
                    thin, false, true, Integer.MAX_VALUE, surface));
            }
        }
        Palette strataPalette = palette(Blocks.STONE, Blocks.CLAY, Blocks.STONE, 80, true, true);
        for (Lithology lithology : new Lithology[]{Lithology.SILICICLASTIC_SEDIMENTARY,
            Lithology.BASIC_VOLCANIC, Lithology.METAMORPHIC}) {
            StrataPolicy.Column strata = StrataPolicy.column(lithology, true, x, z, 1.0, 1.0, 63, 42L);
            for (boolean thin : new boolean[]{false, true}) {
                for (boolean wet : new boolean[]{false, true}) {
                    for (int surface : new int[]{-65, -64, -63, 0, 127}) {
                        BlockState top = wet ? strataPalette.underwaterTop()
                            : thin ? state(Blocks.EMERALD_ORE) : frozenStrata(strata, surface);
                        cases.add(new Scenario("explicit-strata-" + lithology, strataPalette, strata,
                            top, thin, wet, false, 2, surface));
                    }
                }
            }
            // Original thin-shell dispatch keys off the supplied column, full dispatch off palette.strata.
            Palette unflagged = palette(Blocks.STONE, Blocks.CLAY, Blocks.STONE, 80, true, false);
            cases.add(new Scenario("thin-supplied-strata-overrides-flag", unflagged, strata,
                state(Blocks.GOLD_ORE), true, false, false, 2, 127));
            cases.add(new Scenario("full-unflagged-ignores-strata", unflagged, strata,
                unflagged.top(), false, false, false, 2, 127));
        }
        // A source flag without supplied strata is valid on the thin dispatch: no source is queried here.
        cases.add(new Scenario("thin-flag-without-source", strataPalette, null, state(Blocks.IRON_ORE),
            true, false, false, 2, 127));
        for (Block ore : new Block[]{Blocks.COAL_ORE, Blocks.COPPER_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.DEEPSLATE_EMERALD_ORE}) {
            cases.add(new Scenario("supplied-ore-top", palettes.get(2), null, state(ore),
                true, false, false, 0, 127));
        }
        Palette badlands = palette(Blocks.RED_SAND, Blocks.CLAY, Blocks.TERRACOTTA, 4, true, true);
        StrataPolicy.Column source = StrataPolicy.column(Lithology.CARBONATE_SEDIMENTARY,
            false, x, z, 1.0, 1.0, 63, 42L);
        for (int relief : new int[]{2, 3, 15, 16, 17, 72, 65527, 65528, Integer.MAX_VALUE}) {
            for (boolean thin : new boolean[]{false, true}) {
                for (boolean wet : new boolean[]{false, true}) {
                    for (int surface : new int[]{-64, 0, 127}) {
                        cases.add(new Scenario("badlands-relief-" + relief, badlands, source,
                            wet ? badlands.underwaterTop()
                                : thin || relief >= 3 ? state(Blocks.GOLD_ORE) : frozenStrata(source, surface),
                            thin, wet, true, relief, surface));
                    }
                }
            }
        }
        return cases;
    }

    private static void compare(EarthChunkGenerator generator, SemanticFixture semantic, ProtoChunk cursorChunk,
                                ProtoChunk sectionChunk,
                                int x, int z, Scenario scenario, Counts counts) throws Exception {
        String context = "x=" + x + " z=" + z + " support=" + generator.settings().undergroundDepth()
            + " " + scenario;
        BlockState sentinel = state(Blocks.DRIPSTONE_BLOCK);
        BlockState[] expected = new BlockState[HEIGHT];
        Arrays.fill(expected, sentinel);
        FrozenPlan frozen = frozenSurface(generator, x, z, scenario, expected);
        reset(cursorChunk, x, z, sentinel);
        reset(sectionChunk, x, z, sentinel);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x + 1, MIN_Y, z);
        BlockPos before = cursor.immutable();
        Object nativePalette = scenario.palette() == null ? null : scenario.palette().nativeValue();
        Object nativePlan = prepare.invoke(generator, nativePalette, scenario.strata(), scenario.top(),
            scenario.thin(), scenario.underwater(), scenario.badlands(), scenario.relief(), x, z,
            scenario.surface(), MIN_Y);
        assertEquals(before, cursor, "Preparation has no cursor effect: " + context);
        compareChunk(cursorChunk, x, z, filled(sentinel), sentinel, "prepare/cursor " + context, counts);
        compareChunk(sectionChunk, x, z, filled(sentinel), sentinel, "prepare/sections " + context, counts);
        TellusEarthSurfaceColumnRules.Plan<BlockState> rule = readRule(nativePlan);
        counts.modes.add(frozen.mode());
        if (frozen.mode() == TellusEarthSurfaceColumnRules.Mode.NONE) {
            assertNull(nativePlan, context);
        } else {
            assertNotNull(rule, context);
            assertEquals(frozen.mode(), rule.mode(), context);
            assertEquals(scenario.surface(), rule.span().surfaceY(), context);
            assertEquals(frozen.bottom(), rule.span().bottomY(), context);
            assertEquals(Math.max(0, scenario.surface() - frozen.bottom() + 1), rule.span().cellCount(), context);
            List<TellusEarthSurfaceColumnRules.Cell<BlockState>> cells = rule.cells();
            assertEquals(rule.span().cellCount(), cells.size(), context);
            for (int index = 0; index < cells.size(); index++) {
                var cell = cells.get(index);
                assertEquals(scenario.surface() - index, cell.y(), context);
                assertEquals(expected[cell.y() - MIN_Y], cell.material(), context);
                assertEquals(cell.material(), rule.materialAt(cell.y()), context);
            }
            assertThrows(IllegalArgumentException.class, () -> rule.materialAt(frozen.bottom() - 1), context);
            assertThrows(IllegalArgumentException.class, () -> rule.materialAt(scenario.surface() + 1), context);
            if (cells.isEmpty()) counts.emptySpans++;
            if (scenario.thin() && scenario.surface() == MIN_Y) counts.minimumThinTops++;
            if (rule.mode() == TellusEarthSurfaceColumnRules.Mode.BADLANDS) {
                for (int y = scenario.surface() - 1; y >= frozen.bottom(); y--) {
                    counts.badlandsMaterials.add(BadlandsTerrainPolicy.bandMaterialIndex(x, z, y));
                }
                if (scenario.top().is(Blocks.GOLD_ORE)) {
                    assertEquals(scenario.palette().top(), rule.top(), context);
                    counts.badlandsIgnoredOreTops++;
                }
            }
            if (rule.mode() == TellusEarthSurfaceColumnRules.Mode.STANDARD && cells.size() >= 3) {
                if (scenario.top().is(Blocks.SAND) && scenario.palette().filler().is(Blocks.SANDSTONE)) {
                    assertEquals(state(Blocks.SAND), rule.materialAt(scenario.surface() - 1), context);
                    assertEquals(state(Blocks.SANDSTONE), rule.materialAt(frozen.bottom()), context);
                    counts.sandShallow++;
                }
                if (scenario.top().is(Blocks.RED_SAND) && scenario.palette().filler().is(Blocks.RED_SANDSTONE)) {
                    assertEquals(state(Blocks.RED_SAND), rule.materialAt(scenario.surface() - 1), context);
                    assertEquals(state(Blocks.RED_SANDSTONE), rule.materialAt(frozen.bottom()), context);
                    counts.redSandShallow++;
                }
            }
            if (scenario.name().equals("supplied-ore-top")) {
                assertEquals(scenario.top(), rule.top(), context);
                counts.oreTops++;
            }
        }
        applyCursor.invoke(generator, cursorChunk, cursor, x, z, nativePlan);
        var writer = new EarthChunkGenerator.ChunkSectionWriter(sectionChunk.getSections(), MIN_Y);
        try {
            applySections.invoke(generator, writer, x & 15, z & 15, nativePlan);
            writer.finish();
        } finally {
            writer.close();
        }
        compareChunk(cursorChunk, x, z, expected, sentinel, "cursor " + context, counts);
        compareChunk(sectionChunk, x, z, expected, sentinel, "sections " + context, counts);
        if (scenario.surface() >= MIN_Y) compareSemantic(semantic, scenario.surface(), rule, expected, context, counts);
        counts.columns++;
    }

    /** Literal old dispatch and loops; no TellusEarthSurfaceColumnRules decision or range helper. */
    private static FrozenPlan frozenSurface(EarthChunkGenerator generator, int x, int z,
                                             Scenario input, BlockState[] states) {
        int surface = input.surface();
        Palette palette = input.palette();
        if (surface < MIN_Y || palette == null && !input.thin()) {
            return new FrozenPlan(TellusEarthSurfaceColumnRules.Mode.NONE, surface + 1);
        }
        if (palette == null) {
            states[surface - MIN_Y] = input.top();
            return new FrozenPlan(TellusEarthSurfaceColumnRules.Mode.TOP_ONLY, surface);
        }
        boolean bands = !input.underwater() && input.badlands() && input.relief() >= 3;
        boolean strata = !input.underwater() && (input.thin() ? input.strata() != null : palette.strata());
        int depth = bands ? frozenCliffDepth(palette.depth(), input.relief()) : strata ? palette.depth()
            : frozenNatural(input.top(), palette.filler())
                ? SurfaceMaterialDepthPolicy.naturalDepth(palette.depth(), x, z, WORLD_SEED) : palette.depth();
        int bottom = Math.max(MIN_Y + 1, surface - depth + 1);
        if (input.thin()) {
            int supportBottomY = Math.max(MIN_Y, surface - generator.settings().undergroundDepth());
            bottom = Math.min(surface, Math.max(supportBottomY + 1, bottom));
        }
        if (bands) {
            for (int y = surface; y >= bottom; y--) {
                states[y - MIN_Y] = y == surface ? palette.top() : frozenBand(x, z, y);
            }
        } else if (strata) {
            for (int y = surface; y >= bottom; y--) {
                states[y - MIN_Y] = y == surface ? input.top() : frozenStrata(input.strata(), y);
            }
        } else {
            BlockState shallowFiller = input.top().is(Blocks.SAND) && palette.filler().is(Blocks.SANDSTONE)
                || input.top().is(Blocks.RED_SAND) && palette.filler().is(Blocks.RED_SANDSTONE)
                ? input.top() : palette.filler();
            for (int y = surface; y >= bottom; y--) {
                states[y - MIN_Y] = y == surface ? input.top() : y == bottom ? palette.filler() : shallowFiller;
            }
        }
        return new FrozenPlan(bands ? TellusEarthSurfaceColumnRules.Mode.BADLANDS
            : strata ? TellusEarthSurfaceColumnRules.Mode.STRATA : TellusEarthSurfaceColumnRules.Mode.STANDARD, bottom);
    }

    private static int frozenCliffDepth(int base, int relief) {
        int normalizedBaseDepth = Math.max(1, base);
        long reliefDepth = Math.max(0L, (long) relief) + 8L;
        return (int) Math.min(65536L, Math.max(Math.max(normalizedBaseDepth, 24), reliefDepth));
    }

    private static boolean frozenNatural(BlockState top, BlockState filler) {
        return frozenSoil(top) || frozenSoil(filler) || top.is(Blocks.SAND) || top.is(Blocks.RED_SAND)
            || filler.is(Blocks.SAND) || filler.is(Blocks.RED_SAND);
    }

    private static boolean frozenSoil(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.MUD);
    }

    private static BlockState frozenBand(int x, int z, int y) {
        return switch (BadlandsTerrainPolicy.bandMaterialIndex(x, z, y)) {
            case 0 -> state(Blocks.TERRACOTTA);
            case 1 -> state(Blocks.ORANGE_TERRACOTTA);
            case 2 -> state(Blocks.YELLOW_TERRACOTTA);
            case 3 -> state(Blocks.BROWN_TERRACOTTA);
            case 4 -> state(Blocks.RED_TERRACOTTA);
            case 5 -> state(Blocks.LIGHT_GRAY_TERRACOTTA);
            case 6 -> state(Blocks.WHITE_TERRACOTTA);
            default -> throw new AssertionError("Unexpected original badlands material");
        };
    }

    private static BlockState frozenStrata(StrataPolicy.Column column, int y) {
        return state(switch (column.material(y)) {
            case 0 -> Blocks.STONE;
            case 1 -> Blocks.ANDESITE;
            case 2 -> Blocks.DIORITE;
            case 3 -> Blocks.GRANITE;
            case 4 -> Blocks.CALCITE;
            case 5 -> Blocks.DEEPSLATE;
            case 6 -> Blocks.TUFF;
            case 7 -> Blocks.BASALT;
            case 8 -> Blocks.SMOOTH_BASALT;
            case 9 -> Blocks.BLACKSTONE;
            case 10 -> Blocks.SANDSTONE;
            case 11 -> Blocks.SMOOTH_SANDSTONE;
            case 12 -> Blocks.RED_SANDSTONE;
            case 13 -> Blocks.TERRACOTTA;
            case 14 -> Blocks.WHITE_TERRACOTTA;
            case 15 -> Blocks.LIGHT_GRAY_TERRACOTTA;
            case 16 -> Blocks.RED_TERRACOTTA;
            case 17 -> Blocks.ORANGE_TERRACOTTA;
            case 18 -> Blocks.PACKED_MUD;
            case 19 -> Blocks.COARSE_DIRT;
            case 20 -> Blocks.CLAY;
            case 21 -> Blocks.COBBLESTONE;
            default -> throw new AssertionError("Unexpected original strata material");
        });
    }

    private static void compareSemantic(SemanticFixture fixture, int surface,
                                        TellusEarthSurfaceColumnRules.Plan<BlockState> rule,
                                        BlockState[] expected, String context, Counts counts) {
        List<TellusEarthSurfaceColumnRules.Cell<SemanticMaterial>> patch = new ArrayList<>();
        boolean representable = true;
        if (rule != null) {
            for (var cell : rule.cells()) {
                Optional<SemanticMaterial> material = TellusNativeSemanticMaterials.material(cell.material());
                if (!cell.material().equals(cell.material().getBlock().defaultBlockState())) {
                    assertTrue(material.isEmpty(), "Nondefault native state must not be flattened: " + context);
                }
                representable &= material.isPresent();
                patch.add(new TellusEarthSurfaceColumnRules.Cell<>(cell.y(), material.orElse(SemanticMaterial.UNKNOWN)));
            }
        }
        var generation = fixture.generation();
        TellusEarthColumnSnapshot body = new TellusEarthColumnSnapshot(generation, fixture.coordinate(), surface,
            new SurfaceDecision(SemanticMaterial.DRIPSTONE_BLOCK, SemanticMaterial.DRIPSTONE_BLOCK, 0),
            WaterDecision.dry(), BiomeDecision.defaultBiome(),
            new StrataDecision(List.of(new StrataDecision.Layer(MIN_Y, surface, SemanticMaterial.DRIPSTONE_BLOCK)), true),
            List.of("pre-resolved sentinel body"));
        var overlay = body.withResolvedSurface(patch, BiomeDecision.defaultBiome());
        if (!representable) {
            assertTrue(overlay.isEmpty(), "Unknown property state must leave semantic overlay unavailable: " + context);
            counts.nonDefaultColumns++;
            return;
        }
        var snapshot = overlay.orElseThrow();
        var kernel = TellusEarthColumnDecisionKernel.resolvedColumns((g, c, sample, koppen) -> Optional.of(snapshot));
        var decision = kernel.decide(generation, fixture.coordinate(), fixture.sample(), null);
        assertEquals(DecisionStatus.AVAILABLE, decision.status(), context);
        var result = new TellusColumnMaterializer().materializeCells(
            WriteContext.canonical(fixture.coordinate(), generation, "earth-surface-plan-parity"), decision);
        assertEquals(WriteStatus.WRITTEN, result.status(), context);
        BlockState[] actual = filled(state(Blocks.DRIPSTONE_BLOCK));
        Set<Integer> written = new HashSet<>();
        for (var cell : result.mappedWrites()) {
            assertTrue(written.add(cell.y()), "No duplicate semantic writes: " + context);
            assertTrue(cell.y() >= MIN_Y && cell.y() <= surface, context);
            actual[cell.y() - MIN_Y] = TellusNativeSemanticMaterials.state(cell.material());
        }
        assertEquals(surface - MIN_Y + 1, written.size(), "Every body cell is known: " + context);
        for (int index = 0; index < HEIGHT; index++) {
            counts.compare(expected[index], actual[index], context, MIN_Y + index);
        }
        counts.semanticColumns++;
    }

    private static SemanticFixture semanticFixture(EarthChunkGenerator generator) {
        double scale = generator.settings().worldScale();
        GeometryIdentity geometry = GeometryIdentity.forProvider("tellus:earth", "surface-parity-v1",
            "earth-projection-v1", "earth-noise-v1", scale);
        var geo = new SphereWorldModel.GeoPoint(46.0, 12.0, 0.0);
        var coordinate = TellusCoordinateContextFactory.fromGeo(SphereWorldModel.forWorldScale(scale), geo,
            new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo, scale)));
        var generation = new TellusGenerationContext(WORLD_SEED, scale, 63, MIN_Y, MIN_Y + HEIGHT, geometry);
        var request = TellusSampleRequest.fromContext(coordinate, SamplingPurpose.TERRAIN_COLUMN,
            SamplingResolution.exact(scale), geometry, WORLD_SEED);
        var sample = new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 1000000.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 10, 10), TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("surface-plan-parity", "r1", true, "pre-resolved native surface"));
        return new SemanticFixture(generation, coordinate, sample);
    }

    private static ProtoChunk chunk(int x, int z) throws Exception {
        return (ProtoChunk) protoConstructor.newInstance(new ChunkPos(x >> 4, z >> 4), UpgradeData.EMPTY,
            LevelHeightAccessor.create(MIN_Y, HEIGHT), biomeRegistry, null);
    }

    private static void reset(ProtoChunk chunk, int x, int z, BlockState sentinel) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
            MinecraftVersionCompat.setBlockState(chunk, cursor.set(x, y, z), sentinel);
            MinecraftVersionCompat.setBlockState(chunk, cursor.set(x ^ 1, y, z), sentinel);
        }
    }

    private static void compareChunk(ProtoChunk chunk, int x, int z, BlockState[] expected, BlockState sentinel,
                                     String context, Counts counts) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int index = 0; index < HEIGHT; index++) {
            int y = MIN_Y + index;
            counts.compare(expected[index], chunk.getBlockState(cursor.set(x, y, z)), context, y);
            counts.compare(sentinel, chunk.getBlockState(cursor.set(x ^ 1, y, z)), context, y);
        }
    }

    private static BlockState[] filled(BlockState state) {
        BlockState[] states = new BlockState[HEIGHT];
        Arrays.fill(states, state);
        return states;
    }

    @SuppressWarnings("unchecked")
    private static TellusEarthSurfaceColumnRules.Plan<BlockState> readRule(Object nativePlan) throws Exception {
        return nativePlan == null ? null : (TellusEarthSurfaceColumnRules.Plan<BlockState>) ruleAccessor.invoke(nativePlan);
    }

    private static Class<?> nested(String name) {
        return Arrays.stream(EarthChunkGenerator.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals(name)).findFirst().orElseThrow();
    }

    private static Method privateMethod(String name, Class<?>... arguments) throws Exception {
        Method method = EarthChunkGenerator.class.getDeclaredMethod(name, arguments);
        assertTrue(Modifier.isPrivate(method.getModifiers()));
        assertFalse(Modifier.isStatic(method.getModifiers()));
        method.setAccessible(true);
        return method;
    }

    private static Palette palette(Block top, Block underwater, Block filler, int depth,
                                   boolean landform, boolean strata) {
        return new Palette(state(top), state(underwater), state(filler), depth, landform, strata);
    }

    private static BlockState state(Block block) { return block.defaultBlockState(); }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private record Palette(BlockState top, BlockState underwaterTop, BlockState filler,
                           int depth, boolean landformSurface, boolean strata) {
        Object nativeValue() throws Exception {
            return paletteConstructor.newInstance(top, underwaterTop, filler, depth, landformSurface, strata);
        }
    }

    private record Scenario(String name, Palette palette, StrataPolicy.Column strata, BlockState top,
                            boolean thin, boolean underwater, boolean badlands, int relief, int surface) { }
    private record FrozenPlan(TellusEarthSurfaceColumnRules.Mode mode, int bottom) { }
    private record SemanticFixture(TellusGenerationContext generation, TellusCoordinateContext coordinate,
                                   TellusSample sample) { }

    private static final class Counts {
        int columns;
        long mismatchBlocks;
        int semanticColumns;
        int nonDefaultColumns;
        int emptySpans;
        int minimumThinTops;
        int sandShallow;
        int redSandShallow;
        int oreTops;
        int badlandsIgnoredOreTops;
        final EnumSet<TellusEarthSurfaceColumnRules.Mode> modes = EnumSet.noneOf(TellusEarthSurfaceColumnRules.Mode.class);
        final Set<Integer> badlandsMaterials = new HashSet<>();
        final List<String> examples = new ArrayList<>();

        void compare(BlockState expected, BlockState actual, String context, int y) {
            if (!expected.equals(actual)) {
                mismatchBlocks++;
                if (examples.size() < 12) examples.add(context + " y=" + y + " expected=" + expected + " actual=" + actual);
            }
        }
    }
}
