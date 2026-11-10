# Real nonempty lifecycle save/restart — verified

Both actual Fabric servers used disposable copies of the same historical GIS
FULL chunk and cache. No synthetic sampler or replacement region writer was used.

| Check | mc1201 | mc1211 |
|---|---|---|
| Real chest + 3 diamonds | PASS | PASS |
| Block tick / fluid tick | 1 / 1 | 1 / 1 |
| Duplicate pending PostProcessing offsets on restarted disk | 2 | 2 |
| Complete restarted disk and pre-post-processing materialization NBT | equal | equal |
| Explicit native post-processing consumes offsets, every other field equal | PASS | PASS |
| Native queue faults recovered | 3 | 3 |
| All blocks / biome cells per chunk | 1,040,384 / 16,256 | 1,040,384 / 16,256 |
| Source world/cache unchanged | yes | yes |
| Native copied-world DataVersion | 3465 | 3465 → 3955 |

`validation.json` asserts these observations and hashes the raw artifacts.
Each version archives the actual capture/verify reports, expected native SNBT,
server logs and source manifests. The probe invokes same-tick normal shutdown
after its PASS report to prevent scheduled-delay drift between snapshot and
shutdown save. Scheduled ticks are preserved, not claimed to have executed.

The mc1201 initial verify stalled during overworld spawn preparation and exceeded
its timeout before the probe command was dispatched. Its original failed
`roundtrip.json` and compressed startup log are retained. The fresh verify
process used the same captured world without a reset and passed; the final
combined record is `mc1201/roundtrip-resumed.json`. mc1211 passed consecutively.

The only excluded root fields remain `LastUpdate` and `InhabitedTime`.
PostProcessing is not excluded: full NBT matches before explicit native
`postProcessGeneration()`, then an exact expected marker-consumption transition
is compared. Chest inventory and the real water block are independently checked.

This removes the nonempty single-chunk live persistence evidence gap. It is not
acceptance of a process-killed multi-chunk journal, uncontrolled save writers,
new GIS generation coverage or default lifecycle rollout.
