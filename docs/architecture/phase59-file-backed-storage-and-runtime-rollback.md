# Phase 59 — Opt-in file persistence and runtime rollback

## Scope

The persistence slice is deliberately opt-in. `FileBackedAtomicStorageBackend`
implements the existing `AtomicStorageBackend` boundary without adding Minecraft
hooks, default providers, or live-world behavior. Callers choose a directory and
provide codecs for their mapped write type. This backend is plain-file transaction
storage only; it is not evidence for Minecraft world save, reload, region, palette,
light, tick, or crash-recovery acceptance.

## Transaction format

One canonical persistence key maps to one bounded, checksummed v2 UTF-8 record.
The parser rejects malformed UTF-8, invalid Base64, invalid counts, oversized
records/fields, missing fields, trailing content, and checksum mismatch. A commit
serializes and bounds the complete record, forces the temporary file through its
`FileChannel`, and publishes only with `ATOMIC_MOVE`; there is no copy/replace
fallback. A unique tokenized temporary name is used for every attempt. Per-key
JVM lock stripes plus a per-key `.locks/<hash>.lock` file serialize backend
instances and cooperating processes.

After publication the backend attempts to force the containing directory. Some
platforms do not expose directory channels; in that case the atomic-file result
is retained with an explicit diagnostic. Therefore this backend establishes
atomic file replacement and forced file data, but does not claim universal
crash-durable directory metadata. `recover()` removes only tokenized temporary
names created by this backend and only after obtaining the corresponding key
lock; unrelated `.tmp` files and active locked temporary files are retained.

Reload verifies the magic/version, canonical key, idempotency key, exact record
body and checksum before returning the transaction. Repeating the same canonical
key and idempotency key with identical encoded content returns
`ALREADY_COMMITTED`; a different payload with the same idempotency key is an
`IDEMPOTENCY_CONFLICT`, as is a different idempotency key for an occupied key.

Malformed records return `FAILED`, rather than being treated as `NOT_FOUND`.
A non-committable prepared result never creates a file.

## Runtime registration rollback

`TellusGeneratorRuntimeRegistry.registerWithRollback` returns a caller-owned
`RegistrationRollback`. The token does not attach or tick a runtime. If a later
setup phase fails, the caller explicitly invokes `rollback()` (or closes the
token) to detach and remove only the exact generator/runtime identity. Rollback
is idempotent and cannot remove a replacement registration.

## Verification

Pure backend fixtures cover commit/reload, exact idempotency-content conflicts,
strict malformed/trailing/checksum rejection, bounded serialization and reads,
separate-instance concurrent commit, tokenized stale recovery, unrelated-temp
preservation, active-lock preservation, and rejection of non-committable records.
Runtime fixtures cover exact-entry rollback, failed-registration no-ops, reentrant
mutation admission, stale-token isolation, RuntimeException/Error identity and
retry, batch failure recovery, and deterministic concurrent restoration of two
distinct registrations. Entries carry immutable monotonic ordinals so restoration
preserves registration order even when separate callbacks finish out of order.
Runtime callbacks hold neither the registry monitor nor the rollback-token
monitor; a concurrent retry of an admitted token returns without waiting.
Strict UTF-8 encoding rejects unpaired surrogates before lock/temp/publication;
records are never silently normalized by replacement encoding.
