package com.yucareux.tellus.worldgen.runtime;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Feature-gated caller-owned lifecycle manager with explicit callbacks only. */
public final class TellusGeneratorRuntimeLifecycleManager {
   private final TellusGeneratorRuntimeRegistry registry;
   private final boolean enabled;
   private final String rolloutDiagnostic;
   private final TellusGeneratorRuntimeLifecycleBridge bridge;

   /** Creates a disabled manager; the default feature flag remains false. */
   public TellusGeneratorRuntimeLifecycleManager(TellusGeneratorRuntimeRegistry registry) {
      this(registry, false);
   }

   /** Legacy explicit boolean constructor retained for compatibility. */
   public TellusGeneratorRuntimeLifecycleManager(
      TellusGeneratorRuntimeRegistry registry, boolean enabled
   ) {
      this(registry, enabled, ignored -> CapabilityReadiness.available());
   }

   public TellusGeneratorRuntimeLifecycleManager(
      TellusGeneratorRuntimeRegistry registry,
      boolean enabled,
      TellusGeneratorRuntimeLifecycleBridge.ReadinessProbe readinessProbe
   ) {
      this.registry = Objects.requireNonNull(registry, "registry");
      this.enabled = enabled;
      this.rolloutDiagnostic = enabled ? "legacy explicit flag enabled" : "legacy explicit flag disabled";
      this.bridge = new TellusGeneratorRuntimeLifecycleBridge(
         registry, enabled ? AttachPolicy.REQUIRE_CAPABILITY_READY : AttachPolicy.DISABLED,
         Objects.requireNonNull(readinessProbe, "readinessProbe"));
   }

   /** Explicit rollout-result constructor; only an ENABLED result enables callbacks. */
   public TellusGeneratorRuntimeLifecycleManager(
      TellusGeneratorRuntimeRegistry registry,
      TellusRuntimeRolloutGate.Result rollout
   ) {
      this(registry, rollout, ignored -> CapabilityReadiness.available());
   }

   public TellusGeneratorRuntimeLifecycleManager(
      TellusGeneratorRuntimeRegistry registry,
      TellusRuntimeRolloutGate.Result rollout,
      TellusGeneratorRuntimeLifecycleBridge.ReadinessProbe readinessProbe
   ) {
      this.registry = Objects.requireNonNull(registry, "registry");
      rollout = Objects.requireNonNull(rollout, "rollout");
      this.enabled = rollout.enabled();
      this.rolloutDiagnostic = rollout.enabled()
         ? "rollout gate enabled" : String.join("; ", rollout.diagnostics());
      this.bridge = new TellusGeneratorRuntimeLifecycleBridge(
         registry, this.enabled ? AttachPolicy.REQUIRE_CAPABILITY_READY : AttachPolicy.DISABLED,
         Objects.requireNonNull(readinessProbe, "readinessProbe"));
   }

   /** Factory form for callers that evaluate the rollout gate before construction. */
   public static TellusGeneratorRuntimeLifecycleManager fromRolloutGate(
      TellusGeneratorRuntimeRegistry registry,
      TellusRuntimeRolloutGate.Result rollout
   ) {
      return new TellusGeneratorRuntimeLifecycleManager(registry, rollout);
   }

   public boolean enabled() {
      return this.enabled;
   }

   /** Explicit generator registration callback; no registration is inferred. */
   public BridgeResult onGeneratorRegistered(ChunkGenerator generator) {
      return this.bridge.attach(Objects.requireNonNull(generator, "generator"));
   }

   /** Explicit server tick callback; no tick hook is installed. */
   public BridgeResult onServerTick() {
      return this.bridge.tick();
   }

   /** Explicit generator unregister callback; detaches and removes exact identity state. */
   public BridgeResult onGeneratorUnregistered(ChunkGenerator generator) {
      generator = Objects.requireNonNull(generator, "generator");
      if (!this.enabled) return disabled("unregister rejected: " + this.rolloutDiagnostic);
      TellusGeneratorRuntimeRegistry.Resolution resolution = this.registry.unregister(generator);
      if (!resolution.found()) return unresolved(resolution);
      return new BridgeResult(BridgeResult.Status.UNREGISTERED, LifecycleEvent.UNREGISTER,
         resolution.runtime(), CapabilityReadiness.available(), "unregistered and detached");
   }

   /** Explicit server stopping callback; detaches and clears all registry entries. */
   public BridgeResult onServerStopping() {
      if (!this.enabled) return disabled("stop rejected: " + this.rolloutDiagnostic);
      int count = this.registry.unregisterAll();
      return new BridgeResult(BridgeResult.Status.UNREGISTERED_ALL, LifecycleEvent.STOP,
         Optional.empty(), CapabilityReadiness.available(), "unregistered " + count + " runtime(s)");
   }

   private BridgeResult disabled(String diagnostic) {
      return new BridgeResult(BridgeResult.Status.DISABLED, LifecycleEvent.REJECTED,
         Optional.empty(), CapabilityReadiness.notReady(diagnostic), diagnostic);
   }

   private static BridgeResult unresolved(TellusGeneratorRuntimeRegistry.Resolution resolution) {
      BridgeResult.Status status = switch (resolution.status()) {
         case AMBIGUOUS -> BridgeResult.Status.AMBIGUOUS;
         case DUPLICATE -> BridgeResult.Status.DUPLICATE;
         default -> BridgeResult.Status.NOT_FOUND;
      };
      return new BridgeResult(status, LifecycleEvent.REJECTED, Optional.empty(),
         CapabilityReadiness.notReady(resolution.diagnostic()), resolution.diagnostic());
   }
}
