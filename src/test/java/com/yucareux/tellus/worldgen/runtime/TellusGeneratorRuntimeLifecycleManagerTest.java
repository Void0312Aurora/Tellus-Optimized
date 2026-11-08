package com.yucareux.tellus.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.nio.file.Path;
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

/** Focused gated manager contract; no TellusCommon or automatic hook is involved. */
class TellusGeneratorRuntimeLifecycleManagerTest {
   private static Holder<Biome> plains;

   @BeforeAll
   static void bootstrap() {
      System.setProperty("tellus.gameDir", Path.of("build/tmp/runtime-manager").toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of("build/tmp/runtime-manager/config").toAbsolutePath().toString());
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      plains = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
   }

   @Test
   void defaultFeatureFlagIsDisabledAndLeavesRegistryUntouched() {
      EarthChunkGenerator generator = earth();
      EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);
      TellusGeneratorRuntimeRegistry registry = registry(generator, runtime);
      TellusGeneratorRuntimeLifecycleManager manager = new TellusGeneratorRuntimeLifecycleManager(registry);

      assertFalse(manager.enabled());
      assertEquals(BridgeResult.Status.DISABLED, manager.onGeneratorRegistered(generator).status());
      assertEquals(BridgeResult.Status.DISABLED, manager.onServerTick().status());
      assertEquals(BridgeResult.Status.DISABLED, manager.onGeneratorUnregistered(generator).status());
      assertEquals(1, registry.size());
      assertFalse(runtime.attached());
   }

   @Test
   void enabledCallbacksAttachTickAndUnregisterExactIdentity() {
      EarthChunkGenerator generator = earth();
      EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);
      TellusGeneratorRuntimeRegistry registry = registry(generator, runtime);
      TellusGeneratorRuntimeLifecycleManager manager = new TellusGeneratorRuntimeLifecycleManager(registry, true);

      assertEquals(BridgeResult.Status.ATTACHED, manager.onGeneratorRegistered(generator).status());
      assertEquals(BridgeResult.Status.TICKED, manager.onServerTick().status());
      assertEquals(BridgeResult.Status.UNREGISTERED, manager.onGeneratorUnregistered(generator).status());
      assertEquals(0, registry.size());
      assertFalse(runtime.attached());
   }

   @Test
   void staleIdentityIsRejectedAndStoppingClearsAll() {
      EarthChunkGenerator registered = earth();
      EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(registered);
      TellusGeneratorRuntimeRegistry registry = registry(registered, runtime);
      TellusGeneratorRuntimeLifecycleManager manager = new TellusGeneratorRuntimeLifecycleManager(registry, true);

      assertEquals(BridgeResult.Status.NOT_FOUND, manager.onGeneratorUnregistered(earth()).status());
      assertEquals(1, registry.size());
      assertEquals(BridgeResult.Status.ATTACHED, manager.onGeneratorRegistered(registered).status());
      assertEquals(BridgeResult.Status.UNREGISTERED_ALL, manager.onServerStopping().status());
      assertEquals(0, registry.size());
      assertFalse(runtime.attached());
   }

   @Test
   void rolloutGateDisabledResultCannotEnableManager() {
      EarthChunkGenerator generator = earth();
      EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);
      TellusGeneratorRuntimeRegistry registry = registry(generator, runtime);
      TellusRuntimeRolloutGate.Result gate = TellusRuntimeRolloutGate.evaluate(
         new TellusRuntimeRolloutGate.Request(false, parity("columns"), parity("biome"), true));
      TellusGeneratorRuntimeLifecycleManager manager =
         TellusGeneratorRuntimeLifecycleManager.fromRolloutGate(registry, gate);

      assertFalse(gate.enabled());
      assertFalse(manager.enabled());
      assertEquals(BridgeResult.Status.DISABLED, manager.onGeneratorRegistered(generator).status());
      assertFalse(runtime.attached());
      assertEquals(1, registry.size());
   }

   @Test
   void rolloutGateEnabledResultAllowsOnlyExplicitManagerCallbacks() {
      EarthChunkGenerator generator = earth();
      EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);
      TellusGeneratorRuntimeRegistry registry = registry(generator, runtime);
      TellusRuntimeRolloutGate.Result gate = TellusRuntimeRolloutGate.evaluate(
         new TellusRuntimeRolloutGate.Request(true, parity("columns"), parity("biome"), true));
      TellusGeneratorRuntimeLifecycleManager manager =
         new TellusGeneratorRuntimeLifecycleManager(registry, gate);

      assertTrue(gate.enabled());
      assertTrue(manager.enabled());
      assertEquals(BridgeResult.Status.ATTACHED, manager.onGeneratorRegistered(generator).status());
      assertEquals(BridgeResult.Status.TICKED, manager.onServerTick().status());
      assertEquals(BridgeResult.Status.UNREGISTERED, manager.onGeneratorUnregistered(generator).status());
      assertEquals(0, registry.size());
   }

   private static TellusRuntimeRolloutGate.ParityEvidence parity(String id) {
      return new TellusRuntimeRolloutGate.ParityEvidence(true, true, id + "-fresh-pass");
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
