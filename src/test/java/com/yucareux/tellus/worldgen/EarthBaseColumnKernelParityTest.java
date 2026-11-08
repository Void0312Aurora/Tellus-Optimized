package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.caves.TellusCaveDepthMapper;
import com.yucareux.tellus.worldgen.decision.BiomeDecision;
import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.TellusEarthColumnDecisionKernel;
import com.yucareux.tellus.worldgen.decision.TellusEarthColumnRules;
import com.yucareux.tellus.worldgen.decision.TellusEarthColumnSnapshot;
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
import com.yucareux.tellus.worldgen.write.SemanticColumnCell;
import com.yucareux.tellus.worldgen.write.TellusColumnMaterializer;
import com.yucareux.tellus.worldgen.write.WriteContext;
import com.yucareux.tellus.worldgen.write.WriteStatus;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Complete native and semantic base-column parity without terrain/source sampling.
 *
 * <p>The oracle freezes the literal getBaseColumn loops read from the live
 * EarthChunkGenerator at lines 5021..5066 before their extraction. It does not
 * derive expected blocks from TellusEarthColumnRules or the semantic materializer.
 * The unchanged cave mapper remains the same native dependency as in those loops.</p>
 */
class EarthBaseColumnKernelParityTest {
    private static final int[] UNDERGROUND_DEPTHS = {64, 96, 128, 192, 256, 384, 512};
    private static final Range[] RANGES = {
        new Range(-512, 384), new Range(-64, 384), new Range(-17, 33), new Range(-1, 17),
        new Range(0, 17), new Range(1, 96), new Range(64, 320), new Range(1024, 64)
    };
    private static final int EXPECTED_COLUMNS = 1981; // 7 * 8 * 5 * 7 + 7 * 3.

    @TempDir
    static Path gameDirectory;

    private static Constructor<?> columnHeightsConstructor;
    private static Method nativeMaterializer;
    private static Method nativeInput;
    private static String previousGameDir;
    private static String previousConfigDir;
    private static ClassLoader previousLoader;

    @BeforeAll
    static void bootstrapBeforeNativeStaticSourcesAndResolveActualHelper() throws Exception {
        previousGameDir = System.getProperty("tellus.gameDir");
        previousConfigDir = System.getProperty("tellus.configDir");
        previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(EarthBaseColumnKernelParityTest.class.getClassLoader());
        System.setProperty("tellus.gameDir", gameDirectory.toString());
        System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Match the repaired EarthSurfaceKernelNativeParityTest platform fixture.
        try (@SuppressWarnings("unused") var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            Class.forName(EarthChunkGenerator.class.getName(), true,
                EarthBaseColumnKernelParityTest.class.getClassLoader());
        }
        Class<?> columnHeights = Arrays.stream(EarthChunkGenerator.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("ColumnHeights")).findFirst().orElseThrow();
        assertTrue(columnHeights.isRecord());
        assertTrue(Modifier.isPrivate(columnHeights.getModifiers()));
        assertEquals(List.of("terrainSurface", "waterSurface", "hasWater"),
            Arrays.stream(columnHeights.getRecordComponents()).map(component -> component.getName()).toList());
        columnHeightsConstructor = columnHeights.getDeclaredConstructor(int.class, int.class, boolean.class);
        columnHeightsConstructor.setAccessible(true);
        nativeMaterializer = EarthChunkGenerator.class.getDeclaredMethod("materializeResolvedBaseColumn",
            int.class, int.class, columnHeights);
        assertTrue(Modifier.isPrivate(nativeMaterializer.getModifiers()));
        assertFalse(Modifier.isStatic(nativeMaterializer.getModifiers()));
        assertEquals(NoiseColumn.class, nativeMaterializer.getReturnType());
        nativeMaterializer.setAccessible(true);
        nativeInput = EarthChunkGenerator.class.getDeclaredMethod("baseColumnInput",
            int.class, int.class, columnHeights);
        assertTrue(Modifier.isPrivate(nativeInput.getModifiers()));
        assertFalse(Modifier.isStatic(nativeInput.getModifiers()));
        assertEquals(TellusEarthColumnRules.Input.class, nativeInput.getReturnType());
        nativeInput.setAccessible(true);
    }

