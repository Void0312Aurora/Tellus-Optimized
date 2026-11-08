package com.yucareux.tellus.worldgen.write;

/** Fixed policy for incomplete semantic decisions. */
public enum PartialWritePolicy {
   /** Emit only fields proven by the decision; never synthesize unknown layers. */
   KNOWN_LAYERS_ONLY,
   /** Keep the column untouched until a complete decision is available. */
   NO_WRITE;

   public boolean allowsKnownLayers() {
      return this == KNOWN_LAYERS_ONLY;
   }
}
