package com.yucareux.tellus.worldgen.write;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable result returned by an injected atomic storage backend. */
public record StorageBackendResult<M>(
   StorageBackendStatus status,
   Optional<StorageTransaction<M>> transaction,
   List<String> diagnostics
) {
   public StorageBackendResult {
      Objects.requireNonNull(status, "status");
      transaction = Objects.requireNonNull(transaction, "transaction");
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      if ((status == StorageBackendStatus.COMMITTED || status == StorageBackendStatus.ALREADY_COMMITTED)
         && transaction.isEmpty()) {
         throw new IllegalArgumentException("successful backend result requires transaction");
      }
      if (status != StorageBackendStatus.COMMITTED && status != StorageBackendStatus.ALREADY_COMMITTED
         && transaction.isPresent()) {
         throw new IllegalArgumentException("unsuccessful backend result cannot contain transaction");
      }
   }

   public static <M> StorageBackendResult<M> committed(StorageTransaction<M> transaction, String diagnostic) {
      return new StorageBackendResult<>(StorageBackendStatus.COMMITTED, Optional.of(transaction), List.of(diagnostic));
   }

   public static <M> StorageBackendResult<M> alreadyCommitted(StorageTransaction<M> transaction, String diagnostic) {
      return new StorageBackendResult<>(StorageBackendStatus.ALREADY_COMMITTED, Optional.of(transaction), List.of(diagnostic));
   }

   public static <M> StorageBackendResult<M> noTransaction(StorageBackendStatus status, String diagnostic) {
      if (status == StorageBackendStatus.COMMITTED || status == StorageBackendStatus.ALREADY_COMMITTED) {
         throw new IllegalArgumentException("status");
      }
      return new StorageBackendResult<>(status, Optional.empty(), List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
   }
}
