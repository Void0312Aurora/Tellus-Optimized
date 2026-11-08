package com.yucareux.tellus.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Strict identity/factory contracts with no loader lifecycle registration. */
class TellusGeneratorRuntimeRegistryTest {
    private static EarthChunkGenerator generator;
    private static Holder<Biome> plains;

    @BeforeAll
    static void bootstrap() {
        System.setProperty("tellus.gameDir", Path.of("build/tmp/runtime-registry").toAbsolutePath().toString());
        System.setProperty("tellus.configDir", Path.of("build/tmp/runtime-registry/config").toAbsolutePath().toString());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> mapped = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        plains = mapped.register(Biomes.PLAINS,
            VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
            Lifecycle.stable());
        mapped.freeze();
        generator = new EarthChunkGenerator(new FixedBiomeSource(plains), EarthGeneratorSettings.DEFAULT);
    }

    @Test
    void factoryRequiresExactlyOneIdentityOwningAdapter() {
        EarthGeneratorRuntimeAdapter first = new EarthGeneratorRuntimeAdapter(generator);
        EarthGeneratorRuntimeAdapter second = new EarthGeneratorRuntimeAdapter(generator);

        TellusGeneratorRuntimeFactory.Result created = TellusGeneratorRuntimeFactory.create(
            generator, List.of(ignored -> Optional.of(first)));
        TellusGeneratorRuntimeFactory.Result missing = TellusGeneratorRuntimeFactory.create(
            generator, List.of(ignored -> Optional.empty()));
        TellusGeneratorRuntimeFactory.Result ambiguous = TellusGeneratorRuntimeFactory.create(
            generator, List.of(ignored -> Optional.of(first), ignored -> Optional.of(second)));
        TellusGeneratorRuntimeFactory.Result invalid = TellusGeneratorRuntimeFactory.create(
            generator, List.of(ignored -> Optional.of(new EarthGeneratorRuntimeAdapter(earth()))));

        assertEquals(TellusGeneratorRuntimeFactory.Status.CREATED, created.status());
        assertTrue(created.runtime().isPresent());
        assertEquals(TellusGeneratorRuntimeFactory.Status.NOT_FOUND, missing.status());
        assertEquals(TellusGeneratorRuntimeFactory.Status.AMBIGUOUS, ambiguous.status());
        assertEquals(2, ambiguous.candidates());
        assertEquals(TellusGeneratorRuntimeFactory.Status.INVALID_OWNERSHIP, invalid.status());
    }

    @Test
    void registryEnforcesIdentityOwnershipDuplicateAndAttachDetachIdempotency() {
        TellusGeneratorRuntimeRegistry registry = TellusGeneratorRuntimeFactory.registry();
        TellusGeneratorRuntime runtime = new EarthGeneratorRuntimeAdapter(generator);
        TellusGeneratorRuntime other = new EarthGeneratorRuntimeAdapter(generator);
        EarthChunkGenerator unsupported = earth();

        var registered = registry.register(generator, runtime);
        var duplicate = registry.register(generator, other);
        var invalid = registry.register(generator, new EarthGeneratorRuntimeAdapter(unsupported));
        var found = registry.resolve(generator);
        var notFound = registry.resolve(unsupported);
        var attached = registry.attach(generator);
        var already = registry.attach(generator);
        var detached = registry.detach(generator);

        assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.REGISTERED, registered.status());
        assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.DUPLICATE_IDENTITY, duplicate.status());
        assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.INVALID_OWNERSHIP, invalid.status());
        assertEquals(TellusGeneratorRuntimeRegistry.ResolutionStatus.FOUND, found.status());
        assertEquals(TellusGeneratorRuntimeRegistry.ResolutionStatus.NOT_FOUND, notFound.status());
        assertEquals(TellusGeneratorRuntime.AttachResult.ATTACHED, attached.attachResult().orElseThrow());
        assertEquals(TellusGeneratorRuntime.AttachResult.ALREADY_ATTACHED, already.attachResult().orElseThrow());
        assertFalse(runtime.attached());
        assertEquals(TellusGeneratorRuntimeRegistry.ResolutionStatus.FOUND, detached.status());
    }

    @Test
    void createAndRegisterMapsFactoryFailuresAndKeepsNoLifecycleSideEffects() {
        TellusGeneratorRuntimeRegistry registry = TellusGeneratorRuntimeFactory.registry();
        TellusGeneratorRuntime runtime = new EarthGeneratorRuntimeAdapter(generator);

        var notFound = TellusGeneratorRuntimeFactory.createAndRegister(
            registry, generator, List.of(ignored -> Optional.empty()));
        var registered = TellusGeneratorRuntimeFactory.createAndRegister(
            registry, generator, List.of(ignored -> Optional.of(runtime)));
        var duplicate = TellusGeneratorRuntimeFactory.createAndRegister(
            registry, generator, List.of(ignored -> Optional.of(new EarthGeneratorRuntimeAdapter(generator))));

        assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.FACTORY_NOT_FOUND, notFound.status());
        assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.REGISTERED, registered.status());
        assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.DUPLICATE_IDENTITY, duplicate.status());
        assertEquals(1, registry.size());
        assertFalse(runtime.attached());
        assertTrue(registry.orderedAdapters().contains(runtime));
        registry.detachAll();
        assertFalse(runtime.attached());
    }

    private static EarthChunkGenerator earth() {
        return new EarthChunkGenerator(new FixedBiomeSource(plains), EarthGeneratorSettings.DEFAULT);
    }
}