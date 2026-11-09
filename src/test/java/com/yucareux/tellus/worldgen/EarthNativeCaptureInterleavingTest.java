package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.decision.BiomeDecision;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.WaterDecision;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministically ordered lease interleavings on small real ProtoChunks and resolved inputs.
 * These tests cover the capture boundary, not GIS, full-world or default lifecycle acceptance.
 * Terminal retention is checked through results, claims and successor slots, not private fields.
 */
class EarthNativeCaptureInterleavingTest {
    private static final int MIN_Y = -32;
    private static final int HEIGHT = 64;
    private static final double SCALE = 10000.0;
    private static final TellusGenerationContext GENERATION =
        new TellusGenerationContext(0L, SCALE, 0, MIN_Y, MIN_Y + HEIGHT);
    private static final BiomeDecision BIOME = new BiomeDecision("minecraft:plains", true);
    @TempDir static Path gameDirectory;
    private static Registry<Biome> biomes;
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
        Thread.currentThread().setContextClassLoader(EarthNativeCaptureInterleavingTest.class.getClassLoader());
        System.setProperty("tellus.gameDir", gameDirectory.toString());
        System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            Class.forName(EarthChunkGenerator.class.getName(), true,
                EarthNativeCaptureInterleavingTest.class.getClassLoader());
        }
        var plains = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
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
    void cancelledClaimCanFinishNativeWritesOnOldChunkWithoutErasingSuccessor() throws Exception {
        ProtoChunk oldChunk = chunk();
        ProtoChunk successorChunk = chunk(); // Same position, different native object.
        fill(oldChunk, Blocks.STONE.defaultBlockState());
        fill(successorChunk, Blocks.DIAMOND_BLOCK.defaultBlockState());
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var old = open(slot, oldChunk);
        var claimed = new CountDownLatch(1);
        var resumeNative = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> nativeWork = executor.submit(() -> {
                var builder = old.claim(oldChunk);
                assertNotNull(builder);
                claimed.countDown();
                await(resumeNative);
                // Cancellation is deliberately before all three delayed capture calls.
                writeAndCapture(builder, oldChunk, Blocks.GOLD_BLOCK.defaultBlockState());
                old.complete(builder);
            });
            await(claimed);
            old.close();
            assertInstanceOf(CancellationException.class, failure(old));
            assertNull(slot.get());
            assertNull(old.claim(oldChunk));
            var successor = open(slot, successorChunk);
            var successorBuilder = ready(successor, successorChunk);
            resumeNative.countDown();
            get(nativeWork);
            assertSame(successor, slot.get(), "Late completion must not clear the successor slot");
            assertNull(old.claim(oldChunk));
            assertInstanceOf(CancellationException.class, failure(old));
            assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), cell(oldChunk, 0));
            assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), cell(successorChunk, 0));
            assertFalse(successor.result().toCompletableFuture().isDone());
            successor.complete(successorBuilder);
            var snapshot = get(successor.result().toCompletableFuture());
            assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), snapshot.before().get(-MIN_Y));
            assertEquals(snapshot.before(), snapshot.after());
            assertNull(slot.get());
        } finally {
            resumeNative.countDown();
            try { shutdown(executor); }
            finally { old.close(); closeActive(slot); }
        }
    }

    @Test
    void completeReleasesBeforeBlockedSuccessObserverAndParallelTerminalCalls() throws Exception {
        ProtoChunk target = chunk();
        fill(target, Blocks.STONE.defaultBlockState());
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var builder = ready(lease, target);
        var observerEntered = new CountDownLatch(1);
        var releaseObserver = new CountDownLatch(1);
        var deliveries = new AtomicInteger();
        var observer = lease.result().thenAccept(snapshot -> {
            assertNull(slot.get(), "Release precedes observer delivery");
            assertEquals(Blocks.STONE.defaultBlockState(), snapshot.after().get(0));
            deliveries.incrementAndGet();
            observerEntered.countDown();
            await(releaseObserver);
        });
        var executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> completion = executor.submit(() -> lease.complete(builder));
            await(observerEntered);
            assertFalse(completion.isDone(), "Inline observer still holds up the completing task");
            Future<?> close = executor.submit(lease::close);
            Future<?> failed = executor.submit(() -> lease.failed(new IllegalStateException("too late")));
            get(close);
            get(failed);
            assertNull(get(executor.submit(() -> lease.claim(target))));
            assertNull(slot.get());
            ProtoChunk nextChunk = chunk();
            var successor = open(slot, nextChunk);
            var nextBuilder = get(executor.submit(() -> successor.claim(nextChunk)));
            assertNotNull(nextBuilder, "Successor can claim while old success observer is blocked");
            nextBuilder.before(nextChunk, null, x, z, 0, WaterDecision.dry(), BIOME);
            nextBuilder.after(nextChunk, null, x, z, 0);
            assertSame(successor, slot.get());
            assertFalse(successor.result().toCompletableFuture().isDone());
            releaseObserver.countDown();
            get(completion);
            get(observer.toCompletableFuture()); // Observe callback assertions on the dependent stage.
            assertEquals(1, deliveries.get());
            assertNotNull(get(lease.result().toCompletableFuture()));
            lease.close();
            lease.failed(new IllegalStateException("also too late"));
            assertSame(successor, slot.get());
            successor.complete(nextBuilder);
            assertNotNull(get(successor.result().toCompletableFuture()));
            assertNull(slot.get());
        } finally {
            releaseObserver.countDown();
            try { shutdown(executor); }
            finally { lease.close(); closeActive(slot); }
        }
    }

    @Test
    void failureReleasesBeforeBlockedFailureObserverAndKeepsOriginalCause() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var builder = ready(lease, target);
        var original = new IllegalStateException("original native failure");
        var observerEntered = new CountDownLatch(1);
        var releaseObserver = new CountDownLatch(1);
        var deliveries = new AtomicInteger();
        var observer = lease.result().handle((snapshot, throwable) -> {
            assertNull(snapshot);
            assertSame(original, unwrap(throwable));
            assertNull(slot.get(), "Failure releases before observer delivery");
            deliveries.incrementAndGet();
            observerEntered.countDown();
            await(releaseObserver);
            return null;
        });
        var executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> failed = executor.submit(() -> lease.failed(original));
            await(observerEntered);
            assertFalse(failed.isDone());
            Future<?> complete = executor.submit(() -> lease.complete(builder));
            Future<?> close = executor.submit(lease::close);
            get(complete);
            get(close);
            assertSame(original, failure(lease));
            assertNull(get(executor.submit(() -> lease.claim(target))));
            assertNull(slot.get());
            ProtoChunk nextChunk = chunk();
            var successor = open(slot, nextChunk);
            var nextBuilder = get(executor.submit(() -> successor.claim(nextChunk)));
            assertNotNull(nextBuilder, "Successor can claim while old failure observer is blocked");
            nextBuilder.before(nextChunk, null, x, z, 0, WaterDecision.dry(), BIOME);
            nextBuilder.after(nextChunk, null, x, z, 0);
            releaseObserver.countDown();
            get(failed);
            get(observer.toCompletableFuture());
            assertEquals(1, deliveries.get());
            lease.complete(builder);
            lease.close();
            lease.failed(new IllegalArgumentException("later failure"));
            assertSame(original, failure(lease));
            assertSame(successor, slot.get());
            successor.complete(nextBuilder);
            assertNotNull(get(successor.result().toCompletableFuture()));
            assertNull(slot.get());
        } finally {
            releaseObserver.countDown();
            try { shutdown(executor); }
            finally { lease.close(); closeActive(slot); }
        }
    }

    @Test
    void closeBeforeDelayedClaimReturnsNullAndLeavesSuccessorClaimable() throws Exception {
        ProtoChunk target = chunk();
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var readyToClaim = new CountDownLatch(1);
        var allowClaim = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var delayedClaim = executor.submit(() -> {
                readyToClaim.countDown();
                await(allowClaim);
                return lease.claim(target);
            });
            await(readyToClaim);
            lease.close();
            assertNull(slot.get());
            assertInstanceOf(CancellationException.class, failure(lease));
            var successor = open(slot, target);
            var nextBuilder = ready(successor, target);
            allowClaim.countDown();
            assertNull(get(delayedClaim));
            lease.complete(null);
            assertSame(successor, slot.get());
            assertNull(lease.claim(target));
            successor.complete(nextBuilder);
            assertNotNull(get(successor.result().toCompletableFuture()));
            assertNull(slot.get());
        } finally {
            allowClaim.countDown();
            try { shutdown(executor); }
            finally { lease.close(); closeActive(slot); }
        }
    }

    @Test
    void mutatingPublicFutureCopiesDoesNotCancelOrCompleteUnderlyingClaim() throws Exception {
        ProtoChunk target = chunk();
        fill(target, Blocks.STONE.defaultBlockState());
        var slot = new AtomicReference<TellusEarthNativeColumnCapture.Lease>();
        var lease = open(slot, target);
        var claimed = new CountDownLatch(1);
        var resumeNative = new CountDownLatch(1);
        var deliveries = new AtomicInteger();
        var observer = lease.result().thenAccept(snapshot -> deliveries.incrementAndGet());
        var cancelledCopy = lease.result().toCompletableFuture();
        var completedCopy = lease.result().toCompletableFuture();
        var untouchedCopy = lease.result().toCompletableFuture();
        var fakeCells = Collections.nCopies(1 - MIN_Y, Blocks.AIR.defaultBlockState());
        var fake = new TellusEarthNativeColumnCapture.Snapshot(GENERATION, coordinate, 0,
            WaterDecision.dry(), BIOME, fakeCells, fakeCells, Optional.empty(), Optional.empty(), Optional.empty());
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> nativeWork = executor.submit(() -> {
                var builder = lease.claim(target);
                assertNotNull(builder);
                claimed.countDown();
                await(resumeNative);
                writeAndCapture(builder, target, Blocks.GOLD_BLOCK.defaultBlockState());
                lease.complete(builder);
            });
            await(claimed);
            assertNotSame(cancelledCopy, completedCopy);
            assertTrue(cancelledCopy.cancel(true));
            assertTrue(completedCopy.complete(fake));
            assertTrue(cancelledCopy.isCancelled());
            assertThrows(CancellationException.class, () -> get(cancelledCopy));
            assertSame(fake, get(completedCopy));
            assertFalse(untouchedCopy.isDone());
            assertFalse(lease.result().toCompletableFuture().isDone());
            assertEquals(0, deliveries.get(), "Copy completion does not notify the lease observer");
            assertSame(lease, slot.get());
            assertNull(lease.claim(target), "Only the worker owns the one-shot claim");
            assertThrows(IllegalStateException.class, () -> open(slot, target));
            resumeNative.countDown();
            get(nativeWork);
            get(observer.toCompletableFuture());
            var actual = get(untouchedCopy);
            assertNotSame(fake, actual);
            assertEquals(Blocks.STONE.defaultBlockState(), actual.before().get(-MIN_Y));
            assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), actual.after().get(-MIN_Y));
            assertEquals(1, deliveries.get());
            assertSame(actual, get(lease.result().toCompletableFuture()));
            assertTrue(cancelledCopy.isCancelled());
            assertSame(fake, get(completedCopy));
            assertNull(slot.get());
            assertNull(lease.claim(target));
            var successor = open(slot, target);
            var nextBuilder = ready(successor, target);
            lease.close();
            assertSame(successor, slot.get());
            successor.complete(nextBuilder);
            assertNotNull(get(successor.result().toCompletableFuture()));
        } finally {
            resumeNative.countDown();
            try { shutdown(executor); }
            finally { lease.close(); closeActive(slot); }
        }
    }

    private static TellusEarthNativeColumnCapture.Lease open(
        AtomicReference<TellusEarthNativeColumnCapture.Lease> slot, ProtoChunk target) {
        return TellusEarthNativeColumnCapture.open(slot, target, GENERATION, coordinate);
    }

    private static TellusEarthNativeColumnCapture.Builder ready(
        TellusEarthNativeColumnCapture.Lease lease, ProtoChunk target) {
        var builder = lease.claim(target);
        assertNotNull(builder);
        builder.before(target, null, x, z, 0, WaterDecision.dry(), BIOME);
        builder.after(target, null, x, z, 0);
        return builder;
    }

    private static void writeAndCapture(TellusEarthNativeColumnCapture.Builder builder,
                                        ProtoChunk target, BlockState surface) {
        var writer = new EarthChunkGenerator.ChunkSectionWriter(target.getSections(), MIN_Y);
        try {
            builder.before(target, writer, x, z, 0, WaterDecision.dry(), BIOME);
            writer.setBlock(x & 15, z & 15, 0, surface);
            builder.after(target, writer, x, z, 0);
        } finally { writer.close(); }
    }

    private static ProtoChunk chunk() throws Exception {
        return (ProtoChunk) protoConstructor.newInstance(new ChunkPos(x >> 4, z >> 4), UpgradeData.EMPTY,
            LevelHeightAccessor.create(MIN_Y, HEIGHT), biomes, null);
    }

    private static void fill(ProtoChunk target, BlockState state) {
        for (int y = MIN_Y; y <= 0; y++) {
            MinecraftVersionCompat.setBlockState(target, new BlockPos(x, y, z), state);
        }
    }

    private static BlockState cell(ProtoChunk target, int y) {
        return target.getBlockState(new BlockPos(x, y, z));
    }

    private static Throwable failure(TellusEarthNativeColumnCapture.Lease lease) {
        var exception = assertThrows(ExecutionException.class,
            () -> lease.result().toCompletableFuture().get(5, TimeUnit.SECONDS));
        return unwrap(exception.getCause());
    }

    private static Throwable unwrap(Throwable throwable) {
        while (throwable instanceof CompletionException && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable;
    }

    private static <T> T get(Future<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Interleaving gate timed out"); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interleaving gate interrupted", exception);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Interleaving worker did not terminate");
    }

    private static void closeActive(AtomicReference<TellusEarthNativeColumnCapture.Lease> slot) {
        var active = slot.get();
        if (active != null) active.close();
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
}
