package com.yucareux.tellus.worldgen.write;

import java.util.Objects;

/** Immutable commit eligibility policy; it never performs a commit. */
public record StorageCommitPolicy(
   boolean enabled,
   PartialWritePolicy partialWritePolicy,
   StorageFallbackPolicy fallbackPolicy
) {
   public StorageCommitPolicy {
      Objects.requireNonNull(partialWritePolicy, "partialWritePolicy");
      Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
   }

   public static StorageCommitPolicy canonicalDefault() {
      return new StorageCommitPolicy(true, PartialWritePolicy.KNOWN_LAYERS_ONLY,
         StorageFallbackPolicy.NO_COMMIT);
   }

   public static StorageCommitPolicy disabled() {
      return new StorageCommitPolicy(false, PartialWritePolicy.NO_WRITE,
         StorageFallbackPolicy.NO_COMMIT);
   }

   public boolean allows(WriteStatus status) {
      Objects.requireNonNull(status, "status");
      if (!this.enabled || status.noWrite()) return false;
      return status != WriteStatus.PARTIAL || this.partialWritePolicy.allowsKnownLayers();
   }
}
