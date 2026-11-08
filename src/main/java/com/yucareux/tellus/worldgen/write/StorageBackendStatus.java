package com.yucareux.tellus.worldgen.write;

/** Backend result for one atomic transaction or idempotent reload operation. */
public enum StorageBackendStatus {
   COMMITTED,
   ALREADY_COMMITTED,
   NOT_FOUND,
   IDEMPOTENCY_CONFLICT,
   REJECTED,
   FAILED
}
