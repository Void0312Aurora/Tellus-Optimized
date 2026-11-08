package com.yucareux.tellus.worldgen.write;

import java.util.List;
import java.util.Objects;

/** Explicit transaction preparation/commit/reload coordinator. */
public final class StorageTransactionCoordinator<M> {
   private final StorageTransactionBoundary boundary;
   private final AtomicStorageBackend<M> backend;

   public StorageTransactionCoordinator(AtomicStorageBackend<M> backend) {
      this(new StorageTransactionBoundary(), backend);
   }

   public StorageTransactionCoordinator(
      StorageTransactionBoundary boundary, AtomicStorageBackend<M> backend
   ) {
      this.boundary = Objects.requireNonNull(boundary, "boundary");
      this.backend = Objects.requireNonNull(backend, "backend");
   }

   public StorageCoordinatorResult<M> commit(
      WriteContext context, WriteResult<M> result,
      StorageCommitPolicy policy, String operationRevision
   ) {
      StorageTransaction<M> transaction = this.boundary.prepare(context, result, policy, operationRevision);
      if (!transaction.commitEligible()) return noCommit(transaction);
      return fromBackend(this.backend.commit(transaction), false);
   }

   public StorageCoordinatorResult<M> reload(
      CanonicalPersistenceKey key, String idempotencyKey
   ) {
      Objects.requireNonNull(key, "key");
      idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
      return fromBackend(this.backend.reload(key, idempotencyKey), true);
   }

   private StorageCoordinatorResult<M> noCommit(StorageTransaction<M> transaction) {
      StorageCoordinatorStatus status = switch (transaction.status()) {
         case NO_COMMIT_DISABLED -> StorageCoordinatorStatus.NO_COMMIT_DISABLED;
         case NO_COMMIT_UNKNOWN -> StorageCoordinatorStatus.NO_COMMIT_UNKNOWN;
         case NO_COMMIT_QUEUED -> StorageCoordinatorStatus.NO_COMMIT_QUEUED;
         case NO_COMMIT_PARTIAL -> StorageCoordinatorStatus.NO_COMMIT_PARTIAL;
         case NO_COMMIT_FALLBACK -> StorageCoordinatorStatus.NO_COMMIT_FALLBACK;
         case PREPARED -> throw new IllegalStateException("prepared transaction must be backend committed");
      };
      return new StorageCoordinatorResult<>(status, java.util.Optional.empty(), transaction.diagnostics());
   }

   private StorageCoordinatorResult<M> fromBackend(StorageBackendResult<M> result, boolean reload) {
      Objects.requireNonNull(result, "result");
      StorageCoordinatorStatus status = switch (result.status()) {
         case COMMITTED -> reload ? StorageCoordinatorStatus.RELOADED : StorageCoordinatorStatus.COMMITTED;
         case ALREADY_COMMITTED -> StorageCoordinatorStatus.ALREADY_COMMITTED;
         case NOT_FOUND -> StorageCoordinatorStatus.NOT_FOUND;
         case IDEMPOTENCY_CONFLICT -> StorageCoordinatorStatus.IDEMPOTENCY_CONFLICT;
         case REJECTED -> StorageCoordinatorStatus.BACKEND_REJECTED;
         case FAILED -> StorageCoordinatorStatus.BACKEND_FAILED;
      };
      return new StorageCoordinatorResult<>(status, result.transaction(), result.diagnostics());
   }

   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }
}
