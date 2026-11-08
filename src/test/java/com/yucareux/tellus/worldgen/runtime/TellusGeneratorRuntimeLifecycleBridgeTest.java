package com.yucareux.tellus.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Explicit lifecycle bridge tests; construction performs no automatic hooks. */
class TellusGeneratorRuntimeLifecycleBridgeTest {
    private static Holder<Biome> plains;

    @BeforeAll
    static void bootstrap() {
        System.setProperty("tellus.gameDir", Path.of("build/tmp/runtime-bridge").toAbsolutePath().toString());
        System.setProperty("tellus.configDir", Path.of("build/tmp/runtime-bridge/config").toAbsolutePath().toString());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        plains = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
    }

    @Test
    void disabledBridgeIsNoOpAndDoesNotForwardRegistryOperations() {
        EarthChunkGenerator generator = earth();
        EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);
        TellusGeneratorRuntimeRegistry registry = registry(generator, runtime);
        TellusGeneratorRuntimeLifecycleBridge bridge = new TellusGeneratorRuntimeLifecycleBridge(
            registry, AttachPolicy.DISABLED, ignored -> CapabilityReadiness.available());

        BridgeResult attached = bridge.attach(generator);
        BridgeResult ticked = bridge.tick();
        BridgeResult detached = bridge.detach(generator);
        BridgeResult detachedAll = bridge.detachAll();

        assertEquals(BridgeResult.Status.DISABLED, attached.status());
        assertEquals(BridgeResult.Status.DISABLED, ticked.status());
        assertEquals(BridgeResult.Status.DISABLED, detached.status());
        assertEquals(BridgeResult.Status.DISABLED, detachedAll.status());
        assertFalse(runtime.attached());
        assertTrue(attached.readiness().diagnostic().contains("disabled"));
    }

    @Test
    void enabledBridgeForwardsAttachTickDetachAndIsIdempotent() {
        EarthChunkGenerator generator = earth();
        EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);
        TellusGeneratorRuntimeRegistry registry = registry(generator, runtime);
        AtomicInteger readinessChecks = new AtomicInteger();
        TellusGeneratorRuntimeLifecycleBridge bridge = new TellusGeneratorRuntimeLifecycleBridge(
            registry, AttachPolicy.REQUIRE_CAPABILITY_READY,
            ignored -> {
                readinessChecks.incrementAndGet();
                return CapabilityReadiness.available();
            });

        assertFalse(runtime.attached());
        BridgeResult attached = bridge.attach(generator);
        BridgeResult already = bridge.attach(generator);
        BridgeResult ticked = bridge.tick();
        BridgeResult detached = bridge.detach(generator);
        BridgeResult detachedAgain = bridge.detach(generator);
        BridgeResult detachedAll = bridge.detachAll();

        assertEquals(BridgeResult.Status.ATTACHED, attached.status());
        assertEquals(BridgeResult.Status.ALREADY_ATTACHED, already.status());
        assertEquals(BridgeResult.Status.TICKED, ticked.status());
        assertTrue(ticked.diagnostic().contains("1 attached"));
        assertEquals(BridgeResult.Status.DETACHED, detached.status());
        assertEquals(BridgeResult.Status.DETACHED, detachedAgain.status());
        assertEquals(BridgeResult.Status.DETACHED_ALL, detachedAll.status());
        assertEquals(2, readinessChecks.get());
        assertFalse(runtime.attached());
    }

    @Test
    void readinessAndRegistryResolutionStatusesAreForwarded() {
        EarthChunkGenerator generator = earth();
        EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);
        TellusGeneratorRuntimeRegistry registry = registry(generator, runtime);
        TellusGeneratorRuntimeLifecycleBridge notReady = new TellusGeneratorRuntimeLifecycleBridge(
            registry, AttachPolicy.REQUIRE_CAPABILITY_READY,
            ignored -> CapabilityReadiness.notReady("fixture not ready"));
        BridgeResult rejected = notReady.attach(generator);
        BridgeResult missing = notReady.attach(earth());

        TellusGeneratorRuntimeLifecycleBridge explicit = new TellusGeneratorRuntimeLifecycleBridge(
            registry, AttachPolicy.EXPLICIT_ONLY, ignored -> CapabilityReadiness.notReady("unused"));
        BridgeResult attached = explicit.attach(generator);
        BridgeResult unknownGenerator = explicit.attach(earth());

        assertEquals(BridgeResult.Status.NOT_READY, rejected.status());
        assertEquals(LifecycleEvent.NOT_READY, rejected.event());
        assertEquals(BridgeResult.Status.NOT_FOUND, missing.status());
        assertEquals(BridgeResult.Status.ATTACHED, attached.status());
        assertEquals(BridgeResult.Status.NOT_FOUND, unknownGenerator.status());
        assertEquals(LifecycleEvent.REJECTED, unknownGenerator.event());
    }

    private static TellusGeneratorRuntimeRegistry registry(
        EarthChunkGenerator generator, EarthGeneratorRuntimeAdapter runtime
    ) {
        TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
        assertTrue(registry.register(generator, runtime).registered());
        return registry;
    }

    private static EarthChunkGenerator earth() {
        return new EarthChunkGenerator(new FixedBiomeSource(plains), EarthGeneratorSettings.DEFAULT);
    }
}
