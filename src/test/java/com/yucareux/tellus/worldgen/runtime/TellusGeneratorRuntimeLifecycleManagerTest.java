package com.yucareux.tellus.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.runtime.TellusGeneratorRuntime.AttachResult;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Focused gated manager and exact rollback contracts; no automatic hook is involved. */
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
   void explicitRegistrationRollbackDetachesAndRemovesOnlyExactEntry() {
      EarthChunkGenerator generator = earth();
      EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);
      TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
      var rollback = registry.registerWithRollback(generator, runtime);

      assertTrue(rollback.registered());
      assertEquals(1, registry.size());
      assertTrue(rollback.rollback());
      assertFalse(runtime.attached());
      assertEquals(0, registry.size());
      assertFalse(rollback.rollback(), "rollback is idempotent");
      rollback.close();
      assertEquals(0, registry.size());
   }

   @Test
   void failedRegistrationRollbackTokenDoesNotTouchExistingEntries() {
      EarthChunkGenerator generator = earth();
      EarthChunkGenerator other = earth();
      EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);
      TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
      assertTrue(registry.register(generator, runtime).registered());
      var duplicate = registry.registerWithRollback(generator, runtime);

      assertFalse(duplicate.registered());
      assertFalse(duplicate.rollback());
      assertEquals(1, registry.size());
      assertTrue(registry.resolve(generator).found());
      assertFalse(registry.resolve(other).found());
   }

   @Test
   void reentrantDetachCannotUnregisterCurrentEntryAndCanRegisterDistinctSuccessor() {
      EarthChunkGenerator generator = earth();
      EarthChunkGenerator successor = earth();
      TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
      EarthGeneratorRuntimeAdapter successorRuntime = new EarthGeneratorRuntimeAdapter(successor);
      CallbackRuntime runtime = new CallbackRuntime(generator, () -> {
         assertEquals(TellusGeneratorRuntimeRegistry.ResolutionStatus.NOT_FOUND,
            registry.unregister(generator).status());
         assertEquals(TellusGeneratorRuntimeRegistry.RegistrationStatus.REGISTERED,
            registry.register(successor, successorRuntime).status());
      });

      assertTrue(registry.register(generator, runtime).registered());
      assertTrue(registry.unregister(generator).found());
      assertEquals(1, registry.size());
      assertFalse(registry.resolve(generator).found());
      assertSame(successorRuntime, registry.resolve(successor).runtime().orElseThrow());
   }

   @Test
   void staleRollbackTokenNeverTouchesReplacementEntry() {
      EarthChunkGenerator generator = earth();
      TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
      EarthGeneratorRuntimeAdapter original = new EarthGeneratorRuntimeAdapter(generator);
      var token = registry.registerWithRollback(generator, original);

      assertTrue(registry.unregister(generator).found());
      EarthGeneratorRuntimeAdapter replacement = new EarthGeneratorRuntimeAdapter(generator);
      assertTrue(registry.register(generator, replacement).registered());
      assertFalse(token.rollback());
      assertEquals(1, registry.size());
      assertSame(replacement, registry.resolve(generator).runtime().orElseThrow());
   }

   @Test
   void rollbackRuntimeFailurePreservesIdentityAndAllowsRetry() {
      EarthChunkGenerator generator = earth();
      TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
      RuntimeException failure = new RuntimeException("detach-runtime");
      int[] attempts = {0};
      CallbackRuntime runtime = new CallbackRuntime(generator, () -> {
         if (attempts[0]++ == 0) throw failure;
      });
      var token = registry.registerWithRollback(generator, runtime);

      assertSame(failure, assertThrows(RuntimeException.class, token::rollback));
      assertEquals(1, registry.size());
      assertTrue(token.rollback());
      assertEquals(0, registry.size());
   }

   @Test
   void rollbackErrorPreservesIdentityAndAllowsRetry() {
      EarthChunkGenerator generator = earth();
      TellusGeneratorRuntimeRegistry registry = new TellusGeneratorRuntimeRegistry();
      AssertionError failure = new AssertionError("detach-error");
      int[] attempts = {0};
      CallbackRuntime runtime = new CallbackRuntime(generator, () -> {
         if (attempts[0]++ == 0) throw failure;
      });
      var token = registry.registerWithRollback(generator, runtime);

      assertSame(failure, assertThrows(AssertionError.class, token::rollback));
      assertEquals(1, registry.size());
      assertTrue(token.rollback());
      assertEquals(0, registry.size());
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

   @Test
   void batchDetachFailureAndSuccessRetainOriginalRegistrationOrder() {
      var registry = new TellusGeneratorRuntimeRegistry();
      var a = earth(); var b = earth(); var c = earth();
      var events = new java.util.ArrayList<String>();
      var failure = new AssertionError("batch-detach");
      int[] attempts = {0};
      var ar = new CallbackRuntime(a, () -> events.add("a"));
      var br = new CallbackRuntime(b, () -> { events.add("b"); if (attempts[0]++ == 0) throw failure; });
      var cr = new CallbackRuntime(c, () -> events.add("c"));
      registry.register(a, ar); registry.register(b, br); registry.register(c, cr);
      var original = java.util.List.of(ar, br, cr);
      assertSame(failure, assertThrows(AssertionError.class, registry::detachAll));
      assertEquals(java.util.List.of("c", "b"), events);
      assertEquals(original, registry.orderedAdapters());
      events.clear();
      registry.detachAll();
      assertEquals(java.util.List.of("c", "b", "a"), events);
      assertEquals(original, registry.orderedAdapters());
      assertEquals(3, registry.unregisterAll());
      assertEquals(0, registry.size());
   }

   @Test
   void admittedRollbackCallbackHoldsNeitherMonitorAndConcurrentTokenRetryReturnsCold() throws Exception {
      var registry = new TellusGeneratorRuntimeRegistry();
      var generator = earth();
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var token = new java.util.concurrent.atomic.AtomicReference<TellusGeneratorRuntimeRegistry.RegistrationRollback>();
      var runtime = new CallbackRuntime(generator, () -> {
         assertFalse(Thread.holdsLock(registry));
         assertFalse(Thread.holdsLock(token.get()));
         entered.countDown();
         try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
         catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
      });
      token.set(registry.registerWithRollback(generator, runtime));
      var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
         var first = pool.submit(() -> token.get().rollback());
         assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
         var retry = pool.submit(() -> token.get().rollback());
         assertFalse(retry.get(5, java.util.concurrent.TimeUnit.SECONDS));
         release.countDown();
         assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS));
         assertEquals(0, registry.size());
      } finally {
         release.countDown();
         pool.shutdownNow();
         assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
      }
   }

   @Test
   void distinctConcurrentDetachRestoresRegistrationOrderOnSuccessAndFailure() throws Exception {
      for (boolean fail : new boolean[]{false, true}) {
         var registry = new TellusGeneratorRuntimeRegistry();
         var a = earth(); var b = earth(); var c = earth();
         var aEntered = new java.util.concurrent.CountDownLatch(1);
         var bEntered = new java.util.concurrent.CountDownLatch(1);
         var aRelease = new java.util.concurrent.CountDownLatch(1);
         var bRelease = new java.util.concurrent.CountDownLatch(1);
         var aFailure = new AssertionError("first-detach");
         var bFailure = new IllegalStateException("second-detach");
         var ar = new CallbackRuntime(a, () -> { awaitCallbackGate(aEntered, aRelease); if (fail) throw aFailure; });
         var br = new CallbackRuntime(b, () -> { awaitCallbackGate(bEntered, bRelease); if (fail) throw bFailure; });
         var cr = new EarthGeneratorRuntimeAdapter(c);
         registry.register(a, ar); registry.register(b, br); registry.register(c, cr);
         var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
         try {
            var first = pool.submit(() -> registry.detach(a));
            assertTrue(aEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var second = pool.submit(() -> registry.detach(b));
            assertTrue(bEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            aRelease.countDown();
            if (fail) assertSame(aFailure, assertThrows(java.util.concurrent.ExecutionException.class,
               () -> first.get(5, java.util.concurrent.TimeUnit.SECONDS)).getCause());
            else assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS).found());
            assertEquals(java.util.List.of(ar, cr), registry.orderedAdapters());
            bRelease.countDown();
            if (fail) assertSame(bFailure, assertThrows(java.util.concurrent.ExecutionException.class,
               () -> second.get(5, java.util.concurrent.TimeUnit.SECONDS)).getCause());
            else assertTrue(second.get(5, java.util.concurrent.TimeUnit.SECONDS).found());
            assertEquals(java.util.List.of(ar, br, cr), registry.orderedAdapters());
         } finally {
            aRelease.countDown(); bRelease.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
         }
      }
   }

   private static void awaitCallbackGate(java.util.concurrent.CountDownLatch entered,
                                         java.util.concurrent.CountDownLatch release) {
      entered.countDown();
      try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
      catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
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

   private static final class CallbackRuntime implements TellusGeneratorRuntime {
      private final EarthGeneratorRuntimeAdapter delegate;
      private final Runnable detachCallback;

      private CallbackRuntime(EarthChunkGenerator generator, Runnable detachCallback) {
         this.delegate = new EarthGeneratorRuntimeAdapter(generator);
         this.detachCallback = detachCallback;
      }

      @Override public TellusGeneratorSettingsView settings() { return delegate.settings(); }
      @Override public boolean owns(ChunkGenerator candidate) { return delegate.owns(candidate); }
      @Override public AttachResult attach(ChunkGenerator candidate) { return delegate.attach(candidate); }
      @Override public void tick() { delegate.tick(); }
      @Override public void detach() {
         if (detachCallback != null) detachCallback.run();
         delegate.detach();
      }
      @Override public boolean attached() { return delegate.attached(); }
   }

   private static EarthChunkGenerator earth() {
      return new EarthChunkGenerator(new FixedBiomeSource(plains), EarthGeneratorSettings.DEFAULT);
   }
}
