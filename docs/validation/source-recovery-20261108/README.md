# Tellus source recovery — 2026-11-08

The unified-abstraction source, tests and architecture documentation have been restored into the working tree. Recovery used recorded file writes/edits and complete read snapshots from six local DSH sessions, with retained Java bytecode as an independent fallback. Git HEAD remains `1c35ccbb2441fd146ceb28ee35700f684dec8ed3` on `feat/cube-net-parity` during validation.

## Preservation and recovery coverage

Before compiling, 4,363 files (107,813,572 bytes) were copied to `C:\Users\30483\AppData\Local\Temp\tellus-rescue-20261108-134156`. The copy contains mc1201/mc1211 main/test classes, test results/resources, source files present at rescue time, reflog evidence and a class SHA-256 inventory.

The session extraction recovered 223 complete recorded texts: 220 nonempty files and three final empty states of deliberately removed temporary probes. Of these 223 records, 200 came from exact complete writes and literal-edit chains, 19 from complete observed reads with original newline bytes unestablished, and four from historical complete versions with gaps in the latest edit chain. Two additional large tracked files had partial-only evidence. Thirty-four architecture documents and both parity Python scripts were recovered.

Latest-state gaps were reconciled as follows:

- `EarthChunkGenerator`: replayed four exact recorded substitutions onto the Git HEAD baseline.
- `TellusRealtimeManager`: replayed recorded imports and exact final read fragments onto HEAD; checked all final 1,049-line read ranges against the reconstructed text.
- `TellusGeneratorRuntimeRegistry`: restored `unregister` and reverse-order `unregisterAll` from preserved compiled behavior.
- `TellusGeneratorRuntimeLifecycleManagerTest`: restored the missing private registry helper signature, consistent with its body and compiled test behavior.
- `CubeNetSurfacePolicy`: restored the recorded shared surface-roll delegation and final `SphericalNoisePoint` delegation, rather than the malformed intermediate edit.
- mc1211 `CanonicalPipeline`: combined its original complete text with the original mc1201 factory bodies mirrored to mc1211, corroborated by the preserved latest mc1211 class and adapter tests.

Thirty-seven incorrectly duplicated common classes from the initial decompilation pass and two obsolete duplicate tests were moved into the external recovery backup. The retained test variants were checked against the saved compiled method inventories. These files were preserved rather than discarded.

Full per-file hashes, session/tool-call provenance, extraction scripts, quarantined copies and raw test XML remain in the external rescue directory. Original comments/formatting are retained where recorded; byte-for-byte recovery is not claimed for reconstructed fragments or read-only snapshots.

## Git evidence

The Git audit inspected 39 unreachable trees, both unreachable commits, all five unreachable blobs and 75 non-HEAD source/documentation/tool blobs. Those objects preserve older Cube-Net work, not the deleted unified abstractions. No matching VS Code History entry was found among 1,820 entry indexes. The reflog records a reset to HEAD at 13:15:09 +08:00; that entry alone does not establish the command that removed untracked files.

## Validation

| Check | Observed result |
|---|---|
| mc1201 main source compilation | BUILD SUCCESSFUL |
| mc1201 recovered contracts/adapters and three original probes | 129 tests, 128 passed, one external-data failure |
| mc1211 main/test source compilation and selected recovered tests | BUILD SUCCESSFUL; 121 tests passed |
| Python parity comparator tests | Four tests passed |
| Tracked patch whitespace | `git diff --check` passed |

The mc1201 failure is `TellusWorldgenEarthSampleClimateTest.attachesExistingDitheredKoppenWithoutChangingAggregateOrLocalOnlyProvenance`: its production water resolver attempted an Overture PMTiles URL which returned HTTP 404, then raised `OceanCoverageUnavailableException`. The original test and production logic were retained. The mc1211 selection includes the unsupported-request climate test but excludes that known data-dependent method. This is a recorded outstanding test dependency, not an all-green suite.

## Recovered original parity probes

Historical tool records also retained the full sources of `GeneratorParityProbeTest`, `GeneratorConsistencyProbeTest` and `EarthCubeBiomeParityProbeTest` before their deliberate removal. Those original sources were restored in `mc1201/src/test/java/com/yucareux/tellus/worldgen` and freshly rerun.

Results captured from the new JUnit XML:

```text
CONSISTENCY_PROBE faces=6 locations=9 columns=13824 mismatchColumns=0 mismatchBlocks=0
PARITY_PROBE canonicalColumns=256 mismatches=0 maxHeightDifference=0
BIOME_PROBE cases=7 mismatches=1
BIOME_PROBE case=bare earth=minecraft:desert cube=minecraft:stony_peaks equal=false
```

The original consistency fixture injects deterministic elevation/cover/water inputs and compares every block over 3,056 vertical levels in each selected column. It is the recovered original comparison, not a newly invented manifest or a real-world GIS dataset audit. The biome probe's original assertion is observational; the recovery verifier separately validates all seven output cases, the mismatch count and the exact `bare/BWh` values. Machine-readable observations and source hashes are in `verification.json` and `original-probe-results.json` alongside this report.

This corrects the earlier claim that externally supplied manifests were the only way to rerun the original comparison. The archived source provides a reproducible replay path. The separate JSONL/CSV manifest CLI remains available and its parser tests pass; no full manifest comparison through that CLI is claimed here.

## Remaining project work

Recovery does not finish all six deferred implementation objectives. Production rule/callsite migration, a real persistent save/reload backend, full writer/materializer behavior and default lifecycle rollout still require implementation and their own verification. The existing opt-in contracts and successful original probes do not establish those integrations. Default rollout/lifecycle/storage behavior was retained during recovery.
