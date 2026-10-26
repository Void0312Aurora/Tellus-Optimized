# Cube-net seam continuity plan

## Current diagnosis

The cube-net runtime keeps one ordinary Minecraft `Level` and one planar
`ChunkPos` space. A cut seam therefore has two distinct engine locations even
when the two face charts describe adjacent surface points.

The first native apron slice is now implemented. `CubeNetApronResolver` maps a
cut-edge strip from the source chart to canonical target-face cells, and
`CubeNetChunkGenerator` materializes complete derived `LevelChunk` instances
for two native chunks of apron depth. They therefore use the ordinary server
chunk packet, client `ClientChunkCache`, block models, vanilla light pipeline,
fluids, and heightmaps. Canonical face data remains the only terrain source;
the apron is a derived view.

The old render-only block preview is no longer registered or sent; its source
files have been removed. The native chunk path is now the only seam terrain
path.

Two independent discontinuities can therefore be visible at a cut:

1. While the destination is loading, the server holds the player and sends
   position corrections. Local prediction and those corrections can alternate
   between frames.
2. At rebase, the logical position changes to a distant planar coordinate.
   Vanilla entity interpolation must not interpolate through the unused part of
   the cube net, so the target chunk meshes are loaded before the authoritative
   teleport is released.

The runtime now first moves the player into the native apron through a short
same-chart correction. It holds the player only while the proxy chunk area is
loaded. After the player reaches 24 blocks of apron depth, a hysteretic
rebase moves ownership to the canonical target-face chart. The client still
resets its previous position/rotation on an explicit teleport correction.
This removes the old render-only bridge, but the rebase and entity/state
handoff still need live visual acceptance.

Chunk persistence follows the same ownership rule: derived apron positions are
skipped by `ChunkMap.save`, and ordinary `Level#setBlock` writes are forwarded
to the canonical target chunk with the inverse grid rotation while a loaded
apron cache is refreshed. A sparse persistent block-position index rebuilds
edited proxy cells after unload/reload. Block-entity NBT is synchronized in
both directions for the tested chest path; arbitrary entity ownership and
general neighbour-update behavior still need broader acceptance beyond the
moving-arrow and redstone fixtures below.

## Required transition protocol

Treat a cut crossing as one versioned transition rather than as an ordinary
teleport:

```text
IDLE -> PREPARE -> STAGED -> COMMIT -> RELEASE
```

### PREPARE

At the configured seam distance, the server creates a transition record and
loads the source-chart apron plus the target 3x3 area. Local movement is held
while a cold target area is unavailable, so a stale movement packet cannot
start a second transfer.

### STAGED

The server starts the ordinary `ChunkStatus.FULL` futures for the target area
and, when the client advertises the transition protocol, immediately sends a
UUID-scoped `cube_net_transition_prepare` message. The client waits until the
target 3x3 area is present in its `ClientChunkCache` and replies with
`cube_net_transition_ready` carrying the same UUID. COMMIT still waits for
both the server futures and this client acknowledgement. A late or mismatched
reply cannot complete another transition. Clients that do not advertise the
packet type retain the earlier server-only staging path.

### COMMIT

On a server tick boundary, after both server and client staging are complete,
the server sends the target logical position and yaw through the ordinary
teleport correction, resets the entity's previous pose, and transfers velocity
with the seam rotation. This prevents interpolation across the empty planar
gap.

### RELEASE

After the target futures complete, the server releases the movement gate. A
timeout returns the player to the source pose instead of exposing an empty
target view.

## Coordinate and camera rule

The seam mapping must be used for position, velocity, yaw, and rendered target
geometry. For a target point `p_t`, map it into the source render chart with

```text
q_proxy = q_edge + planarVector(sourceFace,
            transportVector(targetFace, targetSide, p_t - p_targetEdge))
```

The same tangent transform is used for the camera forward vector. It is not
enough to transform only the player's block position: a mismatched yaw or
interpolated previous pose produces a visible rotation jump even when the
terrain itself lines up.

