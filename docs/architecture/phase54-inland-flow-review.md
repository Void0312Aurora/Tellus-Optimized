# Round 54 — Exact Cube inland flow and block/marker failure recovery

## Typed state, not a material approximation

SemanticMaterial and the existing native default-state/reverse mapper remain unchanged. WaterFlowState is a new explicit1..8 value attached to FeaturePlacement through an optional component; the established13-argument constructor and ordinary factories retain empty flow state. The canonical-flow factory creates WATER, FILL, OWNER_ONLY placements at absolute Y, with a required post-processing effect. Direct construction also rejects apron ownership, non-water materials and unsupported operations/conflict policies.

SemanticMapper remains a functional interface. Its new default mapFeature delegates ordinary placements to the old map, but stops on exact flow rather than silently mapping it to source water. Both native version mappers explicitly set LiquidBlock.LEVEL. The plain reverse mapper still rejects all non-default properties; the old material enum prefix and53-default mappings remain intact.

The shared legacy FeaturePlacementSinkGuard rejects exact flow unless its caller explicitly requests flow-capable validation. Both standalone feature-only native sinks remain unchanged and therefore stop typed flow before mapping/writes. Only the composite terrain+feature path opts into the extended transaction.

## Production flow rules and owned input

TellusCubeInlandFlowDecisionKernel is Minecraft-free. It plans a positive-flow band using the native falling-only terrain+1 lower bound, inclusive min/max clipping, level clamp1..8 and ascending write order. It retains native early no-flow behavior; no water/flow<=0 invents no cells. Frozen resolved-input tests cover11,232 combinations,2,376 nonempty bands and406,410 written cells, with zero differences.

Both native Cube generators delegate only their flow range/level arithmetic to this kernel. Their raw face terrainPoint(address,1), exact canonical-center resolver query, inland-water/hasWater/positive-level guard, native block setter and ProtoChunk marker calls remain. In particular, waterY still comes from the raw native SurfaceData.waterSurfaceMeters, not the resolver sample's water height; terrain height is evaluated only for a falling result. There is no added enableWater guard or constructor/Codec change.

Both generators also expose identical explicit semanticInlandFlowFeaturePlanner factories. They validate column/sample/generation identity, terminal/complete state, full current native profile/seed and canonical ownership before I/O. A missing resolver is EMPTY. Fresh raw face data and the same real resolver are used, not cached provider water values; missing raw elevation yields UNKNOWN. The resulting canonical flow placement retains exact level and the per-written-cell marker requirement. This does not install a default producer or combine later stages implicitly.

## Block states and marker lists as two prepared resources

TellusCompositeColumnWriter.Target gains an optional PostProcessing capability. Old targets default to no capability, so an exact-flow plan stops before writes rather than losing metadata. Both native adapters expose it only for ProtoChunk; a generic LevelChunk does not gain a pretend marker implementation.

Preparation retains existing identity/owner/budget/bounds/conflict guards, maps the entire terrain+feature batch and snapshots original blocks. After ordered conflict resolution, only final-winning flow cells require markers: duplicate flow placements collapse to the final level and one append; an accepted later ordinary placement removes that cell's flow effect. A conservative capability/mapping check still applies to any typed-flow input, including one later overwritten.

Before the first mutation, the native marker capability captures the actual ShortList[] slots, original non-null list references, ordered packed-short contents including duplicates, and the chunk's dirty flag. All blocks are written and read back first. Each winning flow cell then appends one marker, and occurrence count must increase by exactly one; merely finding a preexisting marker does not establish success. The dirty mark is last.

On a block, marker or dirty failure, the shared writer attempts every affected block restoration in reverse order, including a setter that mutated before throwing, then attempts marker/dirty restoration. It aggregates restoration failures and reports incomplete rollback rather than returning a successful no-write result. Native metadata restoration verifies exact original references, null slots, contents/order/duplicates and dirty flag. A metadata adapter restoration exception is an incomplete rollback; its implementation is not claimed to attempt every later section after an unexpected failing list operation.

**Isolation boundary:** this is local failure recovery, not concurrent transaction isolation. The caller must exclusively own the entire target chunk and must not reenter generation/writing between snapshot and completion. Even disjoint columns share the full marker array and dirty flag; an overlapping or nested unrelated writer could have its metadata restored away. No lock/reentrancy guard or cross-engine exclusion is introduced. Default/runtime acceptance therefore still requires caller coordination and a real world lifecycle. Status, heightmaps, lighting, scheduled ticks, external callbacks and disk transactions are not general-purpose snapshotted resources in this contract.

