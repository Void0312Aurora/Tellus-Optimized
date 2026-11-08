package com.yucareux.tellus.worldgen.runtime;

/** Explicit callback events emitted only by caller-invoked runtime manager methods. */
public enum LifecycleEvent {
   ATTACH,
   TICK,
   DETACH,
   DETACH_ALL,
   UNREGISTER,
   STOP,
   REJECTED,
   NOT_READY;
}
