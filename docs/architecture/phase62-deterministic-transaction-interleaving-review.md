# Phase62 — deterministic transaction interleaving gate

## Scope

Before extending the generic transaction boundary into a loaded native-world
multi-stage transaction, the same canonical key must have deterministic
cross-instance behavior under concurrent publication. This is a coordinator
contract only; it is not evidence of Minecraft region persistence.

## Established behavior

Two independent `FileBackedAtomicStorageBackend` instances are released through
a `CyclicBarrier` against the same canonical key with different idempotency keys
and payloads. Exactly one operation publishes `COMMITTED`; the other observes
`IDEMPOTENCY_CONFLICT`. Reloading both idempotency keys exposes the same winner
and loser statuses, regardless of which worker reaches the file lock first.

The existing same-payload race remains asserted separately as
`COMMITTED` + `ALREADY_COMMITTED`. The new test keeps the lock, atomic move,
checksum and idempotency semantics unchanged; it only makes the interleaving
acceptance explicit and repeatable.

## Verification

The new contract passes on both mc1201 and mc1211 through the shared source
layer. Native Minecraft world persistence, crash recovery, journal durability,
and default lifecycle registration remain separate gates.
