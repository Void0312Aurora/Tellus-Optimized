package com.yucareux.tellus.worldgen.runtime;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Explicit, opt-in bridge over the runtime registry. Constructing this object
 * never attaches, ticks, or detaches anything.
 */
public final class TellusGeneratorRuntimeLifecycleBridge {
   @FunctionalInterface
   public interface ReadinessProbe {
      CapabilityReadiness check(TellusGeneratorRuntime runtime);
   }

   private final TellusGeneratorRuntimeRegistry registry;
   private final AttachPolicy policy;
   private final ReadinessProbe readinessProbe;

   public TellusGeneratorRuntimeLifecycleBridge(
      TellusGeneratorRuntimeRegistry registry,
      AttachPolicy policy,
      ReadinessProbe readinessProbe
   ) {
      this.registry = Objects.requireNonNull(registry, "registry");
      this.policy = Objects.requireNonNull(policy, "policy");
      this.readinessProbe = Objects.requireNonNull(readinessProbe, "readinessProbe");
   }

   public BridgeResult attach(ChunkGenerator generator) {
      Objects.requireNonNull(generator, "generator");
      if (this.policy == AttachPolicy.DISABLED) {
         return result(BridgeResult.Status.DISABLED, LifecycleEvent.REJECTED, Optional.empty(),
            CapabilityReadiness.notReady("lifecycle bridge disabled"), "attach rejected by policy");
      }
      TellusGeneratorRuntimeRegistry.Resolution resolution = this.registry.resolve(generator);
      if (!resolution.found()) return unresolved(resolution, LifecycleEvent.REJECTED);
      TellusGeneratorRuntime runtime = resolution.runtime().orElseThrow();
      CapabilityReadiness readiness = this.policy == AttachPolicy.REQUIRE_CAPABILITY_READY
         ? Objects.requireNonNull(this.readinessProbe.check(runtime), "readiness")
         : CapabilityReadiness.available();
      if (!readiness.ready()) {
         return result(BridgeResult.Status.NOT_READY, LifecycleEvent.NOT_READY,
            Optional.of(runtime), readiness, "runtime capability readiness rejected attach");
      }
      TellusGeneratorRuntime.AttachResult attached = this.registry.attach(generator).attachResult().orElseThrow();
      BridgeResult.Status status = attached == TellusGeneratorRuntime.AttachResult.ATTACHED
         ? BridgeResult.Status.ATTACHED : attached == TellusGeneratorRuntime.AttachResult.ALREADY_ATTACHED
            ? BridgeResult.Status.ALREADY_ATTACHED : BridgeResult.Status.REJECTED;
      return result(status, LifecycleEvent.ATTACH, Optional.of(runtime), readiness, attached.name());
   }

   public BridgeResult tick() {
      if (this.policy == AttachPolicy.DISABLED) {
         return result(BridgeResult.Status.DISABLED, LifecycleEvent.REJECTED, Optional.empty(),
            CapabilityReadiness.notReady("lifecycle bridge disabled"), "tick rejected by policy");
      }
      int count = this.registry.tickAttached();
      return result(BridgeResult.Status.TICKED, LifecycleEvent.TICK, Optional.empty(),
         CapabilityReadiness.available(), "ticked " + count + " attached runtime(s)");
   }

   public BridgeResult detach(ChunkGenerator generator) {
      Objects.requireNonNull(generator, "generator");
      if (this.policy == AttachPolicy.DISABLED) {
         return result(BridgeResult.Status.DISABLED, LifecycleEvent.REJECTED, Optional.empty(),
            CapabilityReadiness.notReady("lifecycle bridge disabled"), "detach rejected by policy");
      }
      TellusGeneratorRuntimeRegistry.Resolution resolution = this.registry.detach(generator);
      if (!resolution.found()) return unresolved(resolution, LifecycleEvent.REJECTED);
      return result(BridgeResult.Status.DETACHED, LifecycleEvent.DETACH,
         resolution.runtime(), CapabilityReadiness.available(), "detached");
   }

   public BridgeResult detachAll() {
      if (this.policy == AttachPolicy.DISABLED) {
         return result(BridgeResult.Status.DISABLED, LifecycleEvent.REJECTED, Optional.empty(),
            CapabilityReadiness.notReady("lifecycle bridge disabled"), "detachAll rejected by policy");
      }
      this.registry.detachAll();
      return result(BridgeResult.Status.DETACHED_ALL, LifecycleEvent.DETACH_ALL, Optional.empty(),
         CapabilityReadiness.available(), "detached all ordered runtimes");
   }

   private static BridgeResult unresolved(
      TellusGeneratorRuntimeRegistry.Resolution resolution, LifecycleEvent event
   ) {
      BridgeResult.Status status = switch (resolution.status()) {
         case AMBIGUOUS -> BridgeResult.Status.AMBIGUOUS;
         case DUPLICATE -> BridgeResult.Status.DUPLICATE;
         default -> BridgeResult.Status.NOT_FOUND;
      };
      return result(status, event, Optional.empty(),
         CapabilityReadiness.notReady(resolution.diagnostic()), resolution.diagnostic());
   }

   private static BridgeResult result(
      BridgeResult.Status status, LifecycleEvent event,
      Optional<TellusGeneratorRuntime> runtime, CapabilityReadiness readiness, String diagnostic
   ) {
      return new BridgeResult(status, event, runtime, readiness, diagnostic);
   }
}
