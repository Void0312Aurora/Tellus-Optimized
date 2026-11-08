package com.yucareux.tellus.worldgen.write;

/** Explicit coordinator outcome including reload and idempotency states. */
public enum StorageCoordinatorStatus {
   COMMITTED,
   ALREADY_COMMITTED,
   RELOADED,
   NOT_FOUND,
   IDEMPOTENCY_CONFLICT,
   BACKEND_REJECTED,
   BACKEND_FAILED,
   NO_COMMIT_DISABLED,
   NO_COMMIT_UNKNOWN,
   NO_COMMIT_QUEUED,
   NO_COMMIT_PARTIAL,
   NO_COMMIT_FALLBACK
}
