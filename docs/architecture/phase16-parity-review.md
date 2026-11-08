# Tellus Phase 16 Parity Comparator Review

> 审阅任务：task-98。
>
> 目标：定位并运行现有 `13,824` Cube/Sphere canonical-column comparator 与 `7` Earth/Cube biome-case comparator；只复用 repository fixtures/tools，不造 samples、不改变 comparator semantics。

## 1. Repository search结果

本轮搜索了：

- repository内 `*parity*`、`*Comparator*`、`*acceptance*` 文件；
- Java/Python/PowerShell source中的 `13,824`、`13824`、`Earth/Cube`、`Cube/Sphere`、`bare/BWh`、`mismatch`；
- Gradle root build script与现有 `tools/`。

找到的相关现有工具是：

- `tools/check_cube_net_acceptance.py`；
- `docs/cube-net-structure-parity.md`；
- `docs/architecture/README.md` 与各阶段 review中的 parity baseline；
- `docs/architecture/phase16-final-status.md`，明确把“fresh full 13,824-column comparator and full biome comparator”列为 deferred follow-up。

没有找到 repository内可执行的 full `13,824` column comparator、full `7` biome-case comparator、对应 fixture manifest或既有 comparator CLI。`check_cube_net_acceptance.py`是 static/contract acceptance tool，不是这两个 full parity comparator。

## 2. Existing tool execution

复用并运行：

```powershell
python tools/check_cube_net_acceptance.py --with-gradle
```

实际结果：

- static checks：`13/13 PASS`；
- `A1`：5个 Cube-Net contract test files present；
- `G1`：Cube-Net contract tests `BUILD SUCCESSFUL in 3m 11s`；
- Gradle summary：`7 actionable tasks: 3 executed, 4 up-to-date`。

该工具覆盖 Sodium/DH opt-in gates、transition payload/state-machine presence与 Cube-Net contract tests；其自身文档明确说明不证明 rendering、movement、multiplayer，也不运行 13,824-column或7-biome comparator。

## 3. Full parity evidence status

### 3.1 Cube/Sphere 13,824 columns

目标 baseline仍记录为：`13,824 columns / 0 mismatch`。

本轮没有可执行 full comparator或可复用 13,824 fixture manifest，因此没有生成新的 13,824-column output。现有 `13/13` acceptance结果不能替代 `13,824 / 0` comparator结果，也不能被报告为 fresh full parity replay。

### 3.2 Earth/Cube 7 biome cases

目标 baseline仍记录为：`7 cases / 1 mismatch`，唯一已知差异为 `bare/BWh`。

本轮没有可执行 full biome comparator或7-case fixture manifest，因此没有重新计算 biome mismatch。既有 `bare/BWh` baseline保持为文档中的 locked evidence，不将其伪装成新鲜运行结果。

## 4. Evidence gap

Full comparator evidence gap明确为：

1. 缺少可执行的 13,824-column comparator入口；
2. 缺少可定位、可复用的 13,824 input fixture/manifest；
3. 缺少可执行的 7-case biome comparator入口；
4. 缺少可定位、可复用的 7-case biome fixture/manifest；
5. 现有工具只提供 static acceptance/contract tests，不产出 column/biome mismatch diff。

本轮没有新建 synthetic fixture，没有扩大测试样本，没有修改 comparator semantics，也没有把 static acceptance结果替换成 parity结果。

## 5. Legacy/rollback/parity结论

- 未运行 full comparator意味着没有新的 parity数字可用于切换任何 migration opt-in；
- legacy generator/source/runtime继续是行为权威；
- 不改变既有 `13,824 / 0 mismatch` 与 `7 / 1 bare/BWh` locked baseline；
- 若后续补齐 comparator，必须使用既有 fixtures/manifest并输出逐列/逐case diff；
- 任何新 mismatch应停止对应 opt-in并回滚到 legacy；
- 当前可复现证据只有 `tools/check_cube_net_acceptance.py --with-gradle` 的 `13/13 PASS` 与 Cube-Net contract test `BUILD SUCCESSFUL`。

## 6. Fail-closed reproducible comparator

Task-100 adds an offline comparator CLI:

- `tools/tellus_parity_compare.py`
- tests: `tools/test_tellus_parity_compare.py`

The CLI consumes only caller-supplied manifests:

```powershell
python tools/tellus_parity_compare.py `
  --columns-left COLUMNS_BASELINE.jsonl `
  --columns-right COLUMNS_CANDIDATE.jsonl `
  --biome EARTH_CUBE_BIOME.jsonl
