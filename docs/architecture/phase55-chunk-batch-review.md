# Round 55 — Participating chunk leases, whole-chunk failure recovery and ordered native post-stages

## Scope and identity

TellusTargetWriteGuard adds nonblocking, non-reentrant ownership for the same actual target object. It uses a short synchronized IdentityHashMap lookup/update, never target equals/hashCode or a Java monitor held across callbacks. A private-constructor Lease carries target identity, owner thread and active/closed state. requireOwned rejects foreign targets/threads and stale tokens; owner close is idempotent and removes only its current active entry. No current-token getter is exposed. Active entries retain their target only until close; no weak-key/value-reference cycle or idle registry is introduced.

Contention, including same-thread reentry, returns QUEUED without waiting or scheduling work. Different actual objects, including two chunks with the same coordinates, are independent. This is not a world/dimension/position lock and does not bind source manifests, native chunk handles, registry state or durable identities.

## Existing native entrypoints participate explicitly

The two CanonicalPipeline, CompositeColumnWriter, CanonicalColumnAdapter and FeaturePlacementSink implementations use this guard: four native adapter kinds across both versions. ColumnWriter already delegates to the canonical adapter. All old standalone terminal/null/disabled paths remain, with their existing no-touch behavior. The column adapter retains pure materialization before acquiring ownership; a busy valid write is queued before mapper/target reads. Feature-only sinks retain their previous status and exact-flow exclusion behavior.

CanonicalPipeline acquires ownership before planning, so a busy target remains sampling-cold. Its legitimate local lease is passed only to the package-private composite writeOwned entrypoint, which verifies the token instead of attempting reentry. An arbitrary mapper/planner callback cannot retrieve that token and ordinary public write entrypoints still queue. The read-only plan method remains independently callable. Public writeChunk delegates to the new explicit batch helper.

The wrappers release in try-with-resources on all returns and throws, including Error. Removing the wrappers/helper extraction and restoring the pipeline's old final route reproduces all eight prior source files exactly; normalized added blocks match between versions. Native generators, Codec/save encoding, default registration and engine callsites do not change.

## Bounded, explicit whole-chunk batch

TellusNativeChunkBatch accepts1..256 Request values. Each carries its WriteContext, generation, coordinate and sample request. Preflight checks every entry before any source callback: complete profile equality, request/coordinate/seed/geometry agreement, canonical/non-apron target ownership, matching target build bounds and unique physical X/Z columns. Nonblocking batches remain queued/source-cold. Whole-target height×256 must fit the1,048,576-cell hard snapshot budget; mapped-write budgets still apply separately to each composite column, not as a new whole-batch aggregate limit. Only a real ProtoChunk is accepted for this batch contract.

One lease covers preflight, all planning, snapshots, every column write and recovery. Every plan must match its request and be complete AVAILABLE; UNKNOWN/QUEUED/PARTIAL stops before earlier columns are written. Planning callbacks must be read-only. This preplanning is not an atomic GIS/source snapshot; sources can still change independently of the target lease.

After all plans are known, the helper captures every block state in the target chunk and the existing NativePostProcessing snapshot: original list references, null slots, ordered duplicate-sensitive packed positions and dirty flag. The full snapshot matters because a feature in one request may write another physical column. Columns then use the shared composite writer directly under the outer lease, not a public native adapter that would attempt reentry.

A stopped column or RuntimeException causes outer restoration of the whole original chunk, including previously completed columns and cross-column features, followed by marker/dirty restoration. Every changed block is attempted in reverse snapshot order and read back; metadata restoration is attempted even after block restoration errors. RuntimeException or Error during restoration is aggregated by throwable identity without self-suppression. Incomplete recovery throws rather than returning a successful no-write result. An Error during writing is restored and the same Error is rethrown; pre-write planning/snapshot Errors release the lease without restorative writes.

No failed result exposes earlier WRITTEN column results that have since been rolled back. Successful Result values contain immutable per-column observations. The batch result is deliberately a distinct type with no StorageTransactionBoundary overload: it is not a flat canonical-column storage payload. Individual observations do not constitute a new persisted multi-column transaction or coordinate-bearing save format.

## Ordered flow then badlands, without property loss

TellusFeaturePlanner.sequence copies its stage array and invokes stages in explicit order. It preserves exact placement objects, including typed flow properties and marker intent. UNKNOWN/QUEUED inputs remain stage-cold; a later blocked stage returns that blocked plan and discards all earlier candidate placements before any write. Empty stages retain diagnostics and yield EMPTY. This is an explicit caller composition, not a new default planner.

