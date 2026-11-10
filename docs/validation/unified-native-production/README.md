# Unified native production entrypoints: actual acceptance

## Production delta

`-Dtellus.worldgen.pipeline=unified` selects the new generator-owned native pipeline. Unset or `legacy` retains the old bodies with no additional target acquisition, source reads or observations; mode is fixed when each generator is constructed. The default remains **LEGACY** until the remaining world-offset/lifecycle gates pass.

- Earth, both Cube implementations and both Sphere implementations now enter the same `BIOMES → TERRAIN → CARVERS → DECORATION` runner from their actual engine entrypoints. The runner borrows one non-reentrant actual-target-object lease for the synchronous stage body and releases it on normal and exceptional returns. It is not a replacement Minecraft scheduler or a transaction over arbitrary neighbour writes.
- Cube canonical terrain now passes its actual native cached grid, landmark/bank-adjusted columns and optional surface resolver into the existing native-snapshot provider, spherical production kernel, column materializer and shared whole-chunk writer. The writer accepts the already-owned lease instead of reacquiring the same target. Native inland flow and badlands run afterward in their original order. Aprons retain their derived native terrain and canonical decoration ownership/final mirror.
- Sphere terrain uses the same provider/kernel/shared writer over its original exact native grid. Its native carver/decoration stages remain empty, including when unified mode records them.
- Earth retains its native resolved section writer, configured-carver/underground kernels, lazy ordered decoration, shared realtime-snow sink and profiling boundaries. No duplicate Earth sampler or decoration algorithm was introduced.
- An admitted failed stage is not retried through legacy. Ownership collisions stop before source acquisition. Batch failure diagnostics use only the error's class: overridable Throwable callbacks stay outside cleanup/reporting paths.

## Controlled native entrypoint gate, both versions

New tests invoke the real public generator methods, not only standalone factories.

| Matrix per version | Complete cells compared | Other observations |
|---|---:|---|
| 24 Cube chunks, all six faces, standard/modified settings, simple/rich surfaces | 2,359,296 | All biomes, heightmaps, ordered post-processing arrays, dirty state, exact native source-query trace/count; actual BIOMES/TERRAIN/CARVERS |
| 18 Sphere chunks, six faces × three surface scenarios | 1,769,472 | Same metadata/source checks; all four stages, empty carvers/decoration |
| Actual Earth controlled fill through public biome/terrain entrypoints | 135,168 | Every final block equal, both stage observations and released target owner |
| Real source error and competing target writer | no mutation after preflight failure | Collision before source reads; fatal-source failure releases lease and records failure |

These are controlled source fixtures, not an independent global-GIS correctness oracle. The initial new fixture omitted Minecraft's native BIOMES status transition; adding the same real status transition to both lanes fixed its preflight without weakening production validation.

## Real native generation/save A/B, both versions

Each lane starts a separate native Minecraft process against a fresh copy of the same historical real-GIS world and cache. The target `(616262, -351597)` is absent before demand generation. The old production checkout is `80dd37ba39677adb5a4229a781ca3b4e5adabc8f`; its only working-copy change is the same observation-only source-readiness probe as the candidate. Production algorithms in that checkout are untouched. Both source-code manifests, the original world and the original cache are asserted unchanged throughout each pair.

Before target demand generation, both lanes call the same existing blocking native detail query, then require that the ordinary NON_BLOCKING query is complete. Both observe the exact same sorted **115 building IDs, 79 road IDs, eight water IDs and zero road areas**. This admission lives solely in the explicit persistence probe; ordinary production retains its existing NON_BLOCKING no-data behavior.

The candidate must report `UNIFIED`, all four stages in order, no failures and the shared chunk writer backend. Then the target reaches FULL and is saved through the native queue. Every generated block/biome digest, native status/DataVersion, lifecycle count and complete native NBT digest equals the old lane. Only the existing `LastUpdate`/`InhabitedTime` save clocks are excluded. Each saved tag also matches its materialized FULL payload, and all processes exit normally.

| Version | Equal block SHA-256 | Equal complete native NBT SHA-256 |
|---|---|---|
| mc1201 | `0d20c7149574253fb93d17bd62927ddfe2246cbb7fcbf749d6688410730696eb` | `34343012d0c50c24cf819d1a2537e7f4cedf54beac6df6110607d080dd1548b2` |
| mc1211 | `d9db01c32d21828990b966fa5e5ebbe43496586a032b10175fff58b954c465cb` | `a3d663c1ac22821593d2f31be10748de229695bc886ec292389e77c25a95a578` |

Each version compares 1,040,384 real generated block cells and 16,256 biome cells. Raw reports/SNBT, compressed complete server logs, immutable-input manifests and source hashes are archived alongside `validation.json`. Observed generation times (not a controlled benchmark) are 13.97s old / 35.02s unified on mc1201, and 15.07s old / 32.97s unified on mc1211; no performance-equivalence claim is made.

### Retained initial cold-input failure

The first mc1201 cold-cache A/B passed. The first mc1211 comparison differed by 448 actual building-material blocks; independent Anvil reads located the changes in sections 104–105, with corresponding heightmap/light changes. That cache initially lacked building/road tile data, and native NON_BLOCKING detail readiness was not admitted equally. The candidate already had the same final digests as the subsequently accepted settled run, while the old lane omitted the building. The first run's FAILED report, both raw payloads and full logs remain under `retained-initial-cold-input-runs/`. A fresh pair with the same real native-populated cache and the explicit readiness/ID checks above passed without changing generation algorithms or adding digest exclusions. This does not promise equal cold-network wall-clock readiness or change the historical no-data semantics.

## Regression and rollback

The first complete mc1201 regression reported one skipped test after an experimental diagnostic called an overridable `Throwable.getMessage`. That callback can itself throw. The diagnostic was restored to the accepted parent's class-only form, preserving the original failure/cleanup contract. This is the sole code change after the successful native A/B: `validation.json` reconstructs the exact native-run file hash from that one-line difference, and the final full regression must have zero skips. No successful-stage operation changed; the initial regression record is retained.

Final serial selected regressions retain every previously accepted suite and add the four new tests: **mc1201 553/553, mc1211 544/544**, zero failures/errors/skips. Original strict parity still compares **13,824 columns / 42,246,144 states / zero column differences**, all seven original biome cases and only the existing `bare/BWh` difference. The Python persistence-controller contracts also remain 8/8.

Recovery checkpoint parent: `c0d5776d0b33796085fb3d1d9b7c6f0bdc1878c4`. The isolated-index checkpoint verifies exact restoration to that parent tree, leaving the user's actual branch, HEAD and index unchanged. Runtime rollback is `-Dtellus.worldgen.pipeline=legacy` before a clean restart; unset remains legacy at this checkpoint. This does not roll back already-saved terrain or change existing Codec data semantics.

Remaining objective: non-default `heightOffset` real save/restart, then reversible runtime/lifecycle default registration and its real acceptance. The accepted Round68 nonempty lifecycle and actual multi-chunk crash-recovery gates are retained, not re-expanded. Full cross-neighbour decoration atomicity, arbitrary external writers and global-GIS coverage are outside this accepted native-stage route.
