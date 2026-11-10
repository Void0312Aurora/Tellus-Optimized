# Phase61 — mc1211 DFU compatibility review

## Decision

Keep the upgrade compatibility layer mc1211-only, opaque, and opt-in through the
existing real persistence probe. Do not alter Tellus generator Codecs, saved NBT,
DataVersion, canonical/apron ownership, or default runtime lifecycle.

## Root cause

Fabric dimensions 4.0.0's `TaggedChoiceTypeMixin` was compiled against the
older DFU callback shape and returns `Codec.PASSTHROUGH` for an unknown choice.
DFU 8.0.16's `TaggedChoiceType.getMapCodec` now produces
`DataResult<? extends MapCodec<?>>`. On the existing 1.20.1 Tellus save,
`tellus:cube_net` reached that path during the 1.21.1 `level.dat` upgrade and
the DataResult success value was cast to `MapCodec`, producing the recorded
`Codec$20` ClassCastException before readiness.

## Implementation boundary

`TellusTaggedChoiceTypeMixin` redirects only the private DFU dispatch call. For
known choices it reconstructs the original `MapCodec.assumeMapUnsafe(type.codec())`
result; for an unknown choice it applies `TellusDfu1211Compat`'s exact
`type`/`minecraft:noise`/`minecraft:flat`/registered-Tellus-ID guard and returns
`MapCodec.assumeMapUnsafe(Codec.PASSTHROUGH)`. The ordinary unknown-key error
remains unchanged outside that guard. Config priority `1100` places the redirect
before Fabric's default-priority injection, while redirecting the dispatch
avoids executing the incompatible callback for the Tellus case.

The current mc1211 entrypoint registers `tellus:earth` and `tellus:cube_net`.
`tellus:sphere_face` remains excluded because no mc1211 chunk-generator Codec is
registered for it. This is an explicit compatibility boundary, not a broad
unknown-generator bypass.

## Evidence

The focused contract suite is 3/3. The real copied-world run in
`docs/validation/round61-mc1211-dfu-persistence/live/mc1211` upgrades a native
DataVersion 3465 world to 3955, passes capture fault rollback and a separate
stop/restart verify, and preserves all source manifests. The original Round60
mc1201 PASS remains unchanged.

## Rollback and limits

Rollback is removing the mc1211 helper, mixin entry, and focused test; the
existing Fabric/legacy path and saved data remain untouched. Round61 does not
claim default lifecycle readiness, crash durability, persistent quarantine,
nonempty tick/entity/marker scenarios, or whole-objective completion.
