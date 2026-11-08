package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** In-memory contract tests for explicit atomic coordination and idempotent reload. */
class StorageTransactionCoordinatorTest {
   private static final double SCALE = 1.0;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromCubeNetCanonical(
      MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
      new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
      11L, SCALE, 64, -64, 320,
      new GeometryIdentity("tellus:storage-coordinator", "v1", "projection", "noise", SCALE));

   @Test
   void injectedBackendCommitsIdempotentlyAndReloads() {
      InMemoryBackend backend = new InMemoryBackend();
      StorageTransactionCoordinator<String> coordinator = new StorageTransactionCoordinator<>(backend);
      WriteContext context = WriteContext.canonical(COORDINATE, GENERATION, "storage");
      WriteResult<String> result = new WriteResult<>(WriteStatus.WRITTEN, java.util.List.of("STONE"),
         java.util.List.of("fixture"));

      StorageCoordinatorResult<String> first = coordinator.commit(
         context, result, StorageCommitPolicy.canonicalDefault(), "op-v1");
      StorageCoordinatorResult<String> repeat = coordinator.commit(
         context, result, StorageCommitPolicy.canonicalDefault(), "op-v1");
      StorageCoordinatorResult<String> conflict = coordinator.commit(
         context, result, StorageCommitPolicy.canonicalDefault(), "op-v2");
      StorageCoordinatorResult<String> reload = coordinator.reload(
         context.canonicalPersistenceKey(), first.transaction().orElseThrow().idempotencyKey());

      assertEquals(StorageCoordinatorStatus.COMMITTED, first.status());
      assertEquals(StorageCoordinatorStatus.ALREADY_COMMITTED, repeat.status());
      assertEquals(StorageCoordinatorStatus.IDEMPOTENCY_CONFLICT, conflict.status());
      assertEquals(StorageCoordinatorStatus.RELOADED, reload.status());
      assertEquals(1, backend.commits);
      assertEquals(first.transaction().orElseThrow(), reload.transaction().orElseThrow());
   }

   @Test
   void noWriteStatusesNeverReachInjectedBackend() {
      InMemoryBackend backend = new InMemoryBackend();
      StorageTransactionCoordinator<String> coordinator = new StorageTransactionCoordinator<>(backend);
      WriteContext context = WriteContext.canonical(COORDINATE, GENERATION, "no-write");
      StorageCoordinatorResult<String> unknown = coordinator.commit(
         context, WriteResult.unknown("unknown"), StorageCommitPolicy.canonicalDefault(), "op");
      StorageCoordinatorResult<String> disabled = coordinator.commit(
         context, new WriteResult<>(WriteStatus.WRITTEN, java.util.List.of("STONE"), java.util.List.of("fixture")),
         StorageCommitPolicy.disabled(), "disabled");

      assertEquals(StorageCoordinatorStatus.NO_COMMIT_UNKNOWN, unknown.status());
      assertEquals(StorageCoordinatorStatus.NO_COMMIT_DISABLED, disabled.status());
      assertEquals(0, backend.commits);
      assertTrue(unknown.transaction().isEmpty());
   }

   private static final class InMemoryBackend implements AtomicStorageBackend<String> {
      private final Map<String, StorageTransaction<String>> transactions = new HashMap<>();
      private int commits;

      @Override
      public StorageBackendResult<String> commit(StorageTransaction<String> transaction) {
         String key = transaction.key().stableKey();
         StorageTransaction<String> existing = this.transactions.get(key);
         if (existing != null) {
            if (existing.idempotencyKey().equals(transaction.idempotencyKey())) {
               return StorageBackendResult.alreadyCommitted(existing, "idempotent replay");
            }
            return StorageBackendResult.noTransaction(StorageBackendStatus.IDEMPOTENCY_CONFLICT,
               "same canonical key has a different idempotency key");
         }
         this.commits++;
         this.transactions.put(key, transaction);
         return StorageBackendResult.committed(transaction, "stored atomically");
      }

      @Override
      public StorageBackendResult<String> reload(CanonicalPersistenceKey key, String idempotencyKey) {
         StorageTransaction<String> existing = this.transactions.get(key.stableKey());
         if (existing == null) return StorageBackendResult.noTransaction(
            StorageBackendStatus.NOT_FOUND, "transaction not found");
         if (!existing.idempotencyKey().equals(idempotencyKey)) return StorageBackendResult.noTransaction(
            StorageBackendStatus.IDEMPOTENCY_CONFLICT, "reload idempotency mismatch");
         return StorageBackendResult.committed(existing, "reloaded transaction");
      }
   }
}