```

Accepted manifest forms are JSONL or CSV. Column rows require explicit `key`, `material`, `height`, `status`; biome rows require explicit `case`, `earth`, `cube`. The comparator:

- rejects missing, empty, malformed, duplicate-key or incomplete manifests;
- compares canonical column key/material/height/status rows deterministically;
- reports exact missing/value-mismatch rows;
- compares Earth/Cube biome values per explicit case and reports exact mismatch cases;
- enforces default expected counts `13,824` columns and `7` biome cases only after manifests are supplied;
- enforces default mismatch expectations `0` columns and `1` biome case, with known mismatch `bare/BWh`;
- never generates coordinates, samples, fixtures, defaults or synthetic rows.

Exit semantics are fail-closed: missing/invalid evidence returns `EVIDENCE_GAP` with exit code `2`; supplied manifests that fail expected counts/mismatch gates return `MISMATCH` with exit code `1`; only an explicit manifest comparison satisfying the configured gates returns `PASS`/exit code `0`. Small explicit manifests may use overridden expected counts for parser/contract tests, but they are not full parity evidence.

### 6.1 Parser/empty-manifest tests

```powershell
python tools/test_tellus_parity_compare.py -v
```

Actual result:

- `Ran 4 tests`;
- `OK`;
- covered JSONL/CSV parsing, empty-manifest rejection, missing-manifest `EVIDENCE_GAP`, and deterministic explicit small-manifest comparison.

## 7. Full parity evidence status

The comparator CLI is now reproducible and fail-closed, but no original full manifests were found in this checkout. Therefore task-100 does not claim a fresh `13,824 / 0` or `7 / 1 bare/BWh` replay. Running the CLI with absent paths intentionally produces an evidence gap rather than manufacturing a pass.

Locked baseline remains documentation evidence only:

- Cube/Sphere: `13,824 columns / 0 mismatch`;
## 8. Round 18 history/ref audit

Round 18 inspected all local branches and remote refs (`feat/cube-net-parity`, `main`, integration and upstream refs), then searched git history for the locked `13,824` and `bare/BWh` evidence terms and parity/comparator paths. No original full comparator, 13,824-row manifest, seven-case manifest, or matching historical commit was found. The only related repository fixtures remain the existing Cube-Net client/visual fixtures and conformal reference fixtures; neither is a canonical-column or biome parity manifest.

This confirms the remaining gate is an unavailable source-evidence input, not an unsearched local ref. The fail-closed comparator remains ready to consume a supplied manifest and refuses to claim fresh replay without it.

## 9. Round 27 HEI-LAN remote workspace audit

The requested remote search was performed through the configured `HEI-LAN` SSH alias. The candidate workspace was `/home/void0312/Workshop/tellus-optimized` (branch `main`, HEAD `ede5c3d4`), with related data under `/home/void0312/Workshop/earth_compare`.

The remote audit searched:

- parity/comparator/manifest/13824-style filenames;
- bounded docs/tools text for `13,824`, `bare/BWh`, `mismatch`, `parity`, and Earth/Cube terms;
- JSON/CSV/JSONL schemas resembling canonical `key/material/height/status` rows or biome case rows.

It found research mapping baselines, spherical-lattice validation, terrain-stability reports, benchmark outputs, and biome CSV resources, but no original 13,824-row baseline/candidate manifests, no seven-case Earth/Cube manifest, and no matching full comparator output. The mapping CSVs and terrain/benchmark reports are different research/operational schemas and are not eligible parity inputs.

The same remote pass also searched `/home/void0312/Workshop/.worktrees`, including `tellus-upstream-084`; only general spherical/geodetic documentation and orientation research CSVs matched, not parity-row manifests.


## 11. Round 28 full Workshop and benchmark audit

A further HEI-LAN search covered the whole `/home/void0312/Workshop` tree while pruning `.git`, `build` and `node_modules`. Filename candidates were limited to parity/13824/comparator/Earth-Cube terms, then Tellus benchmark JSON summaries were inspected directly.

The additional hits were runtime/visual acceptance artifacts such as `earth_cube_net` datapack descriptors and Cube-Net seam-crossing benchmark JSON. Their schemas contain worldScale, faceSize, crossings, ticks and chunk-load observations, not canonical `key/material/height/status` rows or seven Earth/Cube biome cases. No original full parity manifests or comparator output were found anywhere in the expanded Workshop search.


## 12. Conclusion

Task-100 closes the comparator implementation gap without fabricating parity inputs: `tools/tellus_parity_compare.py` is deterministic, explicit-manifest-only and fail-closed; parser/empty-manifest tests pass. The local checkout and all audited HEI-LAN remote scopes lack the original full 13,824-column and 7-case manifests, so no fresh full replay was claimed at that stage. The archived-source recovery below supersedes the earlier assertion that external manifests were the only available replay path.

## 13. Source recovery and fresh original probe replay — 2026-11-08

The DSH session archive retained complete original sources for `GeneratorConsistencyProbeTest`, `GeneratorParityProbeTest` and `EarthCubeBiomeParityProbeTest` before their deliberate removal. They have now been restored under `mc1201/src/test/java/com/yucareux/tellus/worldgen` and rerun against the recovered working tree.

Fresh JUnit stdout reports:

```text
CONSISTENCY_PROBE faces=6 locations=9 columns=13824 mismatchColumns=0 mismatchBlocks=0
PARITY_PROBE canonicalColumns=256 mismatches=0 maxHeightDifference=0
BIOME_PROBE cases=7 mismatches=1
BIOME_PROBE case=bare earth=minecraft:desert cube=minecraft:stony_peaks equal=false
```

These are new observations from the recovered original injected deterministic fixtures. The six-face comparison checks every block over 3,056 vertical levels per sampled column. No manifest rows or expected outputs were fabricated. The biome probe's original assertion is observational, so a separate recovery verifier checked all seven reported cases, their mismatch count and the exact known mismatch values.

See `docs/validation/source-recovery-20261108/README.md`, `verification.json` and `original-probe-results.json` for test counts, source hashes and limitations. The mc1201 recovery run had 128/129 tests pass; the one failing climate test attempted an external Overture PMTiles URL returning HTTP 404. The mc1211 selected recovery suite passed all 121 tests and excluded that known data-dependent method.

The original replay is now reproducible from source without external manifests. This does not claim a full JSONL/CSV CLI replay or completion of default lifecycle/persistent-storage integration.