The native chunk path should keep the source apron and target 3x3 area loaded
until the teleport correction has been accepted. The source and derived chunks
must have disjoint planar ownership, so the client cannot receive two meshes
for the same engine position. A future LOD path must apply the same seam map
before it adds any distant proxy geometry.

## Next implementation order

1. **Reuse audit complete:** the community reference and the Tellus-specific
   boundary are recorded in [the reuse audit](cube-net-reuse-audit.md).
   Immersive Portals is a reference for passenger, chunk-loader, and client
   vehicle patterns, not a direct dependency for Tellus's single-Level chart.
2. **Prototype complete:** versioned PREPARE/STAGED/COMMIT/RELEASE states and
   correction counters are implemented.
3. **Partial:** live fluid propagation in both directions, chest
   synchronization/persistence, sign synchronization, and a redstone
   neighbor-update chain are verified. A moving arrow, a non-player boat with
   an armor-stand passenger, and a bounded local non-player leash bundle
   completed apron rebases. A real player-mounted route now completed one
   cut/apron rebase in a bounded live client run. Broader entity and
   block-entity types remain open.
4. **Mounted transfer exercised, visual gate still open:** the internal
   transfer bundle has target-chunk staging, entity teleport, passenger
   restoration, stale vehicle-packet filtering, and a bounded mounted
   correction/hysteresis path. A live traversal using the explicit target
   `AddEntity` packet retained the player/boat relation and produced no unknown
   passenger-entity warning. The sampled recording still shows a view jump at
   rebase. Do not add broad entity or block-entity coverage until the mounted
   handoff is reviewed frame by frame.
5. **Extended walk routes complete, visual gate open:** the ordinary Fabric
   client completed long walk routes in both directions on an extended fixture.
   An exhaustive encoded-frame pass found no all-black or near-black frame, but
   transition grids show a short sky-only interval around the seam rebase before
   the runway returns. The recordings also contain substantial repeated and
   dropped source frames, so this is not frame-by-frame continuity evidence.
   Earlier short-fixture walk and sprint routes remain separate evidence.
6. **Not started:** Distant Horizons, Sodium, shaders, and other renderer
   combinations. The current client profile does not contain those mods.

The current implementation has the native terrain/apron slice, persistence
guard, block-state write-through, neighbor-driven redstone propagation, and
focused server tests. It is not yet evidence of production-grade continuous
crossing: broader leash coverage, multi-player mounted trees, broad
block-entity coverage, frame-by-frame visual continuity, and third-party
renderers remain open gates.

## Boundary contract test layer

The first architecture item is now represented by a shared pure-Java contract
test, `CubeNetBoundaryContractTest`. It checks the six-face ownership chart
before any Minecraft world or client is involved:

- all 24 directed physical edges exist exactly once and have one reciprocal;
- edge coordinates round-trip through every seam, including reversed edges;
- every target-to-source transform is an orientation-preserving quarter-turn;
- the affine position transform and its grid inverse recover the same face
  coordinate;
- a single boundary crossing transports only the remaining displacement;
- a step that reaches two edges is rejected until a vertex policy exists;
- interior chart points have one owner, while direct shared net edges are not
  implicitly assigned to both faces.

The native apron layer adds two more checks: non-overlapping samples in every
cut strip resolve to one canonical target block and reverse lookup finds the
same proxy projection; orientation-sensitive block states round-trip through
the seam quarter-turn on all 14 directed cut edges. Samples where two wide
apron strips overlap near a concave net vertex are intentionally rejected.
That rejection is currently the corner quarantine policy; it is not silently
assigned to whichever strip happens to be visited first.

The client staging handshake is implemented for the 1.20.1 Fabric path in
`CubeNetTransitionPreparePayload`, `CubeNetTransitionReadyPayload`, and
`CubeNetClientTransition`. It is a readiness gate, not a second renderer or a
double-buffered world. The client still uses vanilla chunk packets and the
ordinary chunk cache; a frame-by-frame visual continuity gate remains open.

