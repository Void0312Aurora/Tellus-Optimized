# Phase65 — native world-root lease

## Scope

`TellusNativeWorldLease` uses a sibling `.native-world.lock` under the bound
`tellus/persistence-journal` directory and a nonblocking OS `FileChannel`
`tryLock()`. `commitBatch` holds this lease across baseline preflight, prepared
journal publication, every ChunkMap write/flush/readback, rollback/finalization,
and journal cleanup. Startup `recoverPendingBatches` holds the same lease while
replaying and verifying prepared records.

An already-held lease returns an explicit rejected batch before any native queue
submission. The lock is process-wide, while the existing per-chunk JVM lease
and quarantine map remain in place for same-process guards.

## Boundary

This coordinates cooperating native batch writers and startup recovery at the
world-root boundary. It does not alter Minecraft's own save scheduler or claim
ownership of arbitrary external region writers; callers still need the admitted
world-save quiescence contract. Codec, save format, generator behavior and
default lifecycle registration remain unchanged.

## Verification

The native contract suite covers a held-lease competing batch, prepared-journal
recovery and exact staged rollback. Serial selected cohorts pass on mc1201 and
mc1211 after integration.
