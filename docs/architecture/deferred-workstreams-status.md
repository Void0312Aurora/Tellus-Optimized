# Tellus Deferred Workstream Implementation Status

## Goal and current boundary

The six workstreams remain part of the same active goal. Contracts and opt-in adapters are prerequisites, not evidence that all production integration is complete. The recovered original parity probes now provide a runnable baseline; externally supplied manifests are no longer the only replay path.

## Status after Round 36

| Workstream | Established implementation | Remaining work |
|---|---|---|
| 1. mc1211 pipeline/writer/materializer | Positioned strata/filler/top/water expansion, inclusive strata ranges, explicit bedrock precedence, status/ownership guards, actual Cube-Net planar and centered Sphere coordinates. Both version adapters pre-map all values, validate target chunk/build bounds, restore original block states on write failure and mark successful chunks unsaved. | Default producer/callsite integration depends on complete production decisions in workstream 3. Custom face-size writers must receive the explicit model. |
| 2. Cube/Sphere sample providers | Canonical/apron/Sphere routing and concrete SphereChunkSampler backend; original factories now preserve the complete slope/geology SurfaceData. New explicit production factories resolve surface policy at the provider boundary. Nonblocking requests return QUEUED before sampler/policy work. | Real-world source coverage and default producer integration still need acceptance. |
| 3. DecisionKernel production rules | Explicit spherical kernel runs native radial scaling/rounding, resolved surface policy and full geological strata with 22 rock mappings and explicit bedrock. Native Earth base/cover/ocean and Cube Earth-equivalent cover branches now delegate to shared Earth surface kernel thresholds while retaining original rolls, materials and depths. Complete six-field native palette parity found and repaired Round33's STONY_PEAKS strata-flag regression. Both native version materializers delegate spherical height/default-surface/geology helpers to shared rules. | Default DecisionEngine remains legacy stone/plains. The explicit Earth column bridge still has generic height/conservative stone strata; full source-backed Earth column decisions, richer Cube semantic-provider surface/biome context and spherical biome classification remain. |
| 4. FeaturePlan sink | Both explicit CanonicalPipeline.write paths now apply terrain and FeaturePlan in one shared block-state transaction: complete combined preflight/mapping/snapshot, effective-state conflict policies, deterministic duplicate collapse, readback and reverse rollback across both phases. Two native adapters and 23 composite regression cases per version verify actual application and failure restoration. | Caller-owned single-chunk block-state scope only; metadata, disk persistence, multi-chunk coordination and durable edit protection remain outside this transaction. Default generator/lifecycle integration remains off. |
| 5. Parity | Original recovered probes freshly reproduce 13,824 columns / 0 differing blocks and seven biome cases / one known bare/BWh mismatch. Both versions now pass 1,176 complete spherical production pipeline columns plus Round 30's 128 materializer combinations. JSONL/CSV comparator and parser tests are available. | Continue replaying original probes through remaining migrations. These fixtures do not establish GIS coverage; a full manifest CLI replay has not been claimed. |
| 6. Save/reload and lifecycle | Caller-owned runtime registry, explicit registration helpers, lifecycle manager, rollout gate, injected atomic storage backend/coordinator contracts and in-memory tests. | Real persistent world backend, real save/reload transactions and default lifecycle hooks remain unimplemented. Current flags/defaults stay opt-in. |

## Evidence and rollback

- Original source recovery and parity rerun: `docs/validation/source-recovery-20261108/README.md`.
- Round 30 implementation, test selection and limitations: `phase30-column-expansion-review.md`.
- Rounds 31–32 production-rule snapshot, test matrix and remaining specialized paths: `phase32-spherical-production-kernel-review.md`.
- Round 33 Earth decision boundary and transactional feature sink review: `phase33-earth-feature-kernel-review.md`.
- Rounds 34–35 actual composite terrain/feature pipeline, serial parity and rollback review: `phase35-composite-column-feature-review.md`; final test selection passes mc1201 122/122 and mc1211 115/115, with the original 13,824/0 and seven-case/one-known-mismatch observations freshly checked.
- Round 36 native Earth/Cube surface kernel and six-field palette parity: `phase36-earth-cube-surface-kernel-review.md`; mc1201 184/184 and mc1211 177/177; native Earth 204,000/0 plus 3,160/0 and Cube 81,900/0 per version. The Round33 built-up strata-flag regression has an archived failing assertion stage and a passing repaired stage.
- Recovery rollback anchor: local branch `recovery/tellus-sources-20261108`, commit `151fded7d751a7c692e0ae371a4cd26bdace2520`.
- Disabling or ceasing explicit pipeline calls retains legacy production behavior. No generator Codec, save encoding or default lifecycle registration changed in Round 30.

Production migration and durable storage are remaining engineering work, not merely an external-manifest acceptance gate.