This test layer is shared by the Fabric module and is independent of native
chunk packets. A failure here blocks later apron, entity, and renderer work;
it prevents those layers from silently introducing a second seam convention.
The full `:mc1201:test` run after this layer added reports 733 tests, with
zero failures and zero errors; two unrelated tests remain skipped by their
existing environment guards.

## 2026-10-18 status refresh

The edited-cell overlay and chest payload path now pass a live restart check on
the isolated `server-11` world. A diamond stored in the proxy chest at
`(15360,100,10)` was present in its canonical owner at `(10229,100,5120)`;
after a normal server restart, both locations still reported the diamond. The
same online session also verified proxy-to-canonical and
canonical-to-proxy chest edits. This covers that chest path, not every block
entity implementation.

The first reload pass exposed a real startup defect: synchronously changing a
chunk from its `CHUNK_LOAD` callback waited on the chunk still being loaded.
Refresh is now deferred to `END_WORLD_TICK`, and startup filters by the sparse
canonical edit index. A later crossing exposed a second defect: refreshing
neighboring chunks modified the pending-refresh map during iteration and
crashed the server. The retry now processes a stable key snapshot and removes
each entry before refresh. Full tests passed after this fix, and the repeated
live routes completed without a server exception.

Fluid propagation in the forward and return seam directions was verified in
the 2026-10-17 live probe. The 2026-10-18 prototype stages non-player entities
at apron depth 24, asynchronously loads the canonical target 3x3 chunks, holds
the entity during the load, checks destination collision, and applies the
target position, yaw, and velocity. A moving arrow completed this path, and a
boat with an armor-stand passenger completed it as one two-member tree. The
boat and passenger both arrived at `(10215.5,101,5120.5)` and the server
retained the passenger list. The `tellus_visualtest:leash_probe` fixture then
staged two no-AI, no-gravity cows after their leash relationship was established
outside the apron. They crossed as one two-member component: the target reached
`(10215.5,101,5120.5)`, the holder reached `(10209.5,101,5120.5)`, and the
target `Leash.UUID` matched the holder UUID. A clean restart preserved both
positions and the same leash UUID, with `pending transfers: 0`. This is
server-side evidence only; leashes involving players, distant endpoints, or
external passenger trees remain rejected.

The same live session also exercised a sign block entity. A sign placed at
proxy `(15360,100,12)` mirrored its four-line `front_text` payload to canonical
`(10227,100,5120)`. Editing the canonical payload to `Canonical sign` and
`NEG_Z` then produced the same payload at the proxy. This confirms the generic
block-entity payload path for this sign and the tested chest; it does not cover
all block-entity types or unloaded-target persistence.

The reuse audit and compatibility spike now separate the next gates. The
community reference confirms the value of a transfer-bundle model, but it does
not provide a drop-in same-Level implementation. The local runtime successfully
transfers a non-player vehicle, a vehicle with a non-player passenger, and now a
player-mounted vehicle through the same bundle contract. The direct seam path
still rejects mounts explicitly; the player-mounted result below is for the
apron rebase path only.

## 2026-10-19 player-mounted bundle probe

The first-run observations in this section are historical; the later mounted
traversal follow-up below records the current live result and next step.

The dedicated `/tellus cube_net mount_probe` command creates a server-owned boat
and attaches the current player before the apron tick. In a fresh
`server-mount-check6` world, the runtime staged `members=2`, sent the canonical
3x3 target area through `ClientboundLevelChunkWithLightPacket` after moving the
client cache center, and committed the bundle at `(10215.5,101,5120.5)`.
The client position remained `(10215.5,100.55,5120.5)` and the server's
`RootVehicle` query continued to point to the same boat at the target position.
After a 12-second stability wait, `/tellus cube_net status` reported
`pending transfers: 0`; no second transfer was pending and the boat remained at
the target coordinate.

The first run after adding target chunk packets exposed two compatibility gaps:
a stale `MoveVehicle` packet could restore the boat's source position after the
server commit, and the vehicle could be re-staged immediately while it was
still inside the destination apron. `ServerVehicleMovePacketMixin` now keeps a
bounded mounted correction gate, rate-limits target resends, and uses destination
apron hysteresis. Focused Java tests and a remapped jar build pass.

