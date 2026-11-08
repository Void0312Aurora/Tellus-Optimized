package com.yucareux.tellus.worldgen.write;

import java.util.List;
import java.util.Objects;

/**
 * Pure transaction preparation boundary. It computes identity and eligibility;
 * it never invokes a save, reload, or engine storage operation.
 */
public final class StorageTransactionBoundary {
   public <M> StorageTransaction<M> prepare(
      WriteContext context,
      WriteResult<M> result,
      StorageCommitPolicy policy,
      String operationRevision
   ) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(result, "result");
      Objects.requireNonNull(policy, "policy");
      operationRevision = requireText(operationRevision, "operationRevision");
      CanonicalPersistenceKey key = context.canonicalPersistenceKey();
      String idempotencyKey = key.stableKey() + "|op=" + operationRevision;

      StorageTransactionStatus status;
      List<M> writes = List.of();
      String diagnostic;
      if (!policy.enabled()) {
         status = StorageTransactionStatus.NO_COMMIT_DISABLED;
         diagnostic = "storage commit policy disabled";
      } else if (context.apronMirror()) {
         status = StorageTransactionStatus.NO_COMMIT_FALLBACK;
         diagnostic = "apron is a derived view; only canonical owner may commit";
      } else if (result.status() == WriteStatus.NO_WRITE_UNKNOWN) {
         status = StorageTransactionStatus.NO_COMMIT_UNKNOWN;
         diagnostic = "unknown write result cannot commit";
      } else if (result.status() == WriteStatus.NO_WRITE_QUEUED) {
         status = StorageTransactionStatus.NO_COMMIT_QUEUED;
         diagnostic = "queued write result cannot commit";
      } else if ((result.status() == WriteStatus.PARTIAL
            || result.status() == WriteStatus.NO_WRITE_PARTIAL)
         && !policy.partialWritePolicy().allowsKnownLayers()) {
         status = StorageTransactionStatus.NO_COMMIT_PARTIAL;
         diagnostic = "partial write rejected by storage policy";
      } else if (!policy.allows(result.status())) {
         status = StorageTransactionStatus.NO_COMMIT_FALLBACK;
         diagnostic = "write result is not commit eligible";
      } else {
         status = StorageTransactionStatus.PREPARED;
         writes = result.mappedWrites();
         diagnostic = "prepared canonical transaction; commit remains external";
      }
      return new StorageTransaction<>(status, key, idempotencyKey, writes,
         List.of(diagnostic), context.seamProjectionIdentity());
   }

   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }
}
