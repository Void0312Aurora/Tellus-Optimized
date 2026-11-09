# Round60 — Native save/restart validation

## Actual results, not a whole-objective completion

| Gate | Result |
|---|---|
| Serial selected mc1201 regression |528/528;0failure/error/skip |
| Serial selected mc1211 regression |519/519;0failure/error/skip |
| New native queue/NBT contracts |21/version (synthetic contracts only) |
| All prior Round59 suites/stdout |Reproduced on both versions |
| Python native-runner contracts |5/5 |
| Python parity comparator contracts |7/7 |
| Full native13,824-column/7-case CLI replay |PASS;0column differences; unchanged bare/BWh mismatch |
| Actual mc1201 copied-world capture→stop→restart→verify |**PASS** |
| Actual mc1211 native upgrade/capture→restart |**FAILED before capture**; open gate |
| Default runtime lifecycle/general crash transaction |Still gated/unimplemented |

## Real existing-world mc1201 acceptance

The target is existing native FULL chunk616246,-351597 in `tellus:earth_cube_net`
from a historical Swiss real-Mapterhorn/land-cover save, not an analytic sampler.
Historical origin is manually corroborated in the referenced provenance docs;
source-origin strings and file hashes are not a machine proof of geographic
origin or new current-build GIS generation. The entire original46-file world and
10-file source cache were copied and fingerprinted before use and checked again
after execution. The source world/cache stayed byte-identical.

The current Tellus0.8.5 build ran actual Fabric1.20.1 twice. First, the probe read
existing native disk NBT before any demand load, verified native FULL coordinates
and world/dimension/seed/generator-profile/bounds identity, then loaded that chunk.
It changed one existing opaque STONE cell at9859938,-2031,-5625550 to GOLD_BLOCK.
All three native publication fault points restored the complete old NBT through
the existing ChunkMap worker, verified exact readback, recovered the separately
mutated loaded cell/dirty flag and compared complete loaded native metadata.
Final native commit was followed by a normal stop, all-dimensions-saved markers,
and observed process exit0. No replacement region worker or custom save format
was used.

The second independent process started the same copied world with the same UUID,
read native disk NBT **before demand load**, and matched the expected whole tag.
It then materialized the real chunk and compared all1,040,384block cells,
16,256biome quart cells, the GOLD sentinel and complete reserialized native NBT.
All passed. Root LastUpdate and InhabitedTime are the only excluded save clocks;
sections/palettes, light, ticks, markers, heightmaps, structures and unknown fields
are not excluded.

`native-metadata-coverage.json` gives the actual nonempty-field boundary:254sections,
2SkyLight arrays and4heightmaps; block entities, block/fluid ticks and postprocess
entries were empty in this tested native chunk. Whole-tag equality does not claim
real nonempty tick/entity/marker lifecycle coverage. Synthetic fixtures cover
other shapes separately. GUI/client/teleport behavior was not part of this
server persistence test.

Complete capture/verify JSON, expected native SNBT, logs, immutable source file
manifests and runner output are archived under `live/mc1201` with hashes in the
main evidence JSON.

## Actual mc1211 upgrade failure

Only a fresh independent copy of the1.20.1 source was admitted for native1.21.1
DataFixer upgrade. Startup failed before readiness:

```text
TaggedChoiceType_DimDataFix: Not recognizing key tellus:cube_net. Using pass-through codec.
ClassCastException: Codec$20 cannot be cast to MapCodec
LevelStorageSource.readLevelDataTagFixed → DataFixTypes → TaggedChoiceType.buildCodec
```

No probe capture or verify ran. The wrapper's Gradle exit0 did not count as a
server PASS: the controller required native readiness/report markers and returned
failure. The full failed log, result and source-world/cache immutability manifests
are archived under `live/mc1211`. No source NBT/DataVersion was patched, no vanilla
generator was substituted and no original save/cache was changed.

This is a concrete **unpassed upgrade gate**, not a reason to mark the overall
goal blocked. Next work is a narrowly scoped mc1211 DFU compatibility layer for
Tellus generator IDs with opaque payload preservation, followed by the same real
upgrade/save/restart test. Fresh-current-version persistence is a separate gate,
not a replacement for this failed upgrade.

## Recovery/checkpoint contract

The actual parent is Round59 `d6eafeacd25b412074380788fa66a68ebac556c3`.
All86 prior source/test/tool hashes remain unchanged;95 current raw hashes include
the new native seam, probe command, tests, controller and opt-in Gradle wiring.
The mc1201 Gradle source matches its normalized parent after two failed pre-start C-drive
attempts exposed Loom's same-drive/project-relative runDir requirement. The runner
now rejects cross-drive runs before source copy; actual worlds were tested in
fresh `D:/Temp` directories outside the checkout.

15 protected parent sources/regions verify unchanged generator Codecs, native
save/apron/high-Y mixins, original probes and material IDs. TellusCommon differs
only by a property-gated command-registration block; no normal lifecycle callback
is replaced or reordered. All old/native generation behavior remains unchanged.
Checkpoint creation uses an isolated Git index, exact scoped staged restore to
parent and exact parent-tree comparison. Real branch/HEAD and staged content stay
unchanged. `index-audit.json` records a qualified raw-index exception: SHA-256 was
634856... at round start and bae681... before checkpoint; its tree is still HEAD,
all1,193entries remain normal stage0/H and cached diff is empty. The exact source
of metadata/cache refresh during inspection is not proven. No original raw backup
exists, so no synthetic alternate index was substituted to fabricate equality.
The checkpoint must preserve the observed bae681... bytes, not claim initial
634856... byte identity.

Observation-count correction: historical Round59 prose said87/67 prior observations,
but its machine evidence actually records47/37 inherited from Round58 and52/40
current normalized observations. Round60 freshly reproduces all52/40. The earlier
prose count was wrong; current evidence and this report use actual counts.


## Remaining objective work

The successful isolated native queue/restart seam is not a crash-durable journal,
not persistent quarantine and not a general loaded/world multi-stage transaction.
mc1211 modded-world upgrade, populated tick/entity/marker lifecycle scenarios,
Earth later-stage production integration, global source/world manifest stability,
world-key aliases/external-writer coordination, the known nondefault Cube sea-level
Codec drift and default lifecycle remain open. Keep the overall goal active.
