package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.caves.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Generator-owned controlled native sources, not live GIS/server/save or source-atomic acceptance. */
class CubeNetOwnedUndergroundStageTest {
    @TempDir static Path directory;
    static final int MIN = -64, HEIGHT = 160;
    static final long SEED = 42;
    static final double SCALE = 1000;
    @BeforeAll static void initialize() throws Exception {
        CubeNetNativeUndergroundStageTest.root = directory;
        CubeNetNativeUndergroundStageTest.init();
    }
    @AfterAll static void restore() throws Exception { CubeNetNativeUndergroundStageTest.cleanup(); }

    static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    static void bind(CubeNetChunkGenerator gen, long seed) throws Exception {
        field(CubeNetChunkGenerator.class, "worldSeed").setLong(gen, seed);
        ((SphereTerrainMaterializer)field(CubeNetChunkGenerator.class, "materializer").get(gen)).setWorldSeed(seed);
        for (var face : (SphereFaceChunkGenerator[])field(CubeNetChunkGenerator.class, "faces").get(gen)) face.setWorldSeed(seed);
    }
    static CubeNetChunkGenerator generator(SphereChunkSampler.PointSampler sampler, SphereSingularityPolicy.Config config) throws Exception {
        var fixed = new FixedBiomeSource(SphereFaceNativeBiomeKernelBridgeTest.biomes.getOrThrow(Biomes.PLAINS));
        Constructor<CubeNetChunkGenerator> ctor = CubeNetChunkGenerator.class.getDeclaredConstructor(BiomeSource.class,
            FixedBiomeSource.class, double.class, int.class, int.class, int.class, SphereSingularityPolicy.Config.class,
            SphereChunkSampler.PointSampler.class, int.class, int.class, int.class, boolean.class);
        ctor.setAccessible(true);
        var gen = ctor.newInstance(fixed, fixed, SCALE, 64, MIN, HEIGHT, config, sampler, 0, 0, 0, false);
        bind(gen, SEED); return gen;
    }
    static ChunkPos position(CubeNetChunkGenerator gen, CubeNetLayout.Face face, boolean corner) {
        double size = gen.model().config().faceBlocks();
        var p = gen.adapter().layout().toPlanar(new CubeNetLayout.FacePoint(face,
            corner ? 8 : size / 2 + 16 * 17 + 8, corner ? 8 : size / 2 - 16 * 23 + 8));
        return new ChunkPos((int)Math.floor(p.x() / 16), (int)Math.floor(p.z() / 16));
    }
    static ProtoChunk chunk(ChunkPos pos, int height) {
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(MIN, height),
            SphereFaceNativeBiomeKernelBridgeTest.registry, null);
        for (int y = MIN; y < MIN + height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var state = y == MIN ? Blocks.BEDROCK.defaultBlockState()
                : y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : y <= 84 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (x == 2 && z == 3 && y > 0 && y < 20) state = Blocks.GLASS.defaultBlockState();
            chunk.setBlockState(new BlockPos(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z), state, false);
        }
        chunk.getPostProcessing()[0] = new it.unimi.dsi.fastutil.shorts.ShortArrayList(new short[]{7, 7, 99});
        chunk.setUnsaved(false); return chunk;
    }
    static SphereChunkSampler.SurfaceData data(SphereWorldModel model, SphereWorldModel.GeoPoint point, boolean water) {
        var b = model.blockForGeo(point);
        double elevation = (10 + Math.floorMod(b.blockX(), 5) - Math.floorMod(b.blockZ(), 3)) * SCALE;
        if (water && Math.floorMod(b.blockX(), 7) == 0)
            return new SphereChunkSampler.SurfaceData(elevation, 80, elevation + 2 * SCALE,
                SphereChunkSampler.SurfaceData.WaterKind.INLAND);
        return new SphereChunkSampler.SurfaceData(elevation, 30);
    }
    static CubeNetNativeUndergroundStage.Prepared prepared(CubeNetChunkGenerator gen, ProtoChunk chunk) {
        var result = gen.prepareNativeUnderground(true, chunk, null, SphereFaceNativeBiomeKernelBridgeTest.registry, SEED);
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.READY, result.status(), result.diagnostics().toString());
        return result.prepared().orElseThrow();
    }
    static void nativeApply(CubeNetChunkGenerator gen, ProtoChunk chunk, UndergroundStagePlan.Flags flags) {
        var address = gen.address(chunk.getPos()).orElseThrow();
        var columns = gen.undergroundColumns(chunk.getPos(), address);
        new CubeNetSphericalCaveSampler().apply(gen.model(), address, new CubeNetSphericalCaveField(SEED), chunk,
            gen.getMinY(), gen.getSeaLevel(), flags.applyCaves(), flags.cavesReachSurface(), flags.applyOreVeins(),
            flags.applyGeologicalStonePatches(), columns.surfaceY(), columns.surfaceSampler(), columns.floodGuardY(),
            columns.generationFloorY(), List.of(), (written, pos, state, fluid) -> {
                MinecraftVersionCompat.setBlockState(written, pos, state);
                if (fluid) MinecraftVersionCompat.markPosForPostProcessing((ProtoChunk)written, pos);
            });
    }
    static void equivalent(ProtoChunk expected, ProtoChunk actual) {
        assertEquals(CubeNetNativeUndergroundStageTest.before(expected).blocks(), CubeNetNativeUndergroundStageTest.before(actual).blocks());
        var a = actual.getPostProcessing(); var e = expected.getPostProcessing();
        for (int i = 0; i < a.length; i++) {
            if (e[i] == null) assertNull(a[i]); else assertArrayEquals(e[i].toShortArray(), a[i].toShortArray());
        }
    }

    @Test void ownedSnapshotsMatchOriginalNativeSamplerAcrossAllFacesAndSourceColdReplay() throws Exception {
        int compared = 0, deltas = 0, negative = 0, rotated = 0;
        var model = SphereWorldModel.forWorldScale(SCALE);
        for (var face : CubeNetLayout.Face.values()) for (boolean water : new boolean[]{false, true}) {
            AtomicInteger reads = new AtomicInteger(); List<SphereWorldModel.GeoPoint> trace = new ArrayList<>();
            var gen = generator((point, resolution) -> {
                assertEquals(SCALE, resolution); reads.incrementAndGet(); trace.add(point); return data(model, point, water);
            }, SphereSingularityPolicy.DEFAULT_CONFIG);
            var pos = position(gen, face, false); if (pos.x < 0 || pos.z < 0) negative++;
            var placement = gen.adapter().layout().placement(face); if (placement.uAxisX() != 1 || placement.vAxisZ() != 1) rotated++;
            var actual = chunk(pos, HEIGHT); var expected = chunk(pos, HEIGHT); var before = CubeNetNativeUndergroundStageTest.before(actual);
            var owned = prepared(gen, actual); var request = owned.plan().request();
            assertEquals(SEED, request.generation().worldSeed()); assertEquals(face.index(), request.address().face());
            assertEquals(400, request.surfaceHaloY().length); assertTrue(request.structureExclusions().isEmpty());
            CubeNetNativeUndergroundStageTest.restored(actual, before);
            var captureTrace = List.copyOf(trace); assertTrue(reads.get() > 0); trace.clear();
            var original = gen.undergroundColumns(pos, request.address());
            assertArrayEquals(original.surfaceY(), request.surfaceY()); assertArrayEquals(original.floodGuardY(), request.floodGuardY());
            assertArrayEquals(original.generationFloorY(), request.generationFloorY()); assertEquals(captureTrace, trace);
            assertEquals(4, CubeNetChunkGenerator.UndergroundColumns.class.getRecordComponents().length,
                "Default record retains its four-component lazy sampler boundary");
            int[] halo = request.surfaceHaloY(); halo[0]++; assertNotEquals(halo[0], request.surfaceHaloY()[0]);
            assertThrows(UnsupportedOperationException.class, () -> request.structureExclusions().add(new UndergroundStagePlan.Exclusion(0,0,0,1,1,1)));
            nativeApply(gen, expected, request.flags()); int capturedReads = reads.get();
            var result = gen.replayNativeUnderground(true, actual, owned);
            assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.WRITTEN, result.status(), result.diagnostics().toString());
            assertEquals(capturedReads, reads.get()); equivalent(expected, actual);
            compared += HEIGHT * 256; deltas += result.appliedDeltas();
        }
        assertTrue(negative > 0); assertTrue(rotated > 0); assertTrue(deltas > 0);
        System.out.println("CUBE_OWNED_UNDERGROUND chunks=12 comparedBlockStates=" + compared + " mismatchBlocks=0 sourceColdReplay=true deltas=" + deltas + " negativeFrames=" + negative + " rotatedFrames=" + rotated);
    }

    @Test void disabledNoFeaturesAndNonblockingAreTargetAndSourceCold() throws Exception {
        AtomicInteger reads = new AtomicInteger(); var gen = generator((p,r) -> { reads.incrementAndGet(); return new SphereChunkSampler.SurfaceData(10000,30); }, SphereSingularityPolicy.DEFAULT_CONFIG);
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID, gen.prepareNativeUnderground(false, null, null, null, 99).status());
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.QUEUED,
            gen.prepareNativeUnderground(true, null, null, null, 99, SamplingResolution.nonBlocking(1)).status());
        for (String name : List.of("cubeNetCaves", "cubeNetOreVeins", "cubeNetGeologicalStonePatches")) field(CubeNetChunkGenerator.class, name).setBoolean(gen, false);
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID, gen.prepareNativeUnderground(true, null, null, null, SEED).status());
        assertEquals(0, reads.get());
    }

    @Test void invalidBoundsCanonicalCapConfigAndSeedStopBeforeSources() throws Exception {
        AtomicInteger reads = new AtomicInteger(); var gen = generator((p,r) -> { reads.incrementAndGet(); return new SphereChunkSampler.SurfaceData(10000,30); }, SphereSingularityPolicy.DEFAULT_CONFIG);
        var pos = position(gen, CubeNetLayout.Face.POS_X, false);
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID,
            gen.prepareNativeUnderground(true, chunk(pos, 16), null, SphereFaceNativeBiomeKernelBridgeTest.registry, SEED).status());
        var cap = chunk(position(gen, CubeNetLayout.Face.POS_X, true), HEIGHT);
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID,
            gen.prepareNativeUnderground(true, cap, null, SphereFaceNativeBiomeKernelBridgeTest.registry, SEED).status());
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID,
            gen.prepareNativeUnderground(true, chunk(new ChunkPos(Integer.MAX_VALUE, Integer.MIN_VALUE), HEIGHT), null, SphereFaceNativeBiomeKernelBridgeTest.registry, SEED).status());
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID,
            gen.prepareNativeUnderground(true, chunk(pos, HEIGHT), null, SphereFaceNativeBiomeKernelBridgeTest.registry, SEED,
                SamplingResolution.exact(1), new SphereSingularityPolicy.Config(0,0)).status());
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID,
            gen.prepareNativeUnderground(true, chunk(pos, HEIGHT), null, SphereFaceNativeBiomeKernelBridgeTest.registry, SEED + 1).status());
        assertEquals(0, reads.get());
    }

    @Test void customSerializedCapConfigIsRetainedAndValidatedDuringReplay() throws Exception {
        var model = SphereWorldModel.forWorldScale(SCALE); AtomicInteger reads = new AtomicInteger();
        var config = new SphereSingularityPolicy.Config(0,0);
        var gen = generator((p,r) -> { reads.incrementAndGet(); return data(model,p,false); }, config);
        var pos = position(gen, CubeNetLayout.Face.POS_X, true); var actual = chunk(pos,HEIGHT); var expected = chunk(pos,HEIGHT);
        assertEquals(SphereSingularityPolicy.Status.EXCLUDED, new SphereSingularityPolicy(model,SphereSingularityPolicy.DEFAULT_CONFIG).status(gen.address(pos).orElseThrow()));
        var owned = prepared(gen, actual); nativeApply(gen, expected, owned.plan().request().flags());
        var adapter = new CubeNetNativeUndergroundStage();
        var standalone = adapter.prepare(true, actual, owned.plan().request(), SphereFaceNativeBiomeKernelBridgeTest.registry, config);
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.READY, standalone.status());
        var before = CubeNetNativeUndergroundStageTest.before(actual);
        assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.INVALID, adapter.replay(true,actual,standalone.prepared().orElseThrow(),SphereSingularityPolicy.DEFAULT_CONFIG).status());
        CubeNetNativeUndergroundStageTest.restored(actual,before);
        int count = reads.get(); assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.WRITTEN,gen.replayNativeUnderground(true,actual,owned).status());
        assertEquals(count,reads.get()); equivalent(expected,actual);
    }

    @Test void changingSeedDuringCaptureRejectsSnapshotAndLeavesTargetUntouched() throws Exception {
        AtomicReference<CubeNetChunkGenerator> active = new AtomicReference<>(); AtomicInteger reads = new AtomicInteger();
        var gen = generator((p,r) -> {
            if (reads.incrementAndGet() == 1) try { bind(active.get(), SEED+1); } catch (Exception failure) { throw new RuntimeException(failure); }
            return new SphereChunkSampler.SurfaceData(10000,30);
        }, SphereSingularityPolicy.DEFAULT_CONFIG); active.set(gen);
        var c = chunk(position(gen,CubeNetLayout.Face.POS_X,false),HEIGHT); var before = CubeNetNativeUndergroundStageTest.before(c);
        var result = gen.prepareNativeUnderground(true,c,null,SphereFaceNativeBiomeKernelBridgeTest.registry,SEED);
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID,result.status()); assertTrue(result.prepared().isEmpty());
        assertTrue(reads.get()>0); CubeNetNativeUndergroundStageTest.restored(c,before);
    }

    @Test void seedChangesAndForeignGeneratorReplayAreRejectedBeforeWritesOrSourceReads() throws Exception {
        AtomicInteger reads = new AtomicInteger(); SphereChunkSampler.PointSampler sampler = (p,r) -> { reads.incrementAndGet(); return new SphereChunkSampler.SurfaceData(10000,30); };
        var gen = generator(sampler,SphereSingularityPolicy.DEFAULT_CONFIG); var other = generator(sampler,SphereSingularityPolicy.DEFAULT_CONFIG);
        var c = chunk(position(gen,CubeNetLayout.Face.NEG_X,false),HEIGHT); var owned = prepared(gen,c); var before = CubeNetNativeUndergroundStageTest.before(c); int count = reads.get();
        assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.INVALID,other.replayNativeUnderground(true,c,owned).status());
        bind(gen,SEED+1); assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.INVALID,gen.replayNativeUnderground(true,c,owned).status());
        assertEquals(count,reads.get()); CubeNetNativeUndergroundStageTest.restored(c,before);
    }
}
