package com.yucareux.tellus.worldgen.write;

/** Injected atomic persistence boundary; implementations own storage and synchronization. */
public interface AtomicStorageBackend<M> {
   StorageBackendResult<M> commit(StorageTransaction<M> transaction);

   StorageBackendResult<M> reload(CanonicalPersistenceKey key, String idempotencyKey);
}