After explicitly dismounting the persisted probe from the previous save, the
next live run produced one `staged -> complete` pair for the two-member bundle.
The same disposable world then suffered severe tick delay from its force-loaded
area; the client timed out and the follow-up native recording failed
finalization. This reduces the duplicate-rebase symptom in this setup, but is
not a stability pass. The visual gate remains open because the client cache
center changes before commit and must still be reviewed for a frame-visible gap.

The first finalized recording showed a sea/sky-only handoff; the client also
logged an unknown-entity passenger packet and target chunks rejected as outside
the view range. A fresh bounded world is required for the next visual run.

At the time of the first runs, the next item was a fresh bounded-world
recording. The follow-up below completed that run and moved the remaining work
to investigating its cache-center/rebase view jump. Broader block-entity types,
distant leash endpoints, multi-player passenger trees, and third-party
renderer testing remain behind that gate.

## 2026-10-19 mounted traversal follow-up

The latest remapped jar was exercised in the bounded `server-mount-check6`
world with the player and boat at `(15360.5,101,32.5)`, facing north.
**Correction on 2026-10-19:** this positive-Z coordinate is already in the
POS_X apron. The route tested an apron rebase, not a traversal from the face
interior across the physical cut. The probe has since been corrected to start
at `(15360.5,101,-32.5)`, yaw 0, and travel south across Z=0 and then depth 24.
The server logged one two-member `staged -> complete` transfer from about
`(15360.5,31.97)` to `(10208.03,5120.5)`. Near the end of the 120-second run,
the player remained mounted at `(10219.31,100.07,5120.5)`, yaw `-90`, and the
boat `RootVehicle` remained at the same target coordinates. Status showed no
pending transfer and counters `sent=1, resent=19, acknowledged=1, rejected=196,
expired=1`.

The native 854x480/30 fps recording finalized as `mount-crossing-real.mp4` with
3600 encoded frames. Its sampled transition frames contain no black interval,
and the red landmark remains visible. They do show a pronounced view change:
the source-side sea/horizon becomes an enlarged green landing platform over
roughly seconds 20–25. The latest client log has no unknown-entity passenger
warning and no out-of-view target-chunk warning, so the explicit `AddEntity`
packet fixed the previously observed missing client entity for this run. This
does not establish seamless rendering: mounted bundle transfer is now
server-stable in this bounded route, while pixel-continuous visual acceptance
still fails. The next ordered work is to investigate the cache-center/rebase
view jump with a landmark-visible third-person capture; do not broaden the
entity matrix until that gate is closed.

The same live session also exercised a cross-seam redstone chain. A proxy
redstone block at `(15360,100,21)` changed the canonical wire cells at
`(10215,100,5120)` and `(10216,100,5120)` to the expected powered levels,
and the canonical lamp at `(10214,100,5120)` and proxy lamp at
`(15360,100,25)` both lit. Removing the proxy power source returned the last
proxy/owner wire pair to power `0` and turned both lamps off. A canonical
source write after restart also powered both lamps and the last wire pair.
This verifies the loaded proxy
neighbor-update route for this redstone fixture; it does not cover every
scheduled-tick or block-state family. The line spans apron depth 24, not the
physical face boundary at depth 0; general cross-face redstone behavior is
still open.

The extended visual fixture now covers the source approach from `Z=-256`, the
native apron through `Z=63`, and the canonical landing strip from `X=10000` to
`X=10239`. On 2026-10-18, walking 160 blocks from each visual start completed:
the cut ended near `(10155.91,100,5120.5)` and return near
`(15360.5,100,-84.33)`. The encoded cut and return streams contain 2525 and
2496 frames at 854x480/30 fps, respectively; full-frame scanning found zero
all-black or near-black frames and ffprobe found no encoded timestamp gap over
50 ms. The seam windows still contain sky-only frames before the runway
reappears, and the native recorder reported 721 dropped/817 duplicate source
frames on cut and 484 dropped/628 duplicate frames on return. The evidence
therefore confirms a visible arrival path with a short rendered gap, not
pixel-continuous crossing. The first long attempt also exposed an undersized
target runway; the fixture was extended and the successful routes were
repeated afterward.

