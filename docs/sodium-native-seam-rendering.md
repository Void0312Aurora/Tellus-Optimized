# Sodium native seam staging (2026-10-26)

Fabric 1.20.1 now has a native cube-net staging backend for exactly
`Sodium 0.5.13+mc1.20.1`. Other Sodium versions retain the unsupported-renderer
rejection path. Vanilla continues to use its existing staged `ViewArea`.
This is a bounded prototype acceptance result, not complete renderer-stack
or production-world acceptance.

## Implementation

The destination chunks still use Tellus' separate client staging cache. An
independent Sodium `RenderSectionManager` builds the destination's native
sections without replacing the active source renderer or its visibility graph.
Because this manager is off-screen, Tellus explicitly queues `INITIAL_BUILD`
sections; Sodium's ordinary visibility collector would never queue them.

Ready requires the full configured square and vertical section range, built
section information, no pending builds or cancellation tokens, and an empty
worker queue after mesh uploads. A completed CPU task alone is insufficient.
Commit promotes the normal chunk packets, rebinds native block-entity references
to the live chunks, installs the native manager, and destroys the source manager.
The first subsequent Sodium terrain update builds the destination visibility
list before drawing.

Cancellation stops the staged workers before unloading their chunks. Sodium
renderer reload/unload also invalidates the staged manager, clears the client
transition, and reports rejection to the server. Unsupported preparation has
a 200-tick retry delay. The backend bridge and its native types are isolated
from vanilla class loading, and all native mixins use the exact-version gate.

Directional block shade is mapped in Sodium's `WorldSlice` sampler, shared by
its flat/AO pipelines and Indium. Fluids additionally rotate Sodium's separate
horizontal face brightness factor. The pure rotation contract now checks every
direction on all 14 directed cut seams against the actual apron block rotation.
This does not transform shader-pack lighting or establish shader correctness.

The server's chunk-retry capability check also needed correction:
`ServerPlayNetworking.canSend` queries client **receivers**, whereas the receipt
packet travels client-to-server. Atomic-commit reception now identifies clients
with the receipt protocol. This restores bounded chunk retransmission when the
initial stream arrives before a joining client's staging cache is ready.

## Validation

The [evidence directory](validation/sodium-native-20261026/) contains a build
log, counts, filtered transition logs, and sign screenshots. Full disposable
saves, raw logs, and recordings remain in
`tools/benchmark-results/sodium-native-20261026/`; these are intentionally not
published as repository artifacts.

The fixture uses analytic terrain at scale 1000, Y `[0,256)`, render distance 4,
and an apron of 7 chunks. Each destination contains 121 streamed chunks and
1296 native render sections. The native stack is Fabric Loader 0.16.14,
Fabric API 0.92.12, Sodium 0.5.13, Iris 1.7.6, and Indium 1.0.36. No shader pack
was selected. The development server uses Loader 0.16.10 / Fabric API 0.92.8.

Initial Prism native preparation and a forward commit ran on the RTX 3090.
Further deterministic input and capture runs used an isolated Prism display
with Mesa llvmpipe, including the final native forward/reverse and live-object
checks. These software-rendered timings are not GPU performance benchmarks.

Observed native cases:

- A prepared destination could be cancelled; worker count returned from 20 to
  the source renderer's 10 in the hardware run.
- Renderer reload after ready destroyed the stage, rejected that transition,
  and allowed a new stage without a client crash.
- Forward and reverse commits completed through the native manager. The final
  paired run restored shade turns `0 -> 3 -> 0`; both first committed frames
  reported 68 visible sections out of 1296.
- The marked stone platform and red/blue columns remained visible in the
  reviewed transition recordings. This is visual inspection, not a zero-frame
  discontinuity or frame-time guarantee.
- Forward promotion rebound two block entities. A sign initially displayed
  `Native seam / before commit`; changing the live canonical sign after commit
  visibly displayed `Live object / AFTER commit`. A chest was present in the
  scene. This does not establish all block-entity families or container GUI
  behavior across commit.
- A real Sodium `0.5.12-beta.2+mc1.20.1` client started and rendered normally,
  rejected preparation and attempted transfer, and stayed in the source chart.
  This run used Fabric and Sodium without Iris/Indium. No native staging mixins
  applied; repeated preparation was separated by approximately ten seconds.

Run the automated checks with:

```sh
./gradlew -PtellusTargets=mc1201 :mc1201:build --offline --max-workers=2 --console=plain
```

The full target build passed: 960 ordinary tests (949 passed, 11 skipped) and
7 parallel-generation tests (6 passed, 1 skipped), no failures or errors. The
artifact hash and final regression observations are recorded in
[summary.json](validation/sodium-native-20261026/summary.json).

## Repeating the interactive checks

Create a disposable cube-net world with `tools/create_cube_net_datapack.py`,
scale 1000, minimum Y 0, height 256, apron 7. Use server view/simulation distance
4 and client view 4. Warm the source `(15360,-1)` and target `(10216,5120)`
regions before applying the client fixture; writing platforms before initial
apron materialization can have them replaced by that materialization.

For a short forward gate, place the player at `(15360.5,100,22.5)`, yaw 0,
then wait for the matching native ready log before walking south past Z=24.
For reverse, use `(10262.5,100,5120.5)`, yaw 270, then walk east past X=10264.
These are short movement tests near the rebase threshold, not full walking
routes from a face interior. Check transition identities, native promotion,
the first rendered frame, and cumulative shade turns in both logs.

Turn away while ready to test cancellation, and use F3+A while ready to test
renderer invalidation. Edit a sign after commit and inspect the actual client
text. Repeat with Sodium absent and with an unsupported Sodium version. For
the join/retry gate, save the player inside the apron beyond its rebase depth,
restart the client, and verify eventual chunk receipt and commit without the
60-second timeout seen before the retry fix.

## Remaining acceptance gates

The default 4064-block world height, larger view distances, first-generation
real terrain at seams, long runs and memory stability, all entity/block-entity
families, active shader packs, and Distant Horizons still need separate native
acceptance. Unsupported combinations and failed preparation retain the safe
rejection path. Preparation can take several seconds; neither these runs nor
the implementation establish a stall-free handoff.
