# Tellus Deferred Workstream Implementation Status

## Goal and current boundary

The six workstreams remain part of the same active goal. Contracts and opt-in adapters are prerequisites, not evidence that all production integration is complete. The recovered original parity probes now provide a runnable baseline; externally supplied manifests are no longer the only replay path.

## Status after Round 33

| Workstream | Established implementation | Remaining work |
|---|---|---|
| 1. mc1211 pipeline/writer/materializer | Positioned strata/filler/top/water expansion, inclusive strata ranges, explicit bedrock precedence, status/ownership guards, actual Cube-Net planar and centered Sphere coordinates. Both version adapters pre-map all values, validate target chunk/build bounds, restore original block states on write failure and mark successful chunks unsaved. | Default producer/callsite integration depends on complete production decisions in workstream 3. Custom face-size writers must receive the explicit model. |
| 2. Cube/Sphere sample providers | Canonical/apron/Sphere routing and concrete SphereChunkSampler backend; original factories now preserve the complete slope/geology SurfaceData. New explicit production factories resolve surface policy at the provider boundary. Nonblocking requests return QUEUED before sampler/policy work. | Real-world source coverage and default producer integration still need acceptance. |
| 3. DecisionKernel production rules | Explicit spherical kernel runs native radial scaling/rounding, resolved surface policy and full geological strata with 22 rock mappings and explicit bedrock. Earth surface branch kernel mirrors native base/cover/ocean thresholds; explicit Earth column kernel consumes an injected complete Earth palette and preserves classifier/depth semantics. Both native version materializers delegate spherical height/default-surface/geology helpers to shared rules. | Default DecisionEngine remains legacy stone/plains. Earth source-backed palette/callsites, Cube-Net Earth-equivalent specialized surface callback and spherical biome classification still need migration. |
| 4. FeaturePlan sink | Root guards and version-specific opt-in sinks now use actual canonical cell-center coordinates, complete batch preflight/mapping, readback and reverse rollback on both versions. | Canonical column pipeline does not yet coordinate feature and column writes as one composite transaction; default generator integration remains off. |
| 5. Parity | Original recovered probes freshly reproduce 13,824 columns / 0 differing blocks and seven biome cases / one known bare/BWh mismatch. Both versions now pass 1,176 complete spherical production pipeline columns plus Round 30's 128 materializer combinations. JSONL/CSV comparator and parser tests are available. | Continue replaying original probes through remaining migrations. These fixtures do not establish GIS coverage; a full manifest CLI replay has not been claimed. |
| 6. Save/reload and lifecycle | Caller-owned runtime registry, explicit registration helpers, lifecycle manager, rollout gate, injected atomic storage backend/coordinator contracts and in-memory tests. | Real persistent world backend, real save/reload transactions and default lifecycle hooks remain unimplemented. Current flags/defaults stay opt-in. |

## Evidence and rollback

- Original source recovery and parity rerun: `docs/validation/source-recovery-20261108/README.md`.
- Round 30 implementation, test selection and limitations: `phase30-column-expansion-review.md`.
- Rounds 31–32 production-rule snapshot, test matrix and remaining specialized paths: `phase32-spherical-production-kernel-review.md`.
- Round 33 Earth decision boundary and transactional feature sink review: `phase33-earth-feature-kernel-review.md`.
- Recovery rollback anchor: local branch `recovery/tellus-sources-20261108`, commit `151fded7d751a7c692e0ae371a4cd26bdace2520`.
- Disabling or ceasing explicit pipeline calls retains legacy production behavior. No generator Codec, save encoding or default lifecycle registration changed in Round 30.

Production migration and durable storage are remaining engineering work, not merely an external-manifest acceptance gate.
