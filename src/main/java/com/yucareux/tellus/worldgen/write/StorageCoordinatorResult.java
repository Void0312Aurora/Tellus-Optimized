package com.yucareux.tellus.worldgen.write;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable coordinator result; no implicit retry or storage fallback occurs. */
public record StorageCoordinatorResult<M>(
   StorageCoordinatorStatus status,
   Optional<StorageTransaction<M>> transaction,
   List<String> diagnostics
) {
   public StorageCoordinatorResult {
      Objects.requireNonNull(status, "status");
      transaction = Objects.requireNonNull(transaction, "transaction");
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      if ((status == StorageCoordinatorStatus.COMMITTED
            || status == StorageCoordinatorStatus.ALREADY_COMMITTED
            || status == StorageCoordinatorStatus.RELOADED)
         && transaction.isEmpty()) {
         throw new IllegalArgumentException("successful coordinator result requires transaction");
      }
      if (status != StorageCoordinatorStatus.COMMITTED
         && status != StorageCoordinatorStatus.ALREADY_COMMITTED
         && status != StorageCoordinatorStatus.RELOADED
         && transaction.isPresent()) {
         throw new IllegalArgumentException("non-success coordinator result cannot contain transaction");
      }
   }
}
