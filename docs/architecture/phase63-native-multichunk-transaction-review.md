# Phase63 — native multi-chunk staged transaction seam

## Scope

`TellusNativeChunkPersistence.commitBatch` is the first native multi-stage
transaction seam. It accepts complete native NBT candidates for multiple
canonical chunks, sorts positions deterministically, requires an existing FULL
baseline for every chunk before publication, and uses the existing live
ChunkMap queue through the single-chunk commit path.

If a later stage rejects or rolls back, all earlier committed stages are
published back from their exact prior native tags in reverse order. The result
reports `COMMITTED`, `ROLLED_BACK`, `REJECTED`, or `QUARANTINED` and retains
per-stage diagnostics. Duplicate positions and missing baselines are rejected
before the first write.

## Boundary

This is a quiescent in-process staged rollback seam, not crash durability. The
caller must exclude competing world saves for the batch lifetime. A process
crash between stage publication and recovery can still leave a partial native
batch; the durable journal and restart-recovery gate remain next work. No save
format, Codec, generator behavior, or default lifecycle hook changed.

## Verification

The native contract suite now exercises successful two-chunk publication and a
second-stage failure that restores both exact prior tags. It passes on mc1201
and mc1211; full selected cohorts are rerun serially after this change.
