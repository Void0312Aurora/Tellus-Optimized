# Tellus Deferred Workstream Implementation Status

## Goal and current boundary

The six workstreams remain part of the same active goal. Contracts and opt-in adapters are prerequisites, not evidence that all production integration is complete. The recovered original parity probes now provide a runnable baseline; externally supplied manifests are no longer the only replay path.

## Status after Round 30

| Workstream | Established implementation | Remaining work |
|---|---|---|
| 1. mc1211 pipeline/writer/materializer | Positioned strata/filler/top/water expansion, inclusive strata ranges, explicit bedrock precedence, status/ownership guards, actual Cube-Net planar and centered Sphere coordinates. Both version adapters pre-map all values, validate target chunk/build bounds, restore original block states on write failure and mark successful chunks unsaved. | Default producer/callsite integration depends on complete production decisions in workstream 3. Custom face-size writers must receive the explicit model. |
| 2. Cube/Sphere sample providers | Explicit canonical/apron/Sphere routing and concrete SphereChunkSampler backend; nonblocking requests return QUEUED without sampler work. | Coverage and default producer integration still need verification in the complete pipeline. |
| 3. DecisionKernel production rules | Cube surface adapter; Earth palette contract, climate sample, provider/exact/fallback classifier adapter and explicit climate-enabled pipelines. | Default kernel still uses simplified height/stone/plains decisions; Earth/Cube/Sphere production rules and generator callsites have not all migrated. Earth and spherical surface depth conventions differ and require explicit conversion. |
| 4. FeaturePlan sink | Root guards and version-specific opt-in sinks exist. | Canonical column pipeline does not yet apply placements. Sinks still need actual engine-coordinate mapping, complete batch preflight and failure/rollback verification before production integration. |
| 5. Parity | Original recovered source probes freshly reproduce 13,824 columns / 0 differing blocks and seven biome cases / one known bare/BWh mismatch. Round 30 also compares actual new writer output with native materializer stateAt across 128 fixture combinations in each version. Python JSONL/CSV comparator and its parser tests are available. | Continue replaying the original probes during production migration; these injected fixtures do not establish real-world GIS coverage. A full manifest CLI replay has not been claimed. |
| 6. Save/reload and lifecycle | Caller-owned runtime registry, explicit registration helpers, lifecycle manager, rollout gate, injected atomic storage backend/coordinator contracts and in-memory tests. | Real persistent world backend, real save/reload transactions and default lifecycle hooks remain unimplemented. Current flags/defaults stay opt-in. |

## Evidence and rollback

- Original source recovery and parity rerun: `docs/validation/source-recovery-20261108/README.md`.
- Round 30 implementation, test selection and limitations: `phase30-column-expansion-review.md`.
- Recovery rollback anchor: local branch `recovery/tellus-sources-20261108`, commit `151fded7d751a7c692e0ae371a4cd26bdace2520`.
- Disabling or ceasing explicit pipeline calls retains legacy production behavior. No generator Codec, save encoding or default lifecycle registration changed in Round 30.

Production migration and durable storage are remaining engineering work, not merely an external-manifest acceptance gate.
