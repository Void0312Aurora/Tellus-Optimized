# Round59 production integration validation

## Executed gates

- Strictly serial selected regression cohorts: **mc1201 507/507**, then
  **mc1211 498/498**; zero failures, errors or skipped tests. These cohorts retain
  every Round58 suite and add the new slices/runtime tests. They are not a claim
  that every unrelated repository test or external-data suite was executed.
- Every prior Round58 observation reproduced from the new actual JUnit XML:
  87 observations for mc1201 and 67 for mc1211.
- Generator-owned Cube underground: 12 chunks, 491,520 complete states,
  zero mismatches, 17,392 deltas; six faces include 8 negative and 6 rotated frames.
- Earth native snow: 1,296 cases / 331,776 states, plus 65,536 whole-chunk
  states; zero mismatches, exact cursor/section write and entity-removal traces.
- Existing detached underground 18-chunk/737,280-state parity and all other prior
  native and semantic observations remain reproduced.
- Python comparator contracts: **7/7**.

## Complete explicit manifest replay

The separate mc1201 exporter copies the recovered original fixture inputs and
queries the actual native Cube-Net and Sphere-Face generators. It does not alter
the original three recovered probes. Each column row contains material, height,
status and SHA-256 over every ascending-Y native block-state ID (big-endian int32).
The same-JVM state-ID legend is archived in the metadata. Across13,824 columns,
42,246,144 native states were compared with zero differences.

The seven biome rows copy actual native classifier results. Identifiers retain
original case name and climate input, e.g. `bare/BWh`; the known output remains
Earth `minecraft:desert`, Cube-Net `minecraft:stony_peaks`. The strict comparator
also validates the exact seven identifier set, not just row/mismatch counts.

`comparator-result.json` records the command, exit0, stdout, empty stderr and
SHA-256 of all complete manifests. Both `--require-block-digests` and
`--require-original-biome-cases` were used. These are controlled native fixtures,
not live GIS, datapack/server or persistent-world acceptance.

## Source and recovery evidence

`test-evidence.json` records all actual suite/test-case inventories, XML hashes,
prior stdout checks and86 source/test/tool raw hashes. Of the73 prior raw hashes,
67 are unchanged and six production changes are explicitly listed. Protected
normalized native/default regions are compared to the actual Round58 Git parent
in `static-source-proof.json`: Cube Codec/native underground source trace, native
sampler/field, semantic IDs, original probes, Sphere-Face and default TellusCommon
remain intact.

Changed/new relevant final XML files are archived byte-for-byte. All current XML
hashes and original command argument lists are retained; temporary runner scripts
and nonfinal attempt summaries stay under `%TEMP%/tellus-round59-integration`.
Early compile drafts exposed wrong StructureManager package/record accessor and
orphan helper declarations; all were corrected before the final serial cohorts.
Earlier green cohorts502/506 were superseded by507 after integrity/concurrency
hardening, not substituted for the final sources.

The checkpoint process uses only an isolated `GIT_INDEX_FILE`, scoped staging,
actual `git restore --staged --source <Round58>` and exact parent-tree validation.
The real branch, HEAD and index stay unchanged. An independent read-only source
review identified and verified the strict UTF-8 and concurrent-entry ordinal
fixes; the final artifact/ref/rollback audit is performed after checkpointing.

## Open objective gates

Generic `.txn` persistence is not native world region/chunk save/reload. Source
capture requires caller engine/source quiescence and can read sources before a
busy participant returns QUEUED; it is not source-atomic/busy-source-cold.
Combined biome/terrain/cave/snow/decoration recovery, world-key aliases,
status/light/ticks/heightmaps, real world save/reload/crash recovery, live GIS
acceptance and default lifecycle remain open. The existing nondefault Cube
heightOffset Codec drift remains unchanged and gated.