    @AfterAll
    static void restorePlatformFixture() {
        restoreProperty("tellus.gameDir", previousGameDir);
        restoreProperty("tellus.configDir", previousConfigDir);
        Thread.currentThread().setContextClassLoader(previousLoader);
    }

    @Test
    void actualNativeHelperAndResolvedSemanticColumnsMatchFrozenLiteralLoops() throws Exception {
        ParityCounts nativeCounts = new ParityCounts();
        ParityCounts semanticCounts = new ParityCounts();
        Coverage coverage = new Coverage();
        try (@SuppressWarnings("unused") var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            var plains = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            for (int depth : UNDERGROUND_DEPTHS) {
                EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(plains),
                    EarthGeneratorSettings.DEFAULT.withUndergroundDepth(depth));
                assertEquals(depth, generator.settings().undergroundDepth());
                assertTrue(generator.settings().usesTerrainShell(), "Native settings currently expose only shell terrain");
                Fixture fixture = fixture(generator);
                for (Range range : RANGES) {
                    int[] surfaces = {range.minY(), range.minY() + 1, range.minY() + range.height() / 2,
                        range.maxYExclusive() - 2, range.maxYExclusive() - 1};
                    for (int terrain : surfaces) {
                        for (int waterCase = 0; waterCase < 7; waterCase++) {
                            boolean wet = waterCase >= 2;
                            int water = switch (waterCase) {
                                case 0, 2 -> range.minY();
                                case 1, 6 -> range.maxYExclusive() - 1;
                                case 3 -> Math.max(range.minY(), terrain - 1);
                                case 4 -> terrain;
                                case 5 -> Math.min(range.maxYExclusive() - 1, terrain + 1);
                                default -> throw new AssertionError(waterCase);
                            };
                            compareColumn(generator, fixture, range, terrain, water, wet,
                                waterCase % 2 == 0 ? WaterDecision.Kind.INLAND : WaterDecision.Kind.OCEAN,
                                nativeCounts, semanticCounts, coverage);
                        }
                    }
                }
                for (int onlyY : new int[]{-1, 0, 1}) {
                    compareColumn(generator, fixture, new Range(onlyY, 1), onlyY, onlyY, false,
                        WaterDecision.Kind.NONE, nativeCounts, semanticCounts, coverage);
                }
            }
        }
        assertEquals(EXPECTED_COLUMNS, nativeCounts.columns, "The bounded native matrix ran completely");
        assertEquals(EXPECTED_COLUMNS, semanticCounts.columns, "Every native case also crossed the semantic kernel");
        assertEquals(EXPECTED_COLUMNS, coverage.resolverCalls, "The resolved kernel invoked the supplied snapshot resolver");
        assertEquals(413, coverage.minimumTerrainColumns, "Accessor minimum equals terrain, including single-cell ranges");
        assertEquals(413, coverage.explicitBedrockTopColumns, "Minimum terrain retains an explicit BEDROCK top at semantic depth zero");
        assertTrue(coverage.explicitAirCells > 0, "Known AIR below support is materialized explicitly");
        assertTrue(coverage.untouchedSkyCells > 0, "Sky stays in the initialized AIR array without semantic writes");
        assertTrue(coverage.deepslateCells > 0, "The native deepslate boundary is exercised");
        assertTrue(coverage.stoneCells > 0);
        assertTrue(coverage.waterCells > 0);
        nativeCounts.verify("EARTH_BASE_NATIVE_PARITY");
        semanticCounts.verify("EARTH_BASE_SEMANTIC_PARITY");
    }

    @Test
    void realFactoryChecksItsProfileAndMissingResolvedInputsBeforeSourceQueries() {
        try (@SuppressWarnings("unused") var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            var plains = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(plains), EarthGeneratorSettings.DEFAULT);
            Fixture fixture = fixture(generator);
            TellusGenerationContext generation = new TellusGenerationContext(42L, fixture.scale(), generator.getSeaLevel(),
                generator.getMinY(), Math.addExact(generator.getMinY(), generator.getGenDepth()), fixture.geometry());
            TellusEarthColumnDecisionKernel kernel = generator.semanticBaseColumnKernel();
            assertNotNull(kernel);
            var request = fixture.sample().request();
            assertEquals(DecisionStatus.UNKNOWN, kernel.decide(generation, fixture.coordinate(),
                TellusSample.unknown(request, "no source fixture"), null).status());
            var queuedRequest = TellusSampleRequest.fromContext(fixture.coordinate(), request.purpose(),
                SamplingResolution.nonBlocking(fixture.scale()), fixture.geometry(), generation.worldSeed());
            assertEquals(DecisionStatus.QUEUED, kernel.decide(generation, fixture.coordinate(),
                TellusSample.queued(queuedRequest, "base-parity-fixture", "r1", "pending fixture"), null).status());
            // PARTIAL enters the actual native resolver, whose incomplete-elevation/water guard
            // returns before sampleCoverClass/resolveFastColumnHeights can read any GIS source.
            TellusSample partial = TellusSample.partial(request, "no resolved elevation or water");
            ColumnDecision unavailable = kernel.decide(generation, fixture.coordinate(), partial, null);
            assertEquals(DecisionStatus.UNKNOWN, unavailable.status());
            assertEquals(List.of("native Earth column unavailable"), unavailable.diagnostics());
            TellusGenerationContext differentProfile = new TellusGenerationContext(42L, fixture.scale(),
                generator.getSeaLevel(), generation.minY() + 1, generation.maxYExclusive(), fixture.geometry());
            var error = assertThrows(IllegalArgumentException.class,
                () -> kernel.decide(differentProfile, fixture.coordinate(), partial, null));
            assertEquals("native Earth source context differs from generation profile", error.getMessage());
        }
    }

    private static void compareColumn(EarthChunkGenerator generator, Fixture fixture, Range range,
                                      int terrain, int water, boolean wet, WaterDecision.Kind wetKind,
                                      ParityCounts nativeCounts, ParityCounts semanticCounts, Coverage coverage)
        throws Exception {
        String description = "range=" + range + " terrain=" + terrain + " water=" + water
            + " hasWater=" + wet + " depth=" + generator.settings().undergroundDepth();
        Object heights = columnHeightsConstructor.newInstance(terrain, water, wet);
        NoiseColumn actual = (NoiseColumn) nativeMaterializer.invoke(generator, range.minY(), range.height(), heights);
        assertNotNull(actual, description);
        BlockState[] expected = frozenBaseColumn(generator, range, terrain, water, wet);
        nativeCounts.compare(expected, actual, range, description);

        TellusEarthColumnRules.Input input = (TellusEarthColumnRules.Input) nativeInput.invoke(generator,
            range.minY(), range.maxYExclusive(), heights);
        int support = Math.max(range.minY(), terrain - generator.settings().undergroundDepth());
        assertEquals(new TellusEarthColumnRules.Input(range.minY(), range.maxYExclusive(), terrain,
            wet ? OptionalInt.of(water) : OptionalInt.empty(), generator.settings().usesTerrainShell(),
            generator.getMinY(), generator.settings().undergroundDepth(),
            TellusCaveDepthMapper.actualDeepslateBoundaryY(terrain, support + 1, generator.getSeaLevel())),
            input, "The semantic input must come from the actual native settings/range boundary: " + description);
        TellusGenerationContext generation = new TellusGenerationContext(42L, fixture.scale(), generator.getSeaLevel(),
            range.minY(), range.maxYExclusive(), fixture.geometry());
        TellusEarthColumnSnapshot snapshot = TellusEarthColumnSnapshot.baseColumn(generation, fixture.coordinate(),
            input, wet ? wetKind : WaterDecision.Kind.NONE, BiomeDecision.defaultBiome()).orElseThrow();
        assertTrue(snapshot.completeLayers(), description);
        AtomicInteger resolverCalls = new AtomicInteger();
        TellusEarthColumnDecisionKernel kernel = TellusEarthColumnDecisionKernel.resolvedColumns((g, c, sample, koppen) -> {
            resolverCalls.incrementAndGet();
            assertEquals(generation, g);
            assertEquals(fixture.coordinate(), c);
            return Optional.of(snapshot);
        });
        ColumnDecision decision = kernel.decide(generation, fixture.coordinate(), fixture.sample(), null);
        assertEquals(1, resolverCalls.get(), description);
        coverage.resolverCalls += resolverCalls.get();
        assertEquals(DecisionStatus.AVAILABLE, decision.status(), description);
        assertEquals(terrain, decision.terrainY().orElseThrow(), description);
        assertEquals(wet ? wetKind : WaterDecision.Kind.NONE, decision.water().kind(), description);
        var result = new TellusColumnMaterializer().materializeCells(
            WriteContext.canonical(fixture.coordinate(), generation, "earth-base-native-parity"), decision);
        assertEquals(WriteStatus.WRITTEN, result.status(), description + " diagnostics=" + result.diagnostics());
        BlockState[] semantic = new BlockState[range.height()];
        Arrays.fill(semantic, Blocks.AIR.defaultBlockState());
        boolean[] written = new boolean[range.height()];
        for (SemanticColumnCell cell : result.mappedWrites()) {
            int index = cell.y() - range.minY();
            assertTrue(index >= 0 && index < semantic.length, description + " semantic Y=" + cell.y());
            assertFalse(written[index], description + " duplicate semantic Y=" + cell.y());
            written[index] = true;
            semantic[index] = nativeState(cell.material());
        }
        assertEquals(terrain - range.minY() + 1 + (wet ? Math.max(0, water - terrain) : 0),
            result.mappedWrites().size(), "Only known body and water cells are written: " + description);
        for (int index = 0; index < range.height(); index++) {
            int y = range.minY() + index;
            if (y <= terrain) {
                assertTrue(written[index], description + " missing known body Y=" + y);
                if (y < support) {
                    assertEquals(Blocks.AIR.defaultBlockState(), semantic[index], description + " explicit AIR Y=" + y);
                    coverage.explicitAirCells++;
                }
            } else if (!wet || y > water) {
                assertFalse(written[index], description + " invented sky write Y=" + y);
                coverage.untouchedSkyCells++;
            }
            if (expected[index].is(Blocks.DEEPSLATE)) coverage.deepslateCells++;
            if (expected[index].is(Blocks.STONE)) coverage.stoneCells++;
            if (expected[index].is(Blocks.WATER)) coverage.waterCells++;
        }
        assertEquals(Blocks.BEDROCK.defaultBlockState(), actual.getBlock(support), description + " shell support");
        if (terrain == range.minY()) {
            coverage.minimumTerrainColumns++;
            assertEquals(SemanticMaterial.BEDROCK, decision.surface().top(), description + " explicit bedrock surface");
            assertEquals(0, decision.surface().depth(), description + " base snapshot excludes surface filler");
            assertEquals(Blocks.BEDROCK.defaultBlockState(), semantic[terrain - range.minY()], description);
            coverage.explicitBedrockTopColumns++;
        }
        semanticCounts.compare(semantic, actual, range, description);
    }

    /** Literal pre-extraction loops; do not replace with a rule/materializer-derived oracle. */
    private static BlockState[] frozenBaseColumn(EarthChunkGenerator generator, Range range,
                                                 int surface, int waterTop, boolean hasWater) {
        int minY = range.minY();
        BlockState[] states = new BlockState[range.height()];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        int surfaceIndex = surface - minY;
        if (generator.settings().usesTerrainShell()) {
            int supportBottomY = Math.max(minY, surface - generator.settings().undergroundDepth());
            int deepslateBoundaryY = TellusCaveDepthMapper.actualDeepslateBoundaryY(surface,
                supportBottomY + 1, generator.getSeaLevel());
            for (int y = supportBottomY; y <= surface; y++) {
                int index = y - minY;
                if (index >= 0 && index < states.length) {
                    states[index] = y == supportBottomY ? Blocks.BEDROCK.defaultBlockState()
                        : y < deepslateBoundaryY ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
                }
            }
        } else {
            for (int i = 0; i <= surfaceIndex; i++) {
                if (i >= 0 && i < states.length) {
                    int y = minY + i;
                    states[i] = y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
                }
            }
            int bedrockIndex = generator.getMinY() - minY;
            if (bedrockIndex >= 0 && bedrockIndex < states.length) {
                states[bedrockIndex] = Blocks.BEDROCK.defaultBlockState();
            }
        }
        if (hasWater) {
            int waterIndex = waterTop - minY;
            for (int ix = surfaceIndex + 1; ix <= waterIndex; ix++) {
                if (ix >= 0 && ix < states.length) {
                    states[ix] = Blocks.WATER.defaultBlockState();
                }
            }
        }
        return states;
    }

    private static Fixture fixture(EarthChunkGenerator generator) {
        double scale = generator.settings().worldScale();
        GeometryIdentity geometry = GeometryIdentity.forProvider("tellus:earth", "base-parity-v1",
            "earth-projection-v1", "earth-noise-v1", scale);
        SphereWorldModel model = SphereWorldModel.forWorldScale(scale);
        var geo = new SphereWorldModel.GeoPoint(46.0, 12.0, 0.0);
        TellusCoordinateContext coordinate = TellusCoordinateContextFactory.fromGeo(model, geo,
            new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo, scale)));
        TellusSampleRequest request = TellusSampleRequest.fromContext(coordinate, SamplingPurpose.TERRAIN_COLUMN,
            SamplingResolution.exact(scale), geometry, 42L);
        // Deliberately unrelated source heights: the resolved kernel must retain the native integer heights.
        TellusSample sample = new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 1000000.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 10, 10), TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("base-parity-fixture", "r1", true, "resolved native heights"));
        return new Fixture(scale, geometry, coordinate, sample);
    }

    private static BlockState nativeState(SemanticMaterial material) {
        return switch (material) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case BEDROCK -> Blocks.BEDROCK.defaultBlockState();
            case DEEPSLATE -> Blocks.DEEPSLATE.defaultBlockState();
            case STONE -> Blocks.STONE.defaultBlockState();
            case WATER -> Blocks.WATER.defaultBlockState();
            default -> throw new AssertionError("Unexpected base-column material " + material);
        };
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private record Range(int minY, int height) {
        int maxYExclusive() { return minY + height; }
    }

    private record Fixture(double scale, GeometryIdentity geometry, TellusCoordinateContext coordinate, TellusSample sample) { }

    private static final class Coverage {
        int resolverCalls;
        int minimumTerrainColumns;
        int explicitBedrockTopColumns;
        long explicitAirCells;
        long untouchedSkyCells;
        long deepslateCells;
        long stoneCells;
        long waterCells;
    }

    private static final class ParityCounts {
        int columns;
        long comparedBlocks;
        long mismatchBlocks;
        String firstMismatch;

        void compare(BlockState[] expected, NoiseColumn actual, Range range, String description) {
            assertEquals(range.height(), expected.length, description);
            for (int index = 0; index < expected.length; index++) {
                int y = range.minY() + index;
                BlockState state = actual.getBlock(y);
                comparedBlocks++;
                if (!expected[index].equals(state)) {
                    mismatchBlocks++;
                    if (firstMismatch == null) firstMismatch = description + " Y=" + y
                        + " expected=" + expected[index] + " actual=" + state;
                }
            }
            for (int y : new int[]{range.minY() - 1, range.maxYExclusive(), Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                assertEquals(Blocks.AIR.defaultBlockState(), actual.getBlock(y), description + " outside Y=" + y);
            }
            columns++;
        }

        void verify(String marker) {
            assertTrue(comparedBlocks > EXPECTED_COLUMNS, "Every complete column state was checked");
            assertEquals(0L, mismatchBlocks, firstMismatch);
            System.out.println(marker + " columns=" + columns + " mismatchBlocks=" + mismatchBlocks);
        }
    }
}
