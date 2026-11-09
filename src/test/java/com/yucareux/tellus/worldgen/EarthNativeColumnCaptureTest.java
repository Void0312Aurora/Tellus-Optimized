package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.decision.BiomeDecision;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.WaterDecision;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ProtoChunk/cache-only, caller-resolved input tests of the capture boundary and native palettes.
 * These fixtures do not drive GIS resolution or fillFromNoise, or assert final-world parity.
 */
class EarthNativeColumnCaptureTest {
    private static final int MIN_Y = -32;
    private static final int HEIGHT = 64;
    private static final double SCALE = 10000.0;
    private static final TellusGenerationContext GENERATION =
        new TellusGenerationContext(0L, SCALE, 0, MIN_Y, MIN_Y + HEIGHT);
    private static final BiomeDecision BIOME = new BiomeDecision("minecraft:plains", true);
    @TempDir static Path gameDirectory;
    private static Registry<Biome> biomes;
    private static net.minecraft.core.Holder<Biome> plains;
    private static Constructor<?> protoConstructor;
    private static TellusCoordinateContext coordinate;
    private static int x;
    private static int z;
    private static String previousGameDir;
    private static String previousConfigDir;
    private static ClassLoader previousLoader;

    @BeforeAll
    static void bootstrap() throws Exception {
        previousGameDir = System.getProperty("tellus.gameDir");
        previousConfigDir = System.getProperty("tellus.configDir");
        previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(EarthNativeColumnCaptureTest.class.getClassLoader());
        System.setProperty("tellus.gameDir", gameDirectory.toString());
        System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            Class.forName(EarthChunkGenerator.class.getName(), true, EarthNativeColumnCaptureTest.class.getClassLoader());
        }
        plains = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        Registry.register(biomes, Biomes.PLAINS, plains.value());
        protoConstructor = ProtoChunk.class.getDeclaredConstructor(ChunkPos.class, UpgradeData.class,
            LevelHeightAccessor.class, Registry.class,
            Class.forName("net.minecraft.world.level.levelgen.blending.BlendingData"));
        protoConstructor.setAccessible(true);
        var geo = new SphereWorldModel.GeoPoint(1.0, -1.0, 0.0);
        var earth = EarthCompatibilityPoint.fromGeoPoint(geo, SCALE);
        coordinate = TellusCoordinateContextFactory.fromGeo(SphereWorldModel.forWorldScale(SCALE), geo,
            new EarthOwnerView(earth));
        x = Math.toIntExact(earth.floorBlockX());
        z = Math.toIntExact(earth.floorBlockZ());
    }

    @AfterAll
    static void restoreFixture() {
        restore("tellus.gameDir", previousGameDir);
        restore("tellus.configDir", previousConfigDir);
        Thread.currentThread().setContextClassLoader(previousLoader);
    }

    @Test
    void claimUsesExactObjectIdentityAndIsOneShot() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        assertSame(GENERATION, lease.generation());
        assertThrows(IllegalStateException.class, () -> open(slot, target));
        assertNull(lease.claim(chunk()), "Same chunk position is not target identity");
        var builder = lease.claim(target);
        assertNotNull(builder);
        assertTrue(builder.matches(x, z));
        assertFalse(builder.matches(x + 1, z));
        assertNull(lease.claim(target));
        lease.close();
        lease.close();
        assertNull(slot.get());
        assertNull(lease.claim(target));
        assertFailure(lease, java.util.concurrent.CancellationException.class);
    }

    @Test
    void concurrentOpenHasOneWinnerAndOldClosePreservesReplacement() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> tryOpen(gate, slot, target));
            var b = executor.submit(() -> tryOpen(gate, slot, target));
            gate.countDown();
            var first = a.get(5, TimeUnit.SECONDS);
            var second = b.get(5, TimeUnit.SECONDS);
            assertTrue((first == null) != (second == null));
            var winner = first != null ? first : second;
            assertSame(winner, slot.get());
            winner.close();
            var replacement = open(slot, target);
            winner.close();
            assertSame(replacement, slot.get());
            // Simulate an externally replaced slot, then close its stale predecessor.
            assertTrue(slot.compareAndSet(replacement, null));
            var newest = open(slot, target);
            replacement.close();
            assertSame(newest, slot.get());
            newest.close();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void cursorSnapshotKeepsExactBodyWaterAndSurfacePropertiesWithoutSky() throws Exception {
        ProtoChunk target = chunk();
        fill(target, MIN_Y, 15, Blocks.STONE.defaultBlockState());
        put(target, MIN_Y, Blocks.BEDROCK.defaultBlockState());
        put(target, -20, Blocks.AIR.defaultBlockState());
        put(target, -16, Blocks.BEDROCK.defaultBlockState());
        fill(target, 16, 18, Blocks.WATER.defaultBlockState());
        put(target, 19, Blocks.GOLD_BLOCK.defaultBlockState()); // Outside the observed interval.
        var water = new WaterDecision(WaterDecision.Kind.OCEAN, OptionalInt.of(18));
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var builder = lease.claim(target);
        builder.before(target, null, x, z, 15, water, BIOME);
        var top = Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true);
        var context = new EarthChunkGenerator.ResolvedSurfaceContext(null, null, top,
            true, true, false, 2, BIOME);
        builder.surfaceContext(context);
        put(target, 15, top);
        builder.after(target, null, x, z, 15);
        lease.complete(builder);
        var snapshot = lease.result().toCompletableFuture().join();
        assertEquals(51, snapshot.before().size());
        assertEquals(18, snapshot.maxYInclusive());
        assertEquals(MIN_Y, snapshot.minY());
        assertEquals(TellusEarthNativeColumnCapture.Stage.POST_SURFACE_PRE_SNOW_AND_SHELL_SKIN, snapshot.stage());
        assertSame(context, snapshot.surfaceContext().orElseThrow());
        assertEquals(Blocks.AIR.defaultBlockState(), snapshot.before().get(-20 - MIN_Y));
        assertEquals(Blocks.BEDROCK.defaultBlockState(), snapshot.before().get(-16 - MIN_Y));
        assertEquals(top, snapshot.after().get(15 - MIN_Y));
        assertTrue(snapshot.semanticBefore().isPresent());
        assertTrue(snapshot.semanticAfter().isEmpty(), "Nondefault snowy property must not be approximated");
        put(target, MIN_Y, Blocks.DIAMOND_BLOCK.defaultBlockState());
        assertEquals(Blocks.BEDROCK.defaultBlockState(), snapshot.before().get(0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.after().set(0, top));
        var mutableBefore = new ArrayList<>(snapshot.before());
        var copy = new TellusEarthNativeColumnCapture.Snapshot(GENERATION, coordinate, 15, water, BIOME,
            mutableBefore, snapshot.after(), snapshot.semanticBefore(), snapshot.semanticAfter(), snapshot.surfaceContext());
        mutableBefore.set(0, top);
        assertEquals(Blocks.BEDROCK.defaultBlockState(), copy.before().get(0));
    }

    @Test
    void writerReadsUnflushedPalettesAndCompletesOnlyAfterReleaseWithReentrantReplacement() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var builder = lease.claim(target);
        var writer = new EarthChunkGenerator.ChunkSectionWriter(target.getSections(), MIN_Y);
        try {
            writer.fillColumnConstant(x & 15, z & 15, MIN_Y, 17, Blocks.STONE.defaultBlockState());
            assertTrue(target.getSections()[0].hasOnlyAir(), "Counts intentionally stale before writer finish");
            builder.before(target, writer, x, z, 17, WaterDecision.dry(), BIOME);
            writer.setBlock(x & 15, z & 15, 17, Blocks.BEDROCK.defaultBlockState());
            builder.after(target, writer, x, z, 17);
            assertFalse(lease.result().toCompletableFuture().isDone());
        } finally { writer.close(); }
        var next = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var observed = lease.result().thenAccept(snapshot -> {
            assertNull(slot.get(), "Auto-release precedes all observer code");
            // A distinct thread must acquire every observed section; same-thread reentrancy
            // would conceal a leaked writer palette lock.
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    for (var section : target.getSections()) {
                        section.acquire();
                        section.release();
                    }
                }).get(5, TimeUnit.SECONDS);
            } catch (Exception exception) { throw new AssertionError("writer locks leaked to observer", exception); }
            finally { executor.shutdownNow(); }
            next.set(open(slot, target));
            lease.close();
            assertSame(next.get(), slot.get());
        });
        lease.complete(builder);
        observed.toCompletableFuture().join();
        var snapshot = lease.result().toCompletableFuture().join();
        assertEquals(Blocks.STONE.defaultBlockState(), snapshot.before().get(0));
        assertEquals(Blocks.BEDROCK.defaultBlockState(), snapshot.after().get(49));
        assertTrue(snapshot.semanticAfter().isPresent());
        assertTrue(snapshot.surfaceContext().isEmpty(), "Missing source context stays distinct from known no-op");
        next.get().close();
    }

    @Test
    void knownNoOpContextAndAirOrBedrockAtTopRemainExactSemantics() throws Exception {
        for (var top : List.of(Blocks.AIR.defaultBlockState(), Blocks.BEDROCK.defaultBlockState())) {
            ProtoChunk target = chunk();
            fill(target, MIN_Y, 0, Blocks.STONE.defaultBlockState());
            put(target, -8, Blocks.BEDROCK.defaultBlockState());
            put(target, 0, top);
            var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
            var lease = open(slot, target);
            var builder = lease.claim(target);
            builder.before(target, null, x, z, 0, WaterDecision.dry(), BIOME);
            var noOp = new EarthChunkGenerator.ResolvedSurfaceContext(null, null, null, false, false, false, 0, BIOME);
            builder.surfaceContext(noOp);
            builder.after(target, null, x, z, 0);
            lease.complete(builder);
            var snapshot = lease.result().toCompletableFuture().join();
            assertEquals(snapshot.before(), snapshot.after());
            assertSame(noOp, snapshot.surfaceContext().orElseThrow());
            assertTrue(snapshot.semanticAfter().isPresent());
            assertEquals(top.isAir() ? SemanticMaterial.AIR : SemanticMaterial.BEDROCK,
                snapshot.semanticAfter().orElseThrow().surface().top());
        }
    }

    @Test
    void defaultWaterIsRepresentableButFlowingWaterAndUnsupportedBlocksStayRawOnly() throws Exception {
        for (var state : List.of(Blocks.WATER.defaultBlockState(),
            Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 3),
            Blocks.DIAMOND_BLOCK.defaultBlockState())) {
            ProtoChunk target = chunk();
            fill(target, MIN_Y, 0, Blocks.STONE.defaultBlockState());
            put(target, 1, state);
            var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
            var lease = open(slot, target);
            var builder = lease.claim(target);
            var water = new WaterDecision(WaterDecision.Kind.INLAND, OptionalInt.of(1));
            builder.before(target, null, x, z, 0, water, BIOME);
            builder.after(target, null, x, z, 0);
            lease.complete(builder);
            var snapshot = lease.result().toCompletableFuture().join();
            assertEquals(state, snapshot.before().get(33));
            assertEquals(state == Blocks.WATER.defaultBlockState(), snapshot.semanticBefore().isPresent());
            assertEquals(snapshot.semanticBefore().isPresent(), snapshot.semanticAfter().isPresent());
        }
    }

    @Test
    void captureRuntimeFailureDoesNotInterruptNativeWritesOrProduceFalseSuccess() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var builder = lease.claim(target);
        assertDoesNotThrow(() -> builder.before(target, null, x, z, MIN_Y - 1, WaterDecision.dry(), BIOME));
        put(target, 0, Blocks.GOLD_BLOCK.defaultBlockState());
        assertDoesNotThrow(() -> builder.after(target, null, x, z, MIN_Y - 1));
        assertDoesNotThrow(() -> lease.complete(builder));
        assertFailure(lease, IllegalArgumentException.class);
        assertNull(slot.get());
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), target.getBlockState(new BlockPos(x, 0, z)));
    }

    @Test
    void cancellationSuppressesPreparedSnapshotAndObserverExceptionsStayDependent() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var builder = ready(lease, target);
        lease.close();
        lease.complete(builder);
        assertFailure(lease, java.util.concurrent.CancellationException.class);
        var next = open(slot, target);
        var nextBuilder = ready(next, target);
        var observerFailure = new IllegalStateException("observer");
        var dependent = next.result().thenApply(snapshot -> { throw observerFailure; });
        assertDoesNotThrow(() -> next.complete(nextBuilder));
        assertNotNull(next.result().toCompletableFuture().join());
        assertSame(observerFailure, assertThrows(CompletionException.class,
            () -> dependent.toCompletableFuture().join()).getCause());
    }

    @Test
    void nativeFailureKeepsOriginalCauseDespiteIncompleteClaimAndDependentFailure() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var nativeFailure = new IllegalStateException("original native generation failure");
        lease.result().whenComplete((snapshot, failure) -> { throw new IllegalArgumentException("observer"); });
        assertDoesNotThrow(() -> lease.failed(nativeFailure));
        assertDoesNotThrow(() -> lease.complete(null));
        assertDoesNotThrow(lease::close);
        assertSame(nativeFailure, assertThrows(CompletionException.class,
            () -> lease.result().toCompletableFuture().join()).getCause());
        assertNull(slot.get());
        var incomplete = open(slot, target);
        incomplete.claim(target);
        assertDoesNotThrow(() -> incomplete.complete(null));
        assertFailure(incomplete, IllegalStateException.class);
    }

    @Test
    void fatalErrorDuringCaptureDeliveryPropagatesRatherThanBecomingRuntimeFailure() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var builder = ready(lease, target);
        var fatal = new AssertionError("fatal capture fixture");
        Field before = TellusEarthNativeColumnCapture.Builder.class.getDeclaredField("before");
        before.setAccessible(true);
        before.set(builder, new AbstractList<BlockState>() {
            @Override public BlockState get(int index) { throw fatal; }
            @Override public int size() { throw fatal; }
        });
        try { assertSame(fatal, assertThrows(AssertionError.class, () -> lease.complete(builder))); }
        finally { lease.close(); }
    }

    @Test
    void actualNativeSurfaceOverloadsRecordTheirResolvedContextOnBothBackends() throws Exception {
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            var generator = new EarthChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(plains),
                EarthGeneratorSettings.DEFAULT);
            for (boolean thin : new boolean[]{false, true}) {
                for (boolean sectionBackend : new boolean[]{false, true}) {
                    ProtoChunk target = chunk();
                    fill(target, MIN_Y, 0, Blocks.STONE.defaultBlockState());
                    fill(target, 1, 4, Blocks.WATER.defaultBlockState());
                    var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
                    var lease = open(slot, target);
                    var builder = lease.claim(target);
                    var writer = sectionBackend
                        ? new EarthChunkGenerator.ChunkSectionWriter(target.getSections(), MIN_Y) : null;
                    try {
                        builder.before(target, writer, x, z, 0,
                            new WaterDecision(WaterDecision.Kind.OCEAN, OptionalInt.of(4)), BIOME);
                        invokeSurface(generator, target, writer, builder, thin);
                        builder.after(target, writer, x, z, 0);
                    } finally { if (writer != null) writer.close(); }
                    lease.complete(builder);
                    var snapshot = lease.result().toCompletableFuture().join();
                    var context = snapshot.surfaceContext().orElseThrow();
                    assertEquals(thin, context.thinShell());
                    assertTrue(context.underwater());
                    assertFalse(context.badlands());
                    assertNotNull(context.palette());
                    assertEquals(BIOME, context.biome());
                    assertEquals(context.top(), snapshot.after().get(-MIN_Y));
                    for (int y = 1; y <= 4; y++) {
                        assertEquals(Blocks.WATER.defaultBlockState(), snapshot.after().get(y - MIN_Y));
                    }
                    assertEquals(Blocks.STONE.defaultBlockState(), snapshot.before().get(-MIN_Y));
                    assertTrue(snapshot.semanticBefore().isPresent());
                    assertTrue(snapshot.semanticAfter().isPresent());
                }
            }
        }
    }

    private static void invokeSurface(EarthChunkGenerator generator, ProtoChunk target,
        EarthChunkGenerator.ChunkSectionWriter writer, TellusEarthNativeColumnCapture.Builder builder,
        boolean thin) throws Exception {
        java.lang.reflect.Method selected = null;
        for (var method : EarthChunkGenerator.class.getDeclaredMethods()) {
            var types = method.getParameterTypes();
            if (method.getName().equals(thin ? "applyThinShellSurface" : "applySurface")
                && types[types.length - 1] == TellusEarthNativeColumnCapture.Builder.class
                && types[0] == (writer == null ? net.minecraft.world.level.chunk.ChunkAccess.class
                    : EarthChunkGenerator.ChunkSectionWriter.class)) selected = method;
        }
        assertNotNull(selected, "Native capture-aware overload exists");
        selected.setAccessible(true);
        Class<?> profilerType = null;
        for (var nested : EarthChunkGenerator.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("SurfaceApplyProfiler")) profilerType = nested;
        }
        assertNotNull(profilerType);
        var constructor = profilerType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object profiler = constructor.newInstance();
        List<Object> arguments = new ArrayList<>();
        if (writer == null) {
            arguments.add(target);
            arguments.add(new BlockPos.MutableBlockPos());
            arguments.add(x); arguments.add(z);
        } else {
            arguments.add(writer);
            arguments.add(x); arguments.add(z);
            arguments.add(x & 15); arguments.add(z & 15);
        }
        arguments.add(0); arguments.add(MIN_Y); arguments.add(true);
        if (thin) arguments.add(false);
        arguments.add(plains);
        arguments.add(2); arguments.add(0); arguments.add(10);
        arguments.add(null); arguments.add(profiler); arguments.add(true); arguments.add(builder);
        selected.invoke(generator, arguments.toArray());
    }

    @Test
    void callbacksDoNotHoldLeaseMonitorAndTerminalLeaseDropsChunkReferences() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var builder = ready(lease, target);
        var observer = lease.result().thenRun(() -> {
            var executor = Executors.newSingleThreadExecutor();
            try { executor.submit(lease::close).get(5, TimeUnit.SECONDS); }
            catch (Exception exception) { throw new AssertionError("callback retained lease monitor", exception); }
            finally { executor.shutdownNow(); }
        });
        lease.complete(builder);
        observer.toCompletableFuture().join();
        for (String name : List.of("target", "claimed")) {
            var field = TellusEarthNativeColumnCapture.Lease.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(lease), "Completed lease drops " + name);
        }
        var cancelled = open(slot, target);
        var cancelledBuilder = cancelled.claim(target);
        cancelled.close();
        // Builder owns the target while an already-running native invocation winds down.
        assertDoesNotThrow(() -> cancelledBuilder.before(target, null, x, z, 0, WaterDecision.dry(), BIOME));
        assertDoesNotThrow(() -> cancelledBuilder.after(target, null, x, z, 0));
        cancelled.complete(cancelledBuilder);
        assertFailure(cancelled, java.util.concurrent.CancellationException.class);
    }

    private static TellusEarthNativeColumnCapture.Builder ready(TellusEarthNativeColumnCapture.Lease lease,
                                                                 ProtoChunk target) {
        var builder = lease.claim(target);
        builder.before(target, null, x, z, 0, WaterDecision.dry(), BIOME);
        builder.after(target, null, x, z, 0);
        return builder;
    }
    private static TellusEarthNativeColumnCapture.Lease open(
        AtomicReference<TellusEarthNativeColumnCapture.Lease> slot, ProtoChunk target) {
        return TellusEarthNativeColumnCapture.open(slot, target, GENERATION, coordinate);
    }
    private static TellusEarthNativeColumnCapture.Lease tryOpen(CountDownLatch gate,
        AtomicReference<TellusEarthNativeColumnCapture.Lease> slot, ProtoChunk target) throws InterruptedException {
        gate.await();
        try { return open(slot, target); }
        catch (IllegalStateException occupied) { return null; }
    }
    private static ProtoChunk chunk() throws Exception {
        return (ProtoChunk) protoConstructor.newInstance(new ChunkPos(x >> 4, z >> 4), UpgradeData.EMPTY,
            LevelHeightAccessor.create(MIN_Y, HEIGHT), biomes, null);
    }
    private static void put(ProtoChunk target, int y, BlockState state) {
        MinecraftVersionCompat.setBlockState(target, new BlockPos(x, y, z), state);
    }
    private static void fill(ProtoChunk target, int from, int through, BlockState state) {
        for (int y = from; y <= through; y++) put(target, y, state);
    }
    private static void assertFailure(TellusEarthNativeColumnCapture.Lease lease,
                                       Class<? extends Throwable> type) {
        var failure = assertThrows(CompletionException.class, () -> lease.result().toCompletableFuture().join());
        assertInstanceOf(type, failure.getCause());
    }
    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
}
