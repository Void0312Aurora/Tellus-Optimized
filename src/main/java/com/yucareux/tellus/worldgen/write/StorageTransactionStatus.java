package com.yucareux.tellus.worldgen.write;

/** Pure transaction preparation outcome; COMMIT is intentionally absent. */
public enum StorageTransactionStatus {
   PREPARED,
   NO_COMMIT_DISABLED,
   NO_COMMIT_UNKNOWN,
   NO_COMMIT_QUEUED,
   NO_COMMIT_PARTIAL,
   NO_COMMIT_FALLBACK;

   public boolean commitEligible() {
      return this == PREPARED;
   }
}
