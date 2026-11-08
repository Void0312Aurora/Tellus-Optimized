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

/** Focused mc1211 registration helper contract: order, Earth, unsupported, duplicate. */
class TellusMinecraft1211RuntimeRegistrationTest {
   private static EarthChunkGenerator earth;

   @BeforeAll
   static void bootstrap() {
      System.setProperty("tellus.gameDir", Path.of("build/tmp/runtime-registration-1211").toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of("build/tmp/runtime-registration-1211/config").toAbsolutePath().toString());
      System.setProperty("tellus.overture.release", "2026-06-17.0");
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      earth = new EarthChunkGenerator(new FixedBiomeSource(
         VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)),
         EarthGeneratorSettings.DEFAULT);
   }

   @Test
   void orderedFactoriesStartWithEarthAndRemainExplicit() {
      List<TellusGeneratorRuntimeDescriptor> descriptors = TellusMinecraft1211RuntimeFactories.descriptors();
      assertEquals(List.of("tellus:earth", "tellus:cube_net", "tellus:sphere_face"),
         descriptors.stream().map(TellusGeneratorRuntimeDescriptor::id).toList());
      assertEquals(descriptors.size(), TellusMinecraft1211RuntimeFactories.orderedAdapterFactories().size());
   }

   @Test
   void earthRegistersIntoCallerOwnedRegistryOnly() {
      TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
      TellusGeneratorRuntimeRegistry.RegistrationResult result =
         TellusMinecraft1211RuntimeRegistration.register(registry, earth);
      assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.REGISTERED, result.status());
      assertEquals(1, registry.size());
      assertTrue(registry.resolve(earth).found());
   }

   @Test
   void unsupportedAndDuplicateRemainExplicit() {
      TellusGeneratorRuntimeRegistry unsupportedRegistry = new TellusGeneratorRuntimeRegistry();
      TellusGeneratorRuntimeRegistry.RegistrationResult unsupported =
         TellusMinecraft1211RuntimeRegistration.register(unsupportedRegistry, earth, List.of());
      assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.FACTORY_NOT_FOUND, unsupported.status());
      assertEquals(0, unsupportedRegistry.size());

      TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
      assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.REGISTERED,
         TellusMinecraft1211RuntimeRegistration.register(registry, earth).status());
      assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.DUPLICATE_IDENTITY,
         TellusMinecraft1211RuntimeRegistration.register(registry, earth).status());
      assertEquals(1, registry.size());
   }

   @Test
   void factoryNotFoundResultDoesNotCreateRuntime() {
      TellusGeneratorRuntimeFactory.Result result = TellusGeneratorRuntimeFactory.create(
         earth, List.of(ignored -> Optional.empty()));
      assertEquals(TellusGeneratorRuntimeFactory.Status.NOT_FOUND, result.status());
      assertTrue(result.runtime().isEmpty());
   }
}
