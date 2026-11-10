# Round61 — mc1211 DFU MapCodec compatibility and upgraded-world persistence

## Gate result

| Gate | Result |
|---|---|
| Focused DFU compatibility contract | **3/3 passed** |
| mc1211 compile/test task | **BUILD SUCCESSFUL** |
| Real copied-world 1.20.1 → 1.21.1 upgrade | **PASS** |
| Native capture fault recovery | **PASS; 3 rollback points** |
| Separate-process stop/restart verification | **PASS** |
| Source world/cache integrity | **PASS; byte-identical manifests** |
| Default lifecycle/general crash-durable transaction | Still gated |

## Narrow compatibility fix

Fabric dimensions `4.0.0+6fc22b9919` injects an unknown-generator fallback into
`TaggedChoiceType.getMapCodec` and returns `Codec.PASSTHROUGH`. DFU `8.0.16`
changed that method's result to `DataResult<? extends MapCodec<?>>`; the old
fallback therefore failed with `Codec$20 cannot be cast to MapCodec` while
upgrading `level.dat`, before server readiness.

The mc1211-only Tellus mixin now wraps DFU's private dispatch call and first
executes Fabric's original operation. It retains the ordinary DFU
`MapCodec.assumeMapUnsafe` path for known choices and returns an opaque
`MapCodec.assumeMapUnsafe` wrapper around `Codec.PASSTHROUGH` only when all of
these guards hold:

1. tagged-choice name is exactly `type`;
2. the schema contains both `minecraft:noise` and `minecraft:flat`;
3. the requested unknown key is exactly a currently registered Tellus generator:
   `tellus:earth` or `tellus:cube_net`.

Other schemas, keys, and already registered choices return Fabric's original
result unchanged.
No generator Codec, NBT field, DataVersion, save encoding, or default lifecycle
registration was changed. `tellus:sphere_face` is intentionally not in this
allow-list because mc1211 does not currently register that generator Codec.

The focused tests cover the schema/key allow-list, wrong-schema and known-key
rejection, and typed opaque payload roundtrip through `JsonOps`.

## Real upgraded-world run

The runner admitted the existing native FULL Cube-Net chunk `616246,-351597`
from the historical Swiss real-GIS world, copied the complete world and cache to
a fresh `D:/Temp` directory, and permitted only the disposable-copy native DFU
upgrade. Source `level.dat` is DataVersion `3465` / Minecraft `1.20.1`; the
copied world was upgraded by the running 1.21.1 server to native DataVersion
`3955`. The original level hash and target region hash matched the prior
Round60 admission.

Capture then read the existing native disk record before demand loading, changed
the same opaque STONE sentinel to GOLD_BLOCK, exercised all three native queue
fault points, and recovered complete old NBT plus the separately loaded cell and
dirty state. The native capture report records `nativeCopiedWorldUpgradeSaved:
true`, `nativeFaultRollbacks: 3`, and `status: PASS`.

The second independent server process restarted the same upgraded copy. It read
disk NBT before loading, matched the expected upgraded record, materialized the
chunk, and matched the full native NBT, all `1,040,384` block cells, all `16,256`
biome quart cells, and the GOLD sentinel. Both phases emitted their native PASS
markers and exited `0`; the runner's final status is `PASS`.

Archived JSON, expected SNBT, complete server logs, and immutable 46-world-file /
10-cache-file manifests are under `live/mc1211`. The source world and cache
remained byte-identical.

## Remaining gates

This closes the Round60 mc1211 upgrade blocker only. The tested native seam still
does not constitute a crash-durable journal, general loaded/world multi-stage
transaction, persistent quarantine, populated nonempty tick/entity/marker
lifecycle acceptance, Earth later-stage production integration, or default
runtime registration. Those gates remain opt-in and are tracked in the deferred
workstream status.
