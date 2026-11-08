# Tellus Unified Abstraction — Phase 16 Final Status

## Scope

This phase closes the current incremental implementation window without enabling a default runtime takeover. The source tree remains on the legacy/default generator path unless a caller explicitly constructs and registers the new adapters.

## Delivered contracts

- Read-only `SettingsView` / `RuntimeView` adapters for Earth, Cube-Net and Sphere-Face.
- Typed `TellusCoordinateContext`, owner views and spherical noise identity.
- Snapshot-only `TellusSampleProvider` contracts and Earth source adapters.
- Pure `TellusColumnDecisionEngine` and semantic decision records.
- Owner-aware `TellusFeaturePlanner` and canonical/apron mirror policy.
- Version-neutral writer/storage boundaries and the mc1201 canonical pipeline.
- Plan-only `TellusColumnPipeline` / `TellusColumnPlan` with `UNKNOWN` and `QUEUED` early stop.
- Ordered runtime descriptors, caller-owned registries and explicit mc1201/mc1211 registration helpers.
- Opt-in lifecycle bridge with explicit attach/tick/detach and readiness results.

## Final evidence

The final serialized gate completed successfully:

- mc1201 targeted runtime, realtime, decision, pipeline, surface and coordinate tests: `BUILD SUCCESSFUL`.
- mc1211 explicit runtime registration test: `BUILD SUCCESSFUL`.
- mc1201 `compileJava`: `BUILD SUCCESSFUL`.
- mc1211 `compileJava`: `BUILD SUCCESSFUL`.
- Cube-Net static acceptance: `12/12 PASS`.

## Preserved invariants

- Codec and archive ownership remain in the existing generator/settings implementations.
- Canonical chunks remain the only durable owner; apron targets are rejected by canonical writers and receive only derived mirror plans.
- No implicit refresh, network, prefetch, save, reload or transaction commit was introduced.
- The known biome difference remains explicit: `bare/BWh`, Earth `desert`, Cube-Net `stony_peaks`.
- Locked parity evidence remains `13,824 columns / 0 mismatch` for Cube/Sphere and `7 cases / 1 mismatch` for Earth/Cube biome.

## Explicitly deferred follow-up

The following are intentionally outside the current opt-in boundary and must be implemented as separate, measured changes:

1. Default `TellusCommon` / loader / server lifecycle registration.
2. Automatic runtime attach/tick/detach.
3. Real save/reload transaction integration.
4. Full mc1211 version-layer column writer/materializer pipeline.
5. Replacement of legacy generator writers and DH/Voxy/ManagedTerrain takeover.
6. A fresh execution of the full 13,824-column comparator and full biome comparator.

This deferred list is part of the rollback contract: enabling any item requires a separate feature switch, targeted tests, parity evidence and a reversible migration step.
