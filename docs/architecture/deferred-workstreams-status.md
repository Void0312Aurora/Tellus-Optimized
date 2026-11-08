# Tellus Deferred Workstream Implementation Status

## Goal integration

The six deferred workstreams are now represented in the same active Tellus goal and implemented in ordered, opt-in slices. Default lifecycle and storage hooks remain disabled until fresh parity evidence is available.

## Workstream results

1. **mc1211 column pipeline** — completed. Added version-layer semantic mapper, canonical column adapter, column writer and canonical pipeline; preserved disabled/canonical/apron/UNKNOWN/QUEUED guards. Targeted mc1211 tests and compile pass.
2. **Cube-Net/Sphere-Face samples** — completed as an injected provider boundary. Added explicit owner routing plus concrete `SphereChunkSampler` mapping for elevation/cover/water; non-blocking requests return QUEUED without sampler/network work.
3. **DecisionKernel production rules** — completed as behavior-preserving semantic adapters for Cube/Sphere surface decisions and resolved Earth biome IDs. Full Earth classifier relocation remains separate because it requires visual cover, original Köppen text, coordinates, scale, climate provenance and the existing fallback order; Earth surface also needs underwater-top and palette flags before direct replacement.
4. **FeaturePlan placement sink** — completed as opt-in root and mc1201/mc1211 sinks with owner, apron, conflict, status, UNKNOWN/QUEUED/EMPTY and semantic-material guards. Existing column writers remain unchanged.
5. **Parity comparator** — completed as a fail-closed JSONL/CSV comparator harness with exact mismatch reporting, 13,824/0 and 7/1 thresholds, and parser tests. The repository still lacks the original full manifests, so no fresh 13,824-column or seven-case replay is claimed.
6. **Save/reload and lifecycle** — completed as explicit contract-first coordinators: injected atomic storage backend with commit/reload/idempotency statuses, plus a feature-gated caller-owned lifecycle manager with exact registration, attach/tick/unregister/stop cleanup. Default TellusCommon/server hooks remain disabled.

## Final gate evidence

- mc1201 deferred-workstream targeted tests: `BUILD SUCCESSFUL`.
- mc1211 deferred-workstream targeted tests: `BUILD SUCCESSFUL`.
- mc1201 compile: `BUILD SUCCESSFUL`.
- mc1211 compile: `BUILD SUCCESSFUL`.
- parity harness tests: `Ran 4 tests — OK`.
- Cube-Net static acceptance: `12/12 PASS`.

## Remaining acceptance gates

- Supply or recover the original full parity manifests and run the comparator.
- If Earth classifier relocation is desired, add climate provenance and the complete Earth semantic palette before changing production callsites.
- Only after those gates pass should a separate change enable default TellusCommon/server lifecycle wiring and real world storage backend integration.
