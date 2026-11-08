package com.yucareux.tellus.worldgen.runtime;

/** Explicit opt-in policy for the pure lifecycle bridge. */
public enum AttachPolicy {
   /** Bridge calls are required; no automatic attach is performed. */
   EXPLICIT_ONLY,
   /** Explicit attach additionally requires capability readiness. */
   REQUIRE_CAPABILITY_READY,
   /** All bridge lifecycle operations are rejected. */
   DISABLED;
}
