package com.yucareux.tellus.worldgen.write;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable prepared transaction descriptor with no storage side effects. */
public record StorageTransaction<M>(
   StorageTransactionStatus status,
   CanonicalPersistenceKey key,
   String idempotencyKey,
   List<M> writes,
   List<String> diagnostics,
   Optional<SeamProjectionIdentity> seamProjection
) {
   public StorageTransaction {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(key, "key");
      idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
      writes = List.copyOf(Objects.requireNonNull(writes, "writes"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      seamProjection = Objects.requireNonNull(seamProjection, "seamProjection");
      if (!status.commitEligible() && !writes.isEmpty()) {
         throw new IllegalArgumentException("non-committable transaction cannot contain writes");
      }
   }

   public boolean commitEligible() {
      return this.status.commitEligible();
   }

   public boolean idempotentlyMatches(StorageTransaction<?> other) {
      return other != null && this.idempotencyKey.equals(other.idempotencyKey)
         && this.key.equals(other.key);
   }

   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }
}