The moving-arrow probe began at `(15360.5,100,24.5)`. After one movement tick,
the runtime staged it from apron depth `24.75` and committed it at
`(10215.25,100,5120.5)` after target chunks loaded. A later query found it at
`(10190.54,100,5120.5)`, heading `-90°`, with velocity already near zero. The
negative target-X displacement confirms the rotated movement direction; this is
not a calibrated velocity-magnitude test.

## 2026-10-19 staged renderer and mounted correction fixes

The current renderer work keeps source chunks active while a separate target
cache and native ViewArea are prepared. The 5x5 data area supplies a neighbor
halo for the inner 3x3 meshes; 63 sections near the camera were compiled in
these bounded view-distance-2 runs. The ready acknowledgement now means that
those meshes exist, rather than only that chunk packets arrived. The first
target visibility update runs before its first rendered frame.

Live diagnostics then found a separate race: the player and vehicle packet
filters mutated the passenger tree on Netty before vanilla scheduled the
handlers on the server thread. This could restore a boat's source pose during
commit, and an outgoing native vehicle packet was captured carrying that stale
pose. Both filters now run after `ensureRunningOnSameThread`. The runtime sends
the controlled vehicle correction before the player correction; the client
places passengers and resets their render history before installing the staged
view. Staged clients retain their existing entities and mount relationships.

The proper interior-to-cut mounted route subsequently kept all eight traced
post-commit frames in the target chart with terrain present. Vanilla also keeps
six vehicle movement baseline coordinates separate from its player baseline;
those are now reset at commit. The 19:38:12..47 handoff window with the baseline
adapter had no movement warning or disconnect. It still showed 19 resends and
264 rejected packets, and dense video frames showed camera/boat jitter. The
remaining fixed-destination correction guard rejected ordinary movement beyond
two blocks after acknowledgement; the acknowledged path now follows the
vehicle's current position while retaining the chart-jump check.

See `cube-net-client-acceptance.md`, section "2026-10-19 render staging
investigation", for failed recordings, exact artifact hashes, frame statistics
and the final follow-up. This remains a warm synthetic-terrain RFC test. Full
visual acceptance, return traversal, larger view distances and renderer
compatibility remain separate gates; additional entity and block-entity cases
stay behind the visual gate in `cube-net-reuse-audit.md`.

The final acknowledged-movement fix was tested twice with jar SHA-256
`4364F8B20569284EE404BD8E9918FE489228338F73B534B018E1F0A6E3842B4A`.
Both first- and third-person mounted routes continued another 74.80 blocks
after rebase; two-transfer status reported zero correction resends and no
pending transfer. The handoff windows contained no movement warning or
disconnect, and every traced post-commit frame contained terrain. All 733
MC 1.20.1 tests passed. A native-resolution review at 16, 18, 20 and 30 seconds
corrected the subsequent player-disappearance claim: the mounted player is
visible in those frames. The complete visual gate remains open. Next cover the
configured view with sufficient apron and compiled meshes, and repeat
return/cold-load routes before expanding
entity families. Recorder rates, dropped/duplicate counts and exact evidence
windows are in the client acceptance document.

The configured-view implementation uses the advertised client distance capped by
server view, stages one extra neighbor ring and retains that radius through chunk
tickets and cleanup. It prepares all vertical sections and checks source apron
width against view/simulation distance plus the handoff depth. The earlier
64-block-height view-2/view-4 result used jar
`4DD9FF6F9CF3A500E04DAD37102F967383E1ACB45262F94FEDCBEA24F9A51FEC` and remains
the bounded coverage baseline. The 2026-10-20 full-height asynchronous result is
recorded above; return/walk/sprint/cold routes remain next.

## 2026-10-20 full-height asynchronous staging

