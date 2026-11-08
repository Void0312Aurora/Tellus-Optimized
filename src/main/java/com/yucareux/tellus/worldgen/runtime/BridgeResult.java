package com.yucareux.tellus.worldgen.runtime;

import java.util.Objects;
import java.util.Optional;

/** Immutable result of one explicit bridge or gated-manager operation. */
public record BridgeResult(
   Status status,
   LifecycleEvent event,
   Optional<TellusGeneratorRuntime> runtime,
   CapabilityReadiness readiness,
   String diagnostic
) {
   public enum Status {
      ATTACHED, ALREADY_ATTACHED, TICKED, DETACHED, DETACHED_ALL, UNREGISTERED, UNREGISTERED_ALL,
      NOT_FOUND, AMBIGUOUS, DUPLICATE, NOT_READY, DISABLED, REJECTED
   }

   public BridgeResult {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(event, "event");
      runtime = Objects.requireNonNull(runtime, "runtime");
      readiness = Objects.requireNonNull(readiness, "readiness");
      diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
      if ((status == Status.ATTACHED || status == Status.ALREADY_ATTACHED)
         && runtime.isEmpty()) {
         throw new IllegalArgumentException("successful runtime operation requires runtime");
      }
   }
}
