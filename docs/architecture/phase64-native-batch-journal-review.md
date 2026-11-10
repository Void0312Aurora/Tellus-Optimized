# Phase64 — crash-durable native batch journal

## Scope

`TellusNativeBatchJournal` persists a bounded `PREPARED` record before the
first native batch write. The record binds the exact world root, dimension,
seed, generator type/profile, vertical range and sea level, and stores each
chunk's complete prior/proposed native NBT as checksummed SNBT. Publication is
file-channel forced, atomically renamed, and followed by a directory force when
the platform exposes one.

After every native stage succeeds, the journal is marked `COMMITTED` and then
removed. If a process stops while a `PREPARED` record remains, native
`open(...)` invokes `recoverPendingBatches()` before normal probe/lifecycle work:
it restores every prior tag through the existing ChunkMap queue, flushes,
verifies exact NBT readback, and removes the journal. A finalized journal is
removed without replay. Binding mismatch, checksum failure or readback mismatch
halts recovery and retains the record for quarantine investigation.

## Boundary

This closes the durable prepared-record/restart-recovery seam for staged native
transactions. Competing save writers still require explicit quiescence during a
batch, and the real-world nonempty block-entity/tick/marker matrix remains a
separate acceptance gate. Codec, save format, generation rules and default
lifecycle registration are unchanged.

## Verification

The native contract suite covers journal creation, checksum parsing, exact
prepared recovery and cleanup. Serial selected cohorts pass on mc1201 and
mc1211 after integration.