The old render-thread staging loop was measured on the original 4064-block
height and timed out while compiling 6,350 sections. It has been replaced by a
bounded queue of vanilla rebuild-worker tasks with a shared region snapshot and
the target chart's compilation camera. The 4064-height fixture now completed
view-2 staging in 6.602 s (6,350 sections) and view-4 staging in 20.762 s
(20,574 sections). Both mounted routes crossed and continued under forward input;
the view-2 route continued 127.0 blocks and the view-4 route 406.8 blocks.

This is measured warm synthetic evidence. The preparation starts 128 blocks
before the seam, so it does not establish cold loading, reverse traversal, or a
zero-hold visual handoff. Detailed recorder data and artifact paths are in
`cube-net-client-acceptance.md`.

The reverse mounted route was then run from the target side with view distance 2.
It staged 6,350 sections in 5.786 s, committed at source-side Z=-24.3 and
continued to Z=-147.86 (about 123.6 blocks beyond landing). The two-member boat
bundle completed with no correction resend. This closes the measured warm reverse
direction for the mounted fixture.

## 2026-10-20 cold reload and elevated movement follow-up

The source and target positions were verified unloaded after removing all
forced tickets. A saved-chunk reload staged 6,350 full-height sections in
13.268 s on the client; the server-side load was 6.376 s. The seam committed at
depth 24 and the mounted route continued about 243 blocks after landing. This
measures saved synthetic chunks, not first-generation terrain creation.

A smooth-stone runway at Y=101 put the player at Y=102 with `onGround=true`.
Walk and sprint were each run in both directions. All four routes staged 6,350
sections and crossed at depth 24. Walk continued 53.6 blocks after landing in
each direction; sprint continued 85.7 and 85.9 blocks. The inspected dense video
samples retain the runway and guide without an empty terrain frame. Setup
teleports produced server movement warnings; the seam-crossing intervals did
not. The earlier sea-level attempt was excluded because it allowed water
movement. Exact recordings, frame rates and limitations are in the acceptance
record.

These measurements close the cold reload and elevated movement routes for the
synthetic fixture. Frame-by-frame zero-hold equivalence, first-generation
terrain loading, and alternate renderer combinations remain open.

## 2026-10-20 saved-edit overlay recovery

Dense native-frame review found a reverse handoff extent jump despite zero
black frames. Restarting the same saved fixture then exposed a 16-block
missing apron runway: canonical cells remained lime concrete, but proxy
X=10240/10248/10255 did not. The unmodified-save return attempt fell into the
water and stopped before rebasing.

The overlay retry loop now retains views that have not yet entered
`getChunkNow`, requests their edited owners asynchronously under temporary
tickets, and keeps packet staging blocked until the overlay is applied.
Tickets are released on completion, unload or timeout; failed overlays remain
unready. A retry-only intermediate build demonstrated why owner loading was
also required: it reported timeouts instead of recovering the missing strip.

The final build passes 741 tests (0 failures/errors, 2 existing skips), client
compilation and remapping. On an unchanged-save restart all ten owner/proxy
pairs matched. One reverse walk completed at Y=102, with 53.23 blocks of
movement after landing, 6,350 staged sections and 7.299 s client preparation.
Its 952-frame native recording has zero near-black frames and no former
water-to-runway replacement in the reviewed handoff window. Camera/hand
changes and recorder drops remain, so this is a saved-edit recovery fix with
bounded visual evidence, not full seamless acceptance. The acceptance record
contains hashes, logs, frame indices and the remaining route order.


## 2026-10-20 ready-chart pause lifecycle

The saved-edit repair has forward/reverse walking, sprinting and mounted route
evidence on the saved synthetic fixture. A longer pause found another real
visual defect: server-side preparation and client-side staging both spent their
1200-tick loading timeout after becoming ready. Fixing only the server preserved
the movement path but let the client discard its compiled chart. The resulting
recording had eight sky-only terrain frames, despite zero near-black frames.

