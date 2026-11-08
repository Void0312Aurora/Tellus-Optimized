package com.yucareux.tellus.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Contract tests for explicit, non-registered runtime descriptors. */
class TellusGeneratorRuntimeDescriptorTest {
    private static EarthChunkGenerator earth;

    @BeforeAll
    static void bootstrap() {
        System.setProperty("tellus.gameDir", Path.of("build/tmp/runtime-descriptor").toAbsolutePath().toString());
        System.setProperty("tellus.configDir", Path.of("build/tmp/runtime-descriptor/config").toAbsolutePath().toString());
        System.setProperty("tellus.overture.release", "2026-06-17.0");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        earth = new EarthChunkGenerator(new FixedBiomeSource(
            VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)),
            EarthGeneratorSettings.DEFAULT);
    }

    @Test
    void earthDescriptorIsTypedAndDoesNotRegisterLifecycle() {
        TellusGeneratorRuntimeDescriptor descriptor = TellusEarthRuntimeFactories.descriptor();
        assertEquals("tellus:earth", descriptor.id());
        assertEquals(TellusGeneratorSettingsView.Kind.EARTH, descriptor.kind());
        assertEquals("root", descriptor.sourceLayer());
        assertTrue(descriptor.create(earth).isPresent());
        assertTrue(descriptor.settings(earth).isPresent());
        assertEquals(List.of(descriptor), TellusEarthRuntimeFactories.descriptors());
    }

    @Test
    void createAndRegisterPreservesFactoryFailureStatuses() {
        TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
        TellusGeneratorRuntimeRegistry.RegistrationResult missing =
            TellusGeneratorRuntimeFactory.createAndRegister(
                registry, earth, List.of(ignored -> Optional.empty()));
        assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.FACTORY_NOT_FOUND, missing.status());
        assertEquals(0, registry.size());

        TellusGeneratorRuntimeFactory.AdapterFactory first =
            ignored -> TellusEarthRuntimeFactories.descriptor().create(earth);
        TellusGeneratorRuntimeFactory.AdapterFactory second =
            ignored -> TellusEarthRuntimeFactories.descriptor().create(earth);
        TellusGeneratorRuntimeRegistry.RegistrationResult ambiguous =
            TellusGeneratorRuntimeFactory.createAndRegister(registry, earth, List.of(first, second));
        assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.FACTORY_AMBIGUOUS, ambiguous.status());
        assertEquals(0, registry.size());
    }

    @Test
    void factoryEmptyOutcomeAndDescriptorOrderRemainExplicit() {
        TellusGeneratorRuntimeDescriptor descriptor = TellusEarthRuntimeFactories.descriptor();
        TellusGeneratorRuntimeFactory.Result missing = TellusGeneratorRuntimeFactory.create(
            earth, List.of(ignored -> Optional.empty()));
        assertEquals(TellusGeneratorRuntimeFactory.Status.NOT_FOUND, missing.status());
        assertEquals(1, TellusEarthRuntimeFactories.orderedAdapterFactories().size());
        assertEquals(List.of(descriptor), TellusEarthRuntimeFactories.descriptors());
    }
}