The mixed native integration composes the generator's existing flow planner followed by its badlands planner. A genuine local geodetic river/waterfall solver and stepped DEM use the same resolver for sampled terrain and native flow. A west/east candidate-cover mask creates wet and dry columns in the same physical chunk. A probe only locates an actual falling curtain; the final split is frozen and a new generator avoids reusing its terrain grid. An independent real test biome registry supplies IS_BADLANDS membership. A bounded64-seed fixture search observes a real tagged dry-column result at density0.4, rather than forcing the mixer or equating biome ids with tags.

For each version, all256 columns are batched in native localZ/localX order and compared with the real native full fill. The same chunk contains64 positive-flow columns/544 flowing cells and128 tagged badlands columns/4,480 band cells. All98,304 block states and the complete nullable ordered post-processing arrays match, including preseeded duplicates. The selected biome seed is3 in the recorded run. Raw wet exclusion keeps the two post-stages disjoint within an individual column; their coexistence is genuinely tested in one chunk, not only in separate scenes.

A separate mixed-chunk failure occurs at mapping call20,000, after earlier native-target mutations are observed. The whole batch returns no successful columns and restores all target blocks, original marker references/null slots/duplicate contents and the original dirty flag. The lease can then be reacquired.

## Deterministic verification

Seven pure lease tests cover object identity versus equals, non-reentry, independent targets, latch-controlled other-thread contention, owner-thread/foreign/stale proofs, exception/Error release and private token construction. No sleeps or busy-polling are used.

Fourteen actual ProtoChunk batch tests cover success, immutable result shape, earlier-column flow rollback, a feature outside both requested base columns, complete plan preflight, aliases/profiles/bounds/apron/nonblocking guards, snapshot budget, planning RuntimeException/Error cleanup, writing Error restoration/rethrow, deterministic contention with a native composite writer, different actual objects, same-thread mapper reentry and metadata Error/incomplete rollback reporting. The metadata-error test proves every block is restored before a marker-list failure is reported; it does not claim metadata or dirty state recovered when their own restoration operation throws.

Six pure sequence tests verify order, typed placement identity, later-stage blocking, empty diagnostics, stage-cold terminal inputs, copied arrays, immutability and null contracts. Three mixed native tests cover the full same-chunk comparison, a late whole-batch rollback and five participating public entry paths being sampling/mapping-cold under a held lease, followed by successful retry.

The first focused run passed28 tests and failed one test fixture before the sequence ran: QUEUED sample construction requires a nonblocking request. The fixture now supplies SamplingResolution.nonBlocking; the consistency validator and all production branches are unchanged. The original failing XML is archived.

## Limits and remaining goal gates

Participating writes now enforce exclusion through the whole protected interval, closing the prior caller-only non-reentrancy gap for these adapters. Direct ChunkAccess mutation, the generic composite Target API, native engine generation and unrelated mod writers do not participate automatically. Their callers must still arrange engine quiescence. Concurrent or reentrant out-of-band mutation and different object aliases for one persisted world chunk are not isolated by this identity guard.

This is block/marker/dirty failure recovery, not rollback of chunk status, biome palettes, lighting, scheduled ticks, external callbacks, disk files or source caches. Restoration errors are reported and the lease is released; no target quarantine/lifecycle recovery manager is introduced, so callers must reconcile an incomplete recovery before continuing.

Independent review also found that calling an overridden Throwable.getMessage while formatting a post-write exception could itself throw before restoration. The new batch diagnostics now use only the exception class before cleanup; the pre-write formatter follows the same rule. A dedicated test covers hostile message getters on runtime and Error paths, including a pre-write planner case, earlier-column restoration, same-Error rethrow and lease cleanup. The old-source full run was stopped after this finding and is not used as final-cohort evidence. This does not promise recovery from a process crash or every possible VM allocation failure outside the protected write loop.

Final selected regressions add30 tests to Round54 and ran serially: mc1201 371/371 and mc1211 364/364, with zero failures/errors/skips. [Machine evidence](../validation/round55-chunk-batch/test-evidence.json) stores exact observed totals, suites, XMLs, all retained parity observations, source hashes, guard-restoration proof and mixed-stage counters. Original13,824/0, canonical256/0 and seven/one-known-biome probes remain the mc1201 inventory; no nonexistent mc1211 original probe is claimed.

Rollback parent: recovery/tellus-round54-inland-flow (6b7898f1a5b05ee4245ecfc68182caaad3e396f2). The checkpoint uses an alternate index and actually restores all scoped paths to verify the exact parent tree, while retaining the real branch, HEAD, index and worktree. No world-save migration or default lifecycle registration is introduced.

Live GIS/server acceptance, source-stable complete production stages, later caves/decorations, vertical biome writes, real persistent-world save/reload, world-key-aware runtime coordination/quarantine and default lifecycle remain open. The existing non-default-heightOffset Cube Codec sea drift76→88 is unchanged. The overall goal remains active and all new paths stay explicit opt-in.