Both lifecycles now retain successfully prepared charts until commit/cancel.
Incomplete/failed loading still times out, and world/cache/view changes and
leaving the approach still cancel preparation. The final jar is
`2BEEA75920715D841E2AF52ABF64B74A4A0E3A76AFDE062AC3CB58D3B444BC15` and the
744-test Java suite has 0 failures/errors and 2 existing skips. The real client
kept one ready chart from 13:33:07 to commit at 13:35:25, then continued at
Y=102 on the runway. The 4013-frame recording did not reproduce the terrain
hole; reviewed native frames retain the runway and marker columns. This closes
the observed ready-timeout defect on this fixture, while exact zero-hold
visual equivalence, first-generation terrain and renderer matrices stay open.
The acceptance record and `server-approach-check1/repaired-build-route-summary.json`
retain the negative case and final-build evidence separately.

## 2026-10-20 short-pulse visual handoff check

A short forward pulse moved the player from apron depth 23.98 to 24.078 before
the ready-chart commit. The chart mapped to the target face at yaw 90, with
6,350 client sections staged in 5.719 s. In the 854x480 recording, the runway
and marker columns remain present through the reviewed commit window; no
obvious view jump or blank interval appeared. The highest adjacent-frame
difference was 7.77, in a clip that still includes the player's movement.

Paired source/target stills from a follow-up short-pulse run preserve the runway
and landmark geometry in nearly the same screen positions, but the yellow and
red marker side-face shading changes across the commit. The yellow marker's
representative brighter/darker face means swap from RGB `(189,137,17)` /
`(141,103,12)` to `(142,103,12)` / `(189,137,17)`. This is a confirmed visual
discontinuity on the warm synthetic fixture. Inspection of the local Minecraft
1.20.1 client bytecode confirms that `ClientLevel.getShade` assigns horizontal
face factors `0.8` and `0.6`, while `ModelBlockRenderer` selects them by quad
direction. The `0.8 / 0.6` ratio (1.333) matches the measured bright/dark
marker ratio (1.340), and those sides exchange across the quarter-turn. This
confirms vanilla direction-based shading as the cause of this captured defect.

The short-pulse route therefore rules out an obvious whole-view cut in the
captured interval but **fails overall visual acceptance** because a visible
shading change crosses the seam. Duplicate/dropped video frames and the small
player motion still prevent a claim about exact pixel equivalence. Next test a
rotation-invariant horizontal side-shade fallback on the warmed runway, then
check whether a position-aware chart light basis can preserve vanilla shading
without the global visual change. First-generation terrain and renderer
compatibility remain open.
Full recording, stills, hashes and client/server evidence are indexed in
`cube-net-client-acceptance.md` and the corresponding
`server-approach-check1/*handoff*20261020*` artifacts.

## Motion continuity measurement

Use displacement over a tick window as the speed signal, while retaining the
full signed vector, ground state and commit tick. Acceleration and input
duration can change speed on ordinary flat ground, so compare windows with the
same movement key and grounded state. Map target-chart positions and velocities
back through the inverse seam rotation before comparing them with the source
chart. Server `deltaMovement` alone may not describe the displacement observed
from accepted client movement packets.

A live walking trace on the warmed flat synthetic runway held W continuously
for 12,026 ms. The source window (ticks 11348–11355) measured 4.317 blocks/s.
After target-chart movement resumed, ticks 11361–11369 averaged 4.154
blocks/s, 3.8% lower. Three complete server ticks after commit had no mapped
displacement while W remained held; the mapped commit position was 0.0196
blocks behind the preceding source sample within the commit tick. Per-tick
values fluctuate between zero and movement packets with larger displacement,
so a multi-tick average exposes sustained speed more reliably than checking
whether every tick is uniform. Horizontal `deltaMovement` stayed zero while
the measured position advanced, making positions the useful source here.

This route demonstrates a short handoff hold and a similar post-resume speed on
the other chart. It does not establish all-direction or sprint continuity. The
raw per-tick samples and window calculations are in
`server-approach-check1/motion-continuity-20261020.json`; renderer continuity
remains a separate gate.
