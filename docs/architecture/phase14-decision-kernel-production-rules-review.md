# Tellus Phase 14 DecisionKernel Production Rules Mapping 审阅

> 审阅对象：task-94 的 mc1201/mc1211 semantic mapper enum expansion与 targeted tests。
>
> 审阅任务：task-95。
>
> 结论：**Phase 14 first slice completes target-version mapping coverage for the expanded `SemanticMaterial` vocabulary in mc1201 and mc1211. Both mappers use explicit enum switches, map the same semantic values to the corresponding target `Blocks.*` state, and reject UNKNOWN rather than synthesizing a fallback. This is a target mapping/equivalence seam, not a full DecisionKernel production-rule migration: Earth rules, biome source integration, generator call sites, feature policies and parity output remain unchanged.**

## 1. 实现范围

### 1.1 Target semantic mappers

- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1201SemanticMapper.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1211SemanticMapper.java`
- existing target adapters/writers consume these mappers only after shared status/owner guards pass。

Both target mappers now explicitly cover the expanded semantic enum:

- AIR → `Blocks.AIR`；
- BEDROCK → `Blocks.BEDROCK`；
- STONE/DEEPSLATE → matching blocks；
- DIRT/COARSE_DIRT/ROOTED_DIRT/PODZOL；
- GRASS/MOSS/MYCELIUM；
- SAND/SANDSTONE/GRAVEL；
- ANDESITE/DIORITE/COBBLESTONE；
- CLAY/MUD/PACKED_MUD；
- SNOW/POWDER_SNOW/ICE/PACKED_ICE/BLUE_ICE；
- WATER；
- TERRACOTTA/RED_SAND；
- UNKNOWN → explicit `IllegalArgumentException`。

The mc1201 and mc1211 switches are intentionally parallel. Version-specific Minecraft API remains inside each target module; shared `SemanticMaterial` stays registry-free.

### 1.2 Mapping boundary

`map(material, y, context)` is a pure material lookup:

- `y` and context are not used to invent alternate blocks;
- no chunk/world/source/cache access；
- no biome registry lookup；
- no writer/lifecycle call；
- no UNKNOWN/partial fallback to STONE；
- `defaultBlockState()` is the only target-state construction。

Actual surface/strata/water policy remains upstream in `ColumnDecision`/`SurfaceDecision`/`StrataDecision`; mapper only translates an already chosen semantic material。

## 2. Cube/Sphere policy-to-semantic relationship

### 2.1 Existing semantic policy outputs

Cube-Net/Sphere surface policy types already express version-neutral material decisions through `CubeNetSurfacePolicy.Material` and related mountain/shore/snow rules. The expanded `SemanticMaterial` set now has target mappings for the corresponding production categories, including coarse/rooted dirt, podzol, moss/mycelium, rock variants, mud/packed mud and ice variants.

This allows a future production-rule adapter to translate policy output into `SemanticMaterial` without adding Minecraft API to the DecisionKernel. The current mapper itself does not call `CubeNetSurfacePolicy`, `MountainSurfaceRules`, `SurfaceMaterialDepthPolicy`, `DeepslateSlopePolicy` or `CubeNetBiomeSource`.

### 2.2 Equivalence evidence boundary

The target mapping evidence proves enum-to-`BlockState` coverage and parallel mc1201/mc1211 behavior. It does not prove full policy equivalence for every Cube/Sphere path:

- no complete `CubeNetSurfacePolicy.Decision` → `ColumnDecision.SurfaceDecision` adapter yet；
- no full strata/water/biome climate rule migration；
- no feature/structure/road/building/tree placement migration；
- no generator call-site replacement；
- no full column output comparator produced by this slice。

Future policy adapters must use direct semantic fixtures and the existing legacy comparator before they can be called production parity。

## 3. Earth and biome remaining scope

### 3.1 Earth rules remain legacy

Earth `EarthChunkGenerator` surface/terrain decisions remain in the existing generator implementation. The mappers do not route Earth calls into DecisionKernel, and no Earth generator call sites were changed by task-94.

Remaining Earth work includes:

- Earth elevation/cover/climate sample-to-decision production adapter；
- Earth surface palette/strata/water policy conversion；
- Earth feature/structure/hydrology planning；
- explicit Earth generator opt-in with legacy fallback；
- full Earth column comparator before replacement。

### 3.2 Biome source remains legacy

`CubeNetBiomeSource` and Earth/Cube biome source paths remain behavior authority. This slice does not alter biome registry selection, climate mapping, `CubeNetBiomeSource.noiseCoordinates`, or known mismatch handling.

Known baseline remains:

- Earth/Cube biome: `7 cases`, `1 mismatch`, known `bare/BWh`。

The enum mapping test must not be interpreted as a biome fix; `bare/BWh` remains open work and is not hidden by target material mapping。

## 4. Targeted test evidence

Lead reported task-94 mc1201/mc1211 semantic mapper targeted tests and compile checks `BUILD SUCCESSFUL`。Evidence covers:

- explicit known-material mapping to target block states；
- UNKNOWN material rejection；
- target module/API isolation；
- existing canonical adapter/pipeline status guards remain before target mutation；
- mc1201/mc1211 mapping tables remain parallel。

Existing adapter tests also exercise UNKNOWN/QUEUED/apron no-write and canonical top-cell behavior; these guards remain upstream of mapper invocation.

This evidence validates mapping and compile boundaries only. It does not establish Earth biome parity, full Cube/Sphere policy equivalence, save/reload, lifecycle integration or generator output replacement. This review does not start additional tests。

## 5. No generator callsite change

Task-94 did not change call sites in:

- `EarthChunkGenerator`；
- `CubeNetChunkGenerator`；
- `SphereFaceChunkGenerator`；
- `CubeNetBiomeSource`；
- `TellusMinecraft1201CanonicalPipeline` or mc1211 equivalent；
- runtime registry/factory/registration helper；
- lifecycle bridge/Common/server hooks。

The expanded mappers are only injectable target services. A mapper class existing or compiling does not enable DecisionKernel, writer, generator, lifecycle or default worldgen replacement。

## 6. Legacy、rollback与default boundary

### 6.1 Legacy unchanged

- legacy Earth/Cube/Sphere production rules remain default；
- semantic mapper does not auto-register or auto-discover；
- no new global singleton/provider/cache；
- no save/payload/runtime state mutation；
- callers not using the target mapper retain prior behavior。

### 6.2 Rollback points

1. **Mapper opt-out**：停止注入 expanded mapper，恢复 previous target mapping implementation。
2. **Material rollback**：若单个 enum mapping出现 mismatch，回滚该 enum entry，不改变 shared decision policy。
3. **Target rollback**：禁用 mc1201/mc1211 adapter consumer，保留 legacy writer/generator。
4. **Policy rollback**：若 future Cube/Sphere policy-to-semantic adapter出现错误，停用 policy adapter，不改 existing surface rules。
5. **Earth/biome isolation**：Earth/biome remaining paths保持 legacy，避免 mapping slice扩大影响面。
6. **Parity rollback**：任何 canonical/biome comparator变化立即停止 production-rule opt-in，输出 semantic/BlockState diff并恢复 legacy。

## 7. Parity 门槛保持

本次 production-rules mapping review不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

Target enum mapping与 compile/targeted tests没有 generator callsite、storage output或parity replay接入；`bare/BWh`仍为已知 mismatch。任何未来 production-rule adapter必须使用同一 comparator，新增 mismatch立即回滚。

## 8. 审阅结论

Task-94 mapping slice边界正确：mc1201/mc1211对扩展 `SemanticMaterial`执行平行、显式、UNKNOWN-fail-fast的 enum mapping，target APIs保持在版本模块；现有 status/owner/no-write guards仍先于 mapper。Earth rules、biome source、generator call sites与默认 legacy行为未改变。

Task-95 review完成；后续应先建立 Cube/Sphere policy-to-semantic direct fixtures，再处理 Earth/biome remaining scope与显式 generator opt-in；继续保持 `13,824 / 0`、`7 / 1 bare/BWh` parity门槛。

## 9. Round17 Earth surface palette contract

Round17 adds a Minecraft-free `EarthSurfacePalette` contract and a pure
`EarthSurfacePaletteAdapter`. The contract carries the fields that the legacy
Earth private `SurfacePalette` cannot currently be represented by the older
three-field `SurfaceDecision`:

- `top`, `underwaterTop`, and `filler` as `SemanticMaterial`;
- positive `depth`;
- version-neutral `SurfaceForm`;
- explicit `strata` flag;
- `Flags.water`, `Flags.shore`, `Flags.badlands`, `Flags.builtUp`, and
  `Flags.randomBiomes`.

`UNKNOWN` is rejected for every material field rather than being converted to
stone or another fallback. `EarthSurfacePaletteAdapter.toSurfaceDecision`
projects only the common top/filler/depth/form view. The reverse adapter accepts
an already semantic `SurfaceDecision` plus explicit underwater/strata/flag
inputs; it does not query climate, classifiers, registries, generators or
sources.

The existing three-argument `SurfaceDecision(top, filler, depth)` constructor
remains valid and uses `SurfaceForm.NONE`; existing `defaultStone()` and
`unknown()` behavior is unchanged. Therefore this contract is preparatory and
does not claim that the private Earth palette has been migrated.

Round17 deliberately does not:

- add a climate field to `TellusSample`;
- reorder or normalize Earth biome classification, including `cover=60` and
  `BWh` behavior;
- change `EarthChunkGenerator` or `EarthBiomeSource` callsites;
- map private `BlockState` palettes directly into the shared contract;
- enable generator, writer, registry or lifecycle integration.

## 10. Optional Climate Sample contract

Task-106 adds `src/main/java/com/yucareux/tellus/worldgen/sample/TellusClimateSample.java` and an optional trailing climate field on `TellusSample`.

The contract preserves raw Köppen codes without classifier logic:

- `TellusClimateSample.available("BWh")`, `BWk`, `BSh`, `Csa` retain exact trimmed codes;
- AVAILABLE climate requires a nonblank code;
- PARTIAL may carry a code or remain null;
- UNKNOWN and QUEUED use null code with explicit status;
- `TellusSample` keeps the existing six-argument constructor, which produces `Optional.empty()` climate;
- the seven-argument constructor carries `Optional<TellusClimateSample>` without changing core elevation/cover/water aggregate status;
- climate is optional metadata, not a new aggregate status or classifier result.

### 10.1 Backward compatibility and raw-code boundary

Existing callers using the six-argument `TellusSample` constructor remain source-compatible and receive absent climate. Existing sample status/provenance/layer consistency validation remains unchanged. The climate contract does not uppercase, classify, map, or reinterpret Köppen codes; trimming only removes surrounding whitespace, while blank input becomes absent.

The contract deliberately does not:

- call Earth/Cube biome classifiers;
- alter `EarthBiomeSource`, `CubeNetBiomeSource`, or any generator callsite;
- change `bare/BWh` behavior or convert `BWh` into a different biome;
- make climate required for core terrain samples;
- add climate to DecisionKernel status aggregation or FeaturePlanner eligibility;
- introduce network/cache/provider scheduling.

### 10.2 Test evidence

Lead reported task-106 climate contract targeted test `BUILD SUCCESSFUL`. `TellusClimateSampleContractTest` covers exact canonical code preservation, six-argument backward compatibility, optional climate carriage in seven-argument samples, AVAILABLE code validation, and explicit UNKNOWN/QUEUED sentinels. This is contract evidence only; no classifier or Earth/Cube biome output was changed or rerun.

The existing Earth/biome remaining scope therefore stays open: an explicit climate source adapter, classifier equivalence fixtures, Earth/Cube biome callsite migration and full comparator replay are still required before production use.

## 11. Phase 14 parity/default conclusion

Task-94/106 changes remain preparatory and callsite-free. mc1201/mc1211 semantic mappings and optional raw climate metadata do not register generators, alter classifiers, or change legacy/default worldgen. The locked baselines remain:

- Cube/Sphere canonical columns: `13,824`, `0 mismatch`;
- Earth/Cube biome: `7 cases`, `1 mismatch`, known `bare/BWh`.

## 12. Round19/20 Earth climate attachment

Task-108 attaches an optional climate sample in `TellusWorldgenEarthSampleBackend` without changing the core aggregate layers or classifier callsites:

- for a supported Earth request, it calls the existing `TellusWorldgenSources.koppen().sampleDitheredCode(blockX, blockZ, scale)`;
- the raw dithered Köppen code is wrapped as `TellusClimateSample.available(code)` when present;
- aggregate UNKNOWN yields no climate attachment;
- existing sample status/elevation/cover/water/provenance remain unchanged;
- the code is provenance data, not a new classifier or biome decision.

### 12.1 Local-only/no scheduling boundary

The Earth backend remains a memory-only/local-only bridge:

- no network request or download scheduling;
- no cache refresh or executor submission;
- no source registry mutation;
- no implicit retry or fallback classifier;
- unsupported non-Earth owner remains UNKNOWN with no climate field;
- local-only provenance remains explicit through existing snapshot/unknown provenance semantics.

The dithered code is read from the existing Koppen source and is not recomputed by a new classifier. This preserves the legacy source algorithm while making the raw value available to a later DecisionKernel consumer.

### 12.2 Test evidence and unchanged classifier scope

Lead reported task-108 climate-backend targeted test `BUILD SUCCESSFUL`. `TellusWorldgenEarthSampleClimateTest` covers:

- exact attachment of the existing dithered code;
- aggregate status preservation through the legacy six-argument projection;
- snapshot/unknown provenance behavior;
- unsupported spherical request remaining UNKNOWN with no climate attachment.

No `EarthBiomeSource`, `CubeNetBiomeSource`, `EarthChunkGenerator` or biome classifier callsite changed. The test compares against `TellusWorldgenSources.koppen().sampleDitheredCode(...)` as the existing source oracle; it does not claim classifier output parity or repair `bare/BWh`.

## 13. Earth/biome parity remains

Task-108 only attaches raw climate metadata. It does not feed climate into DecisionKernel, FeaturePlanner, Writer, biome registry selection or generator output. Existing remaining scope stays open: classifier equivalence fixtures, Earth/Cube biome consumer migration, full comparator manifests and explicit generator opt-in.

Locked baselines remain:

- Cube/Sphere canonical columns: `13,824`, `0 mismatch`;
- Earth/Cube biome: `7 cases`, `1 mismatch`, known `bare/BWh`.

## 14. Earth Biome DecisionKernel bridge

Task-110 adds `src/main/java/com/yucareux/tellus/worldgen/decision/TellusEarthBiomeDecisionKernel.java`, a pure bridge over an injected resolver. It does not implement or replace the Earth classifier; it forwards already-resolved inputs and adapts the returned ID through `TellusBiomeDecisionAdapter`.

### 14.1 Exact forwarding contract

For a complete `EARTH_PROJECTED` sample with nonblank raw Köppen code, the resolver receives, in order:

1. `sample.cover().visualClass()`;
2. raw `koppenCode` unchanged;
3. `EarthCompatibilityPoint.floorBlockX()`;
4. `EarthCompatibilityPoint.floorBlockZ()`;
5. `EarthCompatibilityPoint.worldScale()`.

The bridge does not forward raw cover in place of visual cover, does not round coordinates again, and does not normalize/reclassify Köppen codes. The test exercises raw code order `BWh`, `BWk`, `BSh`, `Csa`; `BWh` remains the first/known Earth desert case and is not rewritten.

### 14.2 UNKNOWN boundaries

The bridge returns `BiomeDecision.unknown()` without resolver invocation when:

- coordinate does not match sample request (throws explicit mismatch error);
- owner is not `EarthOwnerView`;
- sample is UNKNOWN, QUEUED or otherwise incomplete;
- cover is incomplete;
- raw climate code is null or blank;
- injected resolver returns null/unknown ID.

This prevents incomplete climate/cover data from becoming a classifier call or a default biome. The bridge does not schedule source work, access registries, call generators or mutate samples.

### 14.3 Test evidence and production boundary

Lead reported task-110 Earth biome kernel targeted test `BUILD SUCCESSFUL`. `TellusEarthBiomeDecisionKernelTest` covers exact visual-cover/raw-Köppen/floor-coordinate/world-scale forwarding, the `BWh`/`BWk`/`BSh`/`Csa` sequence, missing climate/incomplete sample no-call behavior, unknown resolver IDs and non-Earth owner UNKNOWN behavior.

No EarthBiomeSource, CubeNetBiomeSource, EarthChunkGenerator or generator callsite was migrated. The injected resolver is a test/consumer boundary; `TellusEarthBiomeDecisionKernel` does not install a production classifier or alter the legacy biome source.

## 15. Remaining Earth/biome scope and parity

Task-110 remains preparatory: a production Earth classifier adapter still needs explicit resolver ownership, legacy equivalence fixtures and full comparator replay. The known Earth/Cube biome difference remains `bare/BWh` (Earth desert versus Cube-Net stony peaks); this bridge neither fixes nor hides that mismatch.

Locked baselines remain:

- Cube/Sphere canonical columns: `13,824`, `0 mismatch`;
- Earth/Cube biome: `7 cases`, `1 mismatch`, known `bare/BWh`.

## 16. Earth classifier adapter provider/exact/fallback

Task-112 adds `TellusEarthBiomeClassifierAdapter`, a narrow production-wiring boundary implementing the Earth biome kernel resolver. Its explicit order is:

1. provider resolver: `BiomeClassificationProviders.findBiomeKey(visualCover, koppen, blockX, blockZ, worldScale)`;
2. exact resolver: `BiomeClassification.findBiomeKey(visualCover, koppen)` only when provider returns null;
3. cover fallback: `BiomeClassification.findFallbackKey(visualCover)` only when both provider and exact return null;
4. final `ResourceKey<Biome>` converted to its namespaced string ID; null remains unavailable.

### 16.1 Raw input and range guards

The adapter forwards the kernel's raw inputs without reclassification or normalization:

- visual cover class unchanged;
- raw Köppen code unchanged, including null where fallback fixtures allow it;
- floor block X/Z converted to int only after explicit `Integer.MIN_VALUE..MAX_VALUE` range checks;
- worldScale unchanged.

Out-of-range long coordinates return null before any provider/exact/fallback call, preventing truncation. A null result from all three stages remains unavailable rather than becoming a default biome outside the legacy fallback contract.

### 16.2 Test evidence and unchanged callsites

Lead reported task-112 classifier-adapter targeted test `BUILD SUCCESSFUL`. `TellusEarthBiomeClassifierAdapterTest` covers provider-wins ordering with original inputs, exact-before-fallback ordering, fallback after exact miss, null resolution, out-of-range coordinate fail-closed behavior, and existing production exact/fallback fixtures.

`TellusEarthBiomeClassifierAdapter.production()` is an explicit factory only; no `EarthBiomeSource`, `CubeNetBiomeSource`, `EarthChunkGenerator` or generator callsite was changed. The adapter does not install a classifier hook, register a biome source, schedule work or alter legacy output by construction.

### 16.3 Remaining scope and parity

This adapter now supplies a concrete resolver option for the previously injected bridge, but it does not itself connect the bridge to production generator callsites or claim full Earth/Cube biome replay. The known `bare/BWh` difference remains explicit, and full comparator manifests are still required.

Locked baselines remain:

- Cube/Sphere canonical columns: `13,824`, `0 mismatch`;
- Earth/Cube biome: `7 cases`, `1 mismatch`, known `bare/BWh`.

## 17. Opt-in Earth kernel wiring

Task-114 wires the Earth biome bridge into `TellusColumnDecisionEngine` without changing the default constructor:

- `new TellusColumnDecisionEngine()` keeps `Optional.empty()` Earth kernel and legacy `minecraft:plains` default biome behavior;
- `new TellusColumnDecisionEngine(TellusEarthBiomeDecisionKernel)` explicitly enables the injected Earth resolver;
- `TellusColumnDecisionEngine.withEarthBiomeClassifier()` is an explicit factory for the production provider/exact/fallback adapter;
- the four-argument `decide(..., koppenCode)` overload accepts caller-provided raw climate text; the existing three-argument overload remains and supplies absent climate.

### 17.1 Complete-climate gate

The opt-in kernel is used only when all of these hold:

- an Earth biome kernel was explicitly configured;
- sample is complete;
- raw `koppenCode` is non-null and nonblank;
- coordinate/sample geometry and identity guards pass.

Otherwise the engine returns the existing default biome decision while still computing ordinary terrain/water/strata fields. This prevents partial/unknown climate from becoming a classifier call or an implicit production migration.

### 17.2 BWh and default compatibility evidence

Lead reported task-114 opt-in Earth kernel and CanonicalPipeline targeted tests `BUILD SUCCESSFUL`. The Earth opt-in fixture verifies cover class `60` + raw `BWh` exact classification resolves to `minecraft:desert` when the explicit kernel is injected. A default engine supplied the same climate text still returns the legacy `minecraft:plains` default, proving constructor-level opt-in rather than accidental global activation.

No default `TellusColumnPipeline`, mc1201/mc1211 canonical pipeline, generator constructor, `EarthBiomeSource`, `CubeNetBiomeSource` or runtime/lifecycle callsite was changed to enable the kernel. The opt-in engine is a caller-owned dependency, not a global classifier hook.

## 18. Remaining production scope and parity

Task-114 completes explicit kernel wiring but does not replace Earth biome production callsites. Remaining work includes selecting an explicit consumer, passing climate metadata from the sample provider through the chosen pipeline, comparing all Earth/Cube cases against legacy, and only then enabling a reversible generator path.

Locked baselines remain:

- Cube/Sphere canonical columns: `13,824`, `0 mismatch`;
- Earth/Cube biome: `7 cases`, `1 mismatch`, known `bare/BWh`.

## 19. Explicit Earth classifier pipeline factories

Task-116 adds symmetric mc1201/mc1211 `withEarthBiomeClassifier(...)` factories on the canonical pipelines. They are explicit composition roots, not default wiring:

- default pipeline constructors continue using classifier-free `new TellusColumnDecisionEngine()`;
- opt-in factories use `TellusColumnDecisionEngine.withEarthBiomeClassifier()` or an injected resolver;
- caller supplies sample provider, raw Köppen resolver, feature planner, materializer and mapper;
- the factory composes the shared plan-only pipeline and does not register a generator, registry or lifecycle hook.

The complete-climate gate remains in DecisionEngine: explicit kernel + complete sample + nonblank raw Köppen are required. Otherwise the plan retains legacy default biome behavior. The factory does not change provider/exact/fallback ordering or raw-input forwarding.

Lead reported mc1201/mc1211 targeted factory tests `BUILD SUCCESSFUL`. Tests verify the default pipeline remains plains while an explicitly constructed classifier factory resolves the cover-60/BWh fixture to desert through the injected resolver. No EarthBiomeSource/CubeNetBiomeSource/EarthChunkGenerator callsite or default generator path changed.

## 20. Sample-derived climate pipeline factories

Task-118 adds an explicit sample-derived climate composition path for mc1201/mc1211 canonical pipelines. `TellusColumnPipeline.composeWithSampleClimate(...)` obtains the raw code only from `TellusSample.climate()` when that climate is complete:

- complete climate → `climate.code()` forwarded unchanged to the opt-in Earth kernel;
- absent/partial/UNKNOWN/QUEUED climate → null, so the existing complete-climate gate preserves the default biome decision;
- existing `compose(sampleProvider, decisionEngine, featurePlanner)` remains classifier-free and source-compatible;
- target factories remain explicit and caller-owned.

Lead reported mc1201/mc1211 sample-climate factory tests `BUILD SUCCESSFUL`. Their BWh fixtures prove optional sample climate reaches the explicit resolver path; no sample-climate factory is installed into default pipeline construction, generator callsites, EarthBiomeSource/CubeNetBiomeSource or lifecycle hooks.

The raw-code, visual-cover and coordinate forwarding rules from Sections 14–19 remain unchanged. Rollback is composition-local: stop using `composeWithSampleClimate`/the new target factory and retain the old compose path. Parity remains locked at `13,824 / 0 mismatch` and `7 / 1 bare/BWh` until full manifests are replayed.
