package com.yucareux.tellus.worldgen.runtime;

import java.util.Collections;
import java.util.Set;

/** Read-only runtime projection for a generator adapter. */
public interface TellusGeneratorRuntimeView {
   TellusGeneratorSettingsView settings();

   default TellusGeneratorSettingsView.Kind kind() {
      return this.settings().kind();
   }

   default Set<TellusGeneratorSettingsView.Capability> capabilities() {
      return Collections.unmodifiableSet(this.settings().capabilities());
   }

   default boolean supports(TellusGeneratorSettingsView.Capability capability) {
      return this.capabilities().contains(capability);
   }

   /** Phase 1 adapters have no live manager attachment; this is a lifecycle marker. */
   default boolean attached() {
      return false;
   }
}