## Persisted payload eligibility stays conservative

WriteResult gains a transientPostProcessing flag, with source-compatible3/4-argument constructors defaulting false; withPersistenceIdentity preserves it. Successful composite writes set it when final flow cells require marker effects. CanonicalPipeline returns that result without repackaging it.

StorageTransactionBoundary explicitly returns NO_COMMIT_FALLBACK and an empty payload for such results, because its flat write payload carries no per-cell positions or marker lists (the canonical column identity remains in its key). An absent persistenceIdentity alone would not have stopped its old eligibility path. No storage format, record encoding, version or native world-save Codec is migrated here. The flag means the current generic payload is incomplete for this operation; it does not mean native Minecraft markers need no persistence.

## Real native tests and independent resolved-state oracles

CubeNetInlandFlowPipelineTest supplies a COMPLETE local geodetic river/upper-pool/lower-pool/waterfall/centerline fixture and a stepped180/120 DEM at1m scale. A genuine CubeNetInlandWaterResolver samples a raw spherical terrain source; resolver.apply feeds the generator and that same resolver serves the native flow pass. No resolved Sample is injected and no remote GIS input is claimed.

A bounded Earth-column search requires actual positive ordinary flow and falling flow. They happen to occupy the same planar chunk in this fixture, so the test deduplicates it rather than claiming two chunks. All256 semantic columns are written in native localZ/localX order, not just selected sample columns. Native fill and semantic pipeline match all98,304 block states, including1,088 non-source water cells with observed levels7/8. The complete nullable ordered post-processing arrays also match, including preseeded duplicate markers. This whole-array claim depends on writing every native column in the correct marker order.

Separate typed-placement tests verify all eight LEVEL values through actual version pipelines:24 exact water states and24 markers; default reverse mapping still rejects those states. Further native tests cover original array/list identity/null/order/duplicates/dirty restoration, foreign snapshots, actual ProtoChunk rollback after marker no-op, append-then-throw and dirty mutation failure, plus the unchanged standalone feature sink's no-loss rejection.

Twelve generic transaction tests cover all-level success, default capability/mapper guards, preparation conflict, mutate-then-throw block writes, post-append failure, no-op against preexisting duplicates, dirty failure with both initial flags, final-winner/dedup semantics, source-cold terminal paths, constructor/legacy compatibility, flat-storage rejection and combined block/metadata restoration exceptions. They use pure memory targets and establish failure contracts, not real persistence or concurrent isolation.

## Regression, rollback and remaining work

The final selected suite inventory adds22 tests to Round53 and runs serially across mc1201/mc1211. [Evidence](../validation/round54-inland-flow/test-evidence.json) records suite inventories, source hashes, prior observations, exact flow counters, full marker-array parity and static source boundaries. An initial test-only compilation fix adds the checked exception declaration required by the existing ProtoChunk construction helper; no production branch is changed by that fix.

Removing each new native planner and restoring its one flow-math segment restores the entire prior Cube generator. Native raw-read/query/early-height prefix and state/marker write tail remain exact; both versions' added factories and native flow methods match. Version adapter extensions preserve old methods outside optional marker capability and feature-aware mapping. Independent review covers code, fixture validity, source scope, payload eligibility and caller exclusivity.

Rollback parent: recovery/tellus-round53-cube-badlands (c8509ee138b99c169387e53cd90dcba0b3dac53f). The isolated-index checkpoint stages only the enumerated production/tests/docs/evidence paths and verifies scoped restoration produces the exact parent tree without changing the real branch, HEAD, index or worktree. No world-save migration is introduced.

The controlled fixture is not live GIS/server/world save/reload acceptance. Exact flow is now available explicitly, but multi-stage composition/source stability, chunk-level exclusion, later caves/decorations, vertical biome writes, real persistent-world transactions, default lifecycle and rollback-aware runtime registration still need work. The existing non-default-heightOffset Cube Codec sea drift76→88 is unchanged and remains a save-compatibility gate. The overall goal remains active and lifecycle/default rollout remains opt-in.
