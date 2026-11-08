package com.yucareux.tellus.worldgen.runtime;

import java.util.Objects;
import java.util.Set;

/** Snapshot of optional capability readiness; it performs no probing itself. */
public record CapabilityReadiness(
   boolean ready,
   Set<TellusGeneratorSettingsView.Capability> readyCapabilities,
   String diagnostic
) {
   public CapabilityReadiness {
      readyCapabilities = Set.copyOf(Objects.requireNonNull(readyCapabilities, "readyCapabilities"));
      diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
   }

   public static CapabilityReadiness available() {
      return new CapabilityReadiness(true, Set.of(), "ready");
   }

   public static CapabilityReadiness notReady(String diagnostic) {
      return new CapabilityReadiness(false, Set.of(), diagnostic);
   }
}
