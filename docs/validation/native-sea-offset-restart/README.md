# Non-default Cube sea-offset: real native save/restart acceptance

## Accepted result

Both **mc1201 and mc1211** pass three independent native server launches against the same isolated copied world, with two stored-generator reloads. Every launch observes a genuine `CubeNetBiomeSource` carrying `height_offset=76`, runtime sea level **88**, and a native saved `sea_level=76`. The original world/cache and all Java source remain unchanged during each run.

This intentionally tests the legacy stored value: the unchanged decoder interprets `76 + (76 - 64) = 88`. The corrected encoder preserves the input `76` rather than storing the already-offset `88`; a later load therefore stays at `88`, rather than becoming `100`. It does not reinterpret an old stored `76` as runtime `76`. The existing constructor/new-world, negative/zero/positive delta and repeated Codec tests remain in the retained regression cohort.

| Observation across launches 0 / 1 / 2 | mc1201 | mc1211 |
|---|---|---|
| Actual settings height offset | 76 / 76 / 76 | 76 / 76 / 76 |
| Actual native runtime sea level | 88 / 88 / 88 | 88 / 88 / 88 |
| Native stored `sea_level` | 76 / 76 / 76 | 76 / 76 / 76 |
| Complete serialized generator/profile | identical | identical |
| Complete native chunk NBT after real materialization/save | identical | identical |
| Independent complete Anvil decode | identical | identical |
| Normal exit for all three owned launches | yes | yes |
| Retained serial JUnit cohort | 553/553, zero failures/errors/skips | 544/544, zero failures/errors/skips |

Native full-NBT SHA-256 remains `3a4cca882017da956dc95a145a3c1fd527208260508631dcde8821a20fc75efc` on mc1201 and `8872abd9901a97747b8ed4e9b099cd8098b409ce3c13acd10d7b7bf3cdf73fb6` on mc1211 across their three launches. Both native and independent comparisons exclude only `LastUpdate`/`InhabitedTime`; no block, biome, lighting, heightmap, structure, entity, tick or marker field is excluded. Each launch also verifies its materialized complete payload through the actual Minecraft save queue. The accepted Round68 nonempty lifecycle gate is retained rather than populated again here.

## Make the saved Codec the actual authority

The historical fixture has two representations: an old fixed-biome generator in `level.dat`, and an active custom-dimension datapack definition. The first preparation attempt modified only the saved tag; Minecraft selected the datapack definition and correctly failed the probe's strict settings-carrying-source check (runtime remained sea 64). Its failure report and full log are retained.

The passing run therefore uses the actual authority, without a new generator encoding:

1. Copy the historical real world and native-populated GIS cache to a fresh directory and verify immutable-input manifests.
2. In the copied datapack only, replace its existing `biome_source` field with the already-supported Cube source carrying offset 76 and set its existing `sea_level` field to legacy input 76. Preserve the active geometry, bounds, scale, apron cap 2 and singularity settings. The copied saved level is not altered before native bootstrap.
3. Start native Minecraft, require the real settings source/runtime values, load the already-existing FULL chunk, snapshot it, and force the native save queue. Independently decode the resulting native `level.dat` and require stored sea/offset 76.
4. After that process stops, move only the owned copy's dimension definition out of the datapack into evidence, retaining its dimension-type resource. Subsequent processes therefore load the **saved LevelStem**, rather than silently resetting settings from a datapack. The moved file's exact SHA is verified and its absence is required.
5. Start two more independent native servers; require the same runtime values, native encoded fields, complete serialized generator, native chunk digests and independent Anvil digests.

No save-schema or decoder change was introduced. Final native binary `sea_level` and nested `height_offset` are both the existing NBT `TAG_Int` (3). Raw final native level/backup files, the retired bootstrap definition, all three raw reports/SNBT/logs, source manifests and original JUnit XML are archived with `validation.json`.

## Validation and remaining gate

All 13 Python preparation/controller contracts pass. Strict original parity remains **13,824 columns / 42,246,144 states**, zero column differences, all seven original biome cases and only the existing `bare/BWh` mismatch. The full retained dual-version cohorts remain 553/553 and 544/544. This completes the actual non-default saved-Codec gate; it does not add an alternate region writer, repopulate lifecycle payloads or rewrite old terrain.

Checkpoint parent is `c32c36b37f8e23955a410de1a6126874eccdb129`. The isolated-index checkpoint verifies exact restoration to that parent tree, preserving the real branch/HEAD/index. The remaining objective is reversible runtime/lifecycle default integration with its native acceptance. Legacy stays default until that gate passes.
