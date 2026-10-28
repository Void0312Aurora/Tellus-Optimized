# Cube-net interactive client acceptance

This is an RFC prototype acceptance procedure for Fabric 1.20.1, Java 17 and
the ordinary Minecraft renderer. Passing the server packet-sink check does not
establish client movement or rendering acceptance. Distant Horizons, Sodium and
other renderer combinations need separate runs.

## Current status and rendering handoff (2026-10-26)

Ordinary Fabric/Indigo runs now cover walking/sprinting in both directions,
mounted boats, saved-edit recovery, loaded sign/chest synchronization and chest
interaction. The repeated/rebound seam transfer defect is repaired. A brief
handoff stutter remains recorded as a low-priority follow-up. The exclusion
boundary has a scrolling translucent forcefield cue and repeated-contact text.
The generated transition buffer uses the ordinary terrain sampler.

Real terrain at scale 1 passed two 25-chunk interior-area checks, saved reload,
and independent regeneration with all 50 block-state hashes matching. The
portable [terrain results](validation/terrain-stability-20261026/summary.json)
and [saved-chunk inspection](validation/terrain-stability-20261026/saved-terrain-inspection.json)
accompany this record. Initial region entry still produces loading spikes.
The detailed dated sections below describe the observations and their limits.

Sodium 0.5.13 on Fabric 1.20.1 now has a version-pinned native staging
backend. Bounded local Prism runs cover native mesh preparation, forward and
reverse commits, cancellation, renderer reload, and promoted sign/chest object
references. Iris 1.7.6 and Indium 1.0.36 were installed with shaders disabled.
See [the native renderer report](sodium-native-seam-rendering.md) for exact
versions, fixture bounds, evidence, and remaining gates. Distant Horizons,
active shader packs, other Sodium versions, and broader entity/visual coverage
remain separate acceptance work.

Configuration follow-up: the geographic study selected 250 km + 50 km, while
the tested default and datapack helper still use 300 km + 50 km. Normal movement
uses conservative chunk admission, whereas teleport admission uses the exact
point policy. These discrepancies are recorded; this publication does not
change those tested behaviors or claim their unification.

## Earlier acceptance procedure and results

Status at 2026-10-20: the saved-edit overlay repair now has walking,
sprinting and player-mounted boat routes in both directions on the saved
synthetic fixture. A pause regression exposed a second lifecycle gap: the server
kept a ready chart but the client discarded its mesh pool after 60 seconds. Both
sides now retain ready preparation until commit/cancel. A new live pause route
kept the same chart ready for 138 seconds before committing and did not reproduce
the previous eight-frame terrain disappearance.

Full visual acceptance remains open. Zero near-black frames alone does not
establish continuity: the failed pause recording had no near-black frames either.
Exact zero-hold equivalence, camera/hand behavior, first-generation terrain,
shading/geographic orientation and renderer compatibility remain separate gates.
Configured view coverage has bounded evidence at distances 2 and 4 on the
original 4064-block-height fixture. The earlier third-person player-disappearance
claim was corrected by native-frame review. Keep broader entity coverage behind
this visual gate.

## Isolated runs

Use a disposable server world containing the cube-net datapack described in
`cube-net-generator.md`. The fixture below requires `world_scale: 1000.0`, which
aligns each face to **10240 blocks**, and a vertical range containing Y=99..101.
It changes blocks in that disposable world to make raised test platforms.

Copy `tools/fixtures/cube-net-client` to
`<server run directory>/world/datapacks/client-test`. Keep an existing PCL
instance and its saves separate from both run directories.

PowerShell example, run at the repository root:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dtellus.sphere.syntheticSampler=true -Dtellus.chunkgen.threads.max=2'
.\gradlew.bat '-PtellusTargets=mc1201' '-PtellusRunDir=D:\path\to\server-run' '-PtellusBenchmarkHeap=3g' :mc1201:runServer --offline --no-daemon --max-workers=2 --console=plain
```

This uses analytic terrain, not real elevation data. Bind the test server to
`127.0.0.1` with `online-mode=false`. Set `eula=true` after accepting Minecraft's
EULA. Once the server reports ready, execute in its console:

```text
reload
execute in tellus:earth_cube_net run forceload add 5116 -10252 5124 -10228
execute in tellus:earth_cube_net run forceload add 15355 -16 15365 -1
execute in tellus:earth_cube_net run forceload add 10228 5114 10252 5124
op TellusTester
tellus cube_net status
```

The offline test profile is case-sensitive. The name passed to the client
(`TellusTester`) must match the name passed to `op` exactly; an entry for
`tellustester` produces a different offline UUID and does not expose the
server command tree to this client.

Allow those chunks to finish loading before invoking the route functions.
These fixtures keep source/target chunks warm; they do not measure a cold seam
preload. Cold loading must be tested separately without these forced tickets.

In a second PowerShell terminal, launch a client with its own game directory:

```powershell
.\gradlew.bat '-PtellusTargets=mc1201' '-PtellusClientRunDir=D:\path\to\client-run' '-PtellusClientTestServer=127.0.0.1:25565' :mc1201:runClient --offline --no-daemon --max-workers=2 --console=plain
```

The server property names the Quick Play destination and uses `TellusTester` as
the development profile. If a welcome/accessibility screen interrupts Quick Play,
finish that screen and use Multiplayer / Direct Connection / `127.0.0.1:25565`.
Offline Gradle runs require the full client assets and native libraries to be
cached. Quote `-P` arguments in PowerShell, particularly dotted server addresses.

## Manual routes

Press F3 to observe coordinates and yaw. After confirming the operator entry
above, run each function in chat, then walk
forward with W across the colored stripe. Do not use `/tp` to cross the edge:
that bypasses the movement path being tested.

| Chat command | Start | Expected movement |
| --- | --- | --- |
| `/function tellus_test:direct` | X=5120.5, Y=100, Z=-10235.5; yaw=180 | Walk north across Z=-10240. Coordinates continue normally. |
| `/function tellus_test:cut` | X=15360.5, Y=100, Z=-5.5; yaw=0 | Walk south across Z=0. Arrive near X=10216, Z=5120.5, facing east (yaw=90). |
| `/function tellus_test:return` | X=10215.5, Y=100, Z=5120.5; yaw=270 | Walk east across X=10240. Arrive near X=15360.5, Z<0, facing south (yaw=180). |

Record falling, rubber banding, repeated transfer, camera rotation, missing
chunks, disconnection, and the time until destination chunks appear. Repeat the
cut/return pair while walking and sprinting. Place a distinctive block on each
platform, reconnect, and confirm the edits persist.

Current routing materializes a two-chunk native apron outside each cut seam.
The apron is generated from canonical target-face columns and is sent through
the ordinary `ChunkMap`/client chunk path, so water, heightmaps, vanilla block
models, and the normal light stage are exercised by the same mechanism as
ordinary terrain. Crossing first corrects the player into that source-chart
apron; after 24 blocks of depth the runtime rebases to the canonical target
face.

This is a native terrain slice, not a full acceptance result. Persistence
ownership for derived chunks, entities, block entities, directional block
states, correction counts, and third-party renderer behavior still require
separate live tests.

## Visual, light, and fluid probes

The visual fixture uses an extended lime centerline and blue/red/yellow
cross-stripes. The POS_X approach lies at `Z<0`; its native apron at
`(15360, 99, 0..63)` projects into canonical NEG_Z cells along `Z=5120`.
The canonical landing runway extends from `X=10000` to `X=10239`. Blue at apron
depth 24 marks the configured chart rebase. The water pool is on the distant
source approach around Z=-205; the mounted route uses the separate
`mounted_landmarks` function to place red/yellow pillars ahead of the rebase.
The long fixture includes requested writes through depth 63, but the current
two-chunk native apron ends at depth 32. Do not treat those farther proxy
requests as proof that the native apron extends to depth 63.

Run `/function tellus_visualtest:visual_cut` and record a 30 fps native client video
while walking forward 100 blocks. Repeat with
`/function tellus_visualtest:visual_return`. Inspect the cut crossing and the later
24-block rebase as separate events. The recording must use the client's actual
framebuffer size; check `mct record status` before starting. Save still frames
before the cut, immediately after it, immediately before rebase, and after
rebase. The pass condition is no terrain disappearance, black/blank frame,
duplicated or missing marker row, or discontinuous landmark motion beyond the
player's expected per-frame movement. Record walking and sprinting separately.

For deterministic lighting, the benchmark-only command
`/tellus cube_net light <x> <y> <z>` reports the block state and separate sky
and block-light levels at a loaded cell. Launch the test server with
`-Dtellus.benchmark.enabled=true`. Compare proxy/canonical samples at
`(15360,100,0)` vs `(10239,100,5120)` and
`(15360,100,24)` vs `(10215,100,5120)`, with the same time, weather, and
neighboring fixture blocks. Screenshots remain necessary: equal sampled light
values do not prove that the client rendered the two views continuously.

Use `/function tellus_visualtest:water_forward` and
`/function tellus_visualtest:water_return` for separate one-block channels. Each
places one source three cells before the cut, leaving apron cells to
be reached by ordinary fluid propagation. After five seconds, query the
source-chart proxy cells and their canonical target cells with `mct block get`.
The first apron cell must become flowing water and its canonical owner must
show the same fluid state. Also inspect the contained pool during each video
route; that checks water rendering across the 24-block rebase independently
from long-distance flow. Repeat with warm chunks first, then without forced
chunk tickets for cold-load behavior.

The visual and fluid fixture commands change blocks in the disposable test
world. They are not intended for a normal save.

Keep server/client logs with the producing Git revision and dirty state. Report
startup, connection, manual movement, persistence and visual observations as
separate results. A client process or successful teleport alone does not pass
the manual routes.

## Latest runtime result

## 2026-10-20 flat-runway motion trace

The disposable `server-approach-check1` world was extended with a flat stone
runway at Y=99/100 on both sides of the cut. With W held continuously, the
source chart measured about 4.32 blocks/s over the 7-tick window before the
rebase. The mapped-back destination chart measured about 4.15 blocks/s over
the first resumed 8-tick window, a 3.8% difference.

The server trace still showed zero authoritative displacement for the first
three to five server ticks after the seam packet. This is a transport and
server scheduling delay, not evidence that the client stopped rendering: the
client trace showed local displacement from its first post-commit tick, with
W held and a restored horizontal velocity. The delayed server packets arrived
as a batch after the teleport confirmation and were accepted by Tellus's own
correction gate.

The seam handoff now clears vanilla's `awaitingPositionFromClient` when Tellus
takes ownership of the destination correction, while preserving the monotonic
teleport-id sequence. The log recorded
`awaitingPositionAfterRelease=null`; no vanilla movement warning or runtime
exception occurred. This reduces the server-side gate, but does not remove the
client/network confirmation delay. Pixel-level continuity remains open and
needs a frame-aligned recording plus packet timestamps.

### Outbound movement timing follow-up

The next run added a client hook at `LocalPlayer.sendPosition`, so the client
send boundary could be compared with the server's per-tick movement trace. The
client committed transition
`bf88d704-411a-44f5-9abd-ef42d6a891ba` and executed that boundary on all 12
post-commit game ticks `330565..330576`. Its local X position changed on every
sample while W remained held.

The matching server commit was tick `4741`. The authoritative position stayed
fixed on ticks `4742..4746`; several movement packets were then processed in a
batch at tick `4746`, and the first positive post-commit displacement appeared
at tick `4747`. Teleport confirmations arrived at ticks `4741` and `4744`.
The complete extracted evidence and log hashes are in
`server-approach-check1/outbound-motion-trace-20261020.json`, with the copied
client and server logs beside it.

This narrows the jitter mechanism to packet scheduling/processing around the
vanilla teleport confirmation and the custom handoff. Speed measurement is still
useful for restoring the client's local velocity, but it cannot remove a delay
that occurs before the authoritative server observes the packets.

### Atomic commit implementation

The RFC implementation now has a versioned `cube_net_transition_commit_v1`
payload. Once the destination chunks and render view report ready, the server
updates its authoritative player pose without issuing a second vanilla
teleport, then sends the target pose, transported velocity and fall distance in
that payload. The client handles it on the client main thread, installs the
staged chart, sets the pose and velocity, resets interpolation baselines, and
sends the first target position immediately. The old vanilla teleport path is
retained when the peer does not advertise the payload.

The first live run of this path used transition
`eee9330a-ddf7-457f-b43e-a342a6f9931c`. The client committed in custom mode and
executed the movement-send hook on game ticks `343249..343260`, with local
position changing on every sample while W was held. The server committed at
tick `11426`, armed the correction gate with `mechanism=atomic-custom` and
`teleportId=-1`, and accepted the target-position packet at tick `11428`
without a vanilla teleport confirmation. The first positive authoritative
displacement was observed at tick `11430`; this is a shorter scheduling window
than the vanilla baseline (tick `4747` after commit tick `4741`), but it is not
zero. The remaining delay is in client/server packet scheduling and tick
processing, so the custom commit removes the vanilla confirmation dependency
without claiming perfect server-side simultaneity.

The extracted logs and hashes are recorded in
`server-approach-check1/atomic-commit-motion-trace-20261020.json` beside the
copied client and server logs. This is transport and scheduling evidence on one
warmed flat runway; pixel-level visual continuity, wider route coverage and
entity/container handoff remain separate acceptance items.

### First-motion fast path (2026-10-21)

The client now sends one `cube_net_transition_motion_v1` payload with the first
post-commit movement sample. The server accepts it only when the transition ID
matches the active atomic correction gate and the position change is within the
bounded ordinary movement step. Older clients and invalid samples keep the
existing correction and vanilla fallback behavior.

The first run showed the client sending the payload, but the target-position
packet had already removed the gate before the custom payload arrived. The
server-side gate was therefore changed to remain valid for the short custom
sample window. A second live run then accepted transition
`215bccb4-48ba-4ea8-a28a-6d6352785ff9` at server tick `4979`, four ticks after
the atomic commit at tick `4975`, and applied the sample directly. No vanilla
teleport confirmation or `moved too quickly`/`moved wrongly` warning appeared.

This proves the fast-path receiver is active and bounded. It did not reduce the
observed post-commit displacement window in this local run: the motion trace
first observed a changed position at tick `4980` (five ticks after commit),
where the prior atomic run observed tick `11430` (four ticks after commit).
The remaining interval is therefore still governed by client/server packet
arrival and tick scheduling; further reduction requires a prediction or an
earlier client sample and must be measured separately.

Follow-up candidate (2026-10-21): the client now queues the custom sample at
the head of `LocalPlayer.sendPosition`, before vanilla queues that tick's
ordinary movement packet on the same connection. `:mc1201:test` and
`:mc1201:remapJar` passed for this source. The follow-up live run remains
inconclusive. The disposable server required view and simulation distance 2
for the eight-chunk apron. Its first client preparation logged out-of-view
chunk warnings and waited for a chunk with `remote=0 packets=0`; a retry
completed client render staging in 6.3 seconds. With the trace armed, MC Pilot
held W for 12,051 ms, but the client advanced only 2.35 blocks from X=10215.5
to X=10217.852. It did not reach the seam, so the run produced neither a
matching commit nor a first-motion sample. This is not a latency measurement.
The previous five-tick post-commit window remains the latest valid result, and
the queue-order change has not yet been shown to reduce it. The next valid
comparison needs a stable, continuously traversable route and completed client
staging before the trace is armed.

Latest implementation follow-up: on the atomic commit packet, the client now
queues the bounded first-motion sample immediately after installing the target
chart and pose. The `sendPosition` head hook remains a fallback if the direct
send is unavailable. The server still checks the transition ID, active
correction gate, and maximum ordinary movement step before applying it. The
1.20.1 target tests and remapped jar passed after this change. A later live
attempt used that build, but did not reach the seam and could not measure its
effect on the observed four-to-five-tick server displacement window; see the
2026-10-21 retest below.

### Bounded destination chunk retry

The client previously had no acknowledgement for the destination chunk packet
batch. The server marked the batch sent before writing it and never retried,
while the client could wait indefinitely for a missing chunk. The RFC path now
has the client acknowledge receipt after all chunks in the bounded staging area
have entered its remote cache. For clients that advertise this acknowledgement,
the server can send one retry after eight seconds if the receipt has not
arrived, and stops immediately after receipt. The retry is capped at two total
batches. Older clients retain their single-send behavior. Unit coverage checks
the delay, cap, acknowledgement stop, compatibility branch and receipt packet
round-trip. This improves recovery from a missed/incomplete chunk batch; live
end-to-end behavior still needs a route whose client staging area completes and
can be traversed continuously.

The prior valid event extraction and SHA-256 values are in
`server-approach-check1/first-motion-fast-path-20261021.json`, with copied
logs `server-first-motion-20261021.log` and `client-first-motion-20261021.log`.
This inconclusive attempt has a separate result record,
`server-approach-check1/first-motion-head-order-attempt-20261021.json`, plus
the copied logs `server-first-motion-head-order-attempt-20261021.log` and
`client-first-motion-head-order-attempt-20261021.log`.

### Live retest of immediate first motion and chunk staging (2026-10-21)

The rebuilt 1.20.1 client connected to the disposable server with render
distance 2; the server used view and simulation distance 2. One staging attempt
waited with zero remote chunks, then a later preparation received all 49
expected chunks. Client render staging compiled 6,350 sections in 6,195 ms;
the server marked the staging operation ready at 6,198 ms. The client log also
reported remote chunks outside its vanilla view range. This confirms receipt
and local compilation for that batch, but does not establish that the bounded
retry path itself delivered the chunks.

After `/function tellus_test:return`, MC Pilot held W for 15,060 ms. The client
position moved from X=10215.5 to X=10218.44, remained off ground, and did not
reach the seam at X=10240. The test input used `cube_net trace_motion` without
the `/tellus` command root; the registered command is `/tellus cube_net
trace_motion`. The client therefore reported the shortened input as unknown,
so this attempt produced no motion trace, atomic commit,
or first-motion packet timing. It is not a seam-continuity or latency result.
The screenshot showed no visible runway from the player position, so the next
attempt must first establish and verify a traversable client-visible runway
and an armed trace before movement timing begins.

The extraction and evidence hashes are recorded in
`server-approach-check1/seam-retest-20261021.json`, beside the copied logs
`server-seam-retest-20261021.log`, `client-seam-retest-20261021.log`, and the
captured view `seam-retest-20261021-after-setup.png`. After shutdown, the
client jar and server properties were restored to their pre-test hashes.

### Focused bidirectional rerun (2026-10-23)

A fresh disposable Fabric 1.20.1 server and the rebuilt Fabric client were
rerun from RFC head `3b374645041bb28b79ba6e5fa9652d581cdf30e9`. The source
runway was force-loaded and widened after the fixture's first source fill
reported an unloaded position. The trace command was issued through its
registered root, `/tellus cube_net trace_motion`.

The forward route completed from the source chart through the native apron:
the rebase occurred at approximately `(15360.5, 100, 24.1287)` and committed
at `(10215.8713, 100, 5120.5)`, yaw `90`. The reverse route completed after
`/function tellus_test:return`: the rebase occurred at approximately
`(10264.1592, 100, 5120.5)` and committed at `(15360.5, 100, -24.1592)`,
yaw `-180`. Both directions kept the client connected; the final status query
reported `pending transfers: 0`, `sent=2`, `acknowledged=2`, and no expired
transfers. The forward trace contains 12 pre-commit samples, a commit sample,
and 12 post-commit samples. The reverse trace also completed, but its record is
kept as a separate directional run.

The copied log, result record, and three screenshots are in
`server-live-rerun-20261023/`. This is focused runtime evidence for player
rebase and correction handling. It does not close the frame-by-frame visual
continuity gate; Distant Horizons, Sodium, shaders, and other renderer
combinations were not loaded.

On 2026-10-17, an isolated MC Pilot run with the rebuilt Fabric client tested
both directions against a fresh Fabric server world:

1. `/function tellus_test:cut`, then walk south across the cut. The client
   arrived on `NEG_Z` at approximately `X=10215.77, Z=5120.5`, yaw `90`.
2. `/function tellus_test:return`, then walk east across the return cut. The
   client arrived on `POS_X` at `X=15360.5, Z≈-24.16`, yaw `-180`.

Both server-side transfers completed. The client stayed connected and in the
cube-net dimension. The server log contains no vanilla `moved too quickly`,
`moved wrongly`, exception, or error entries for these crossings. It does show
the stale source-chart position packet being rejected by the correction gate
after the return transfer; that is the packet filter doing its intended job,
not a vanilla movement warning. A temporary diagnostic info line present in
the tested artifact was subsequently removed from source; movement behavior
was unchanged.

The change that closed the warning was resetting the vanilla connection's
movement baseline after the same-level chart rebase, in addition to sending
the destination correction and holding stale packets until the client
acknowledges the new position. Seam transfers and apron rebases are deferred
until vanilla finishes validating the triggering movement packet. The client
run also confirms that large stale deltas are not routed as seam steps.

This verifies the movement and packet-validation path only. It does not
establish frame-by-frame visual continuity, light or fluid parity, persistence
ownership, entity/block-entity handoff, directional block-state rotation, or
third-party renderer compatibility. Those remain separate acceptance items.

## 2026-10-17 seam-apron correction run

The first live fluid probe exposed a concrete routing bug. `ChunkProjection`
returns a target-face local cell, but the mutation router passed that pair
directly to `Level#setBlock`, so the write landed near the planar origin rather
than at the target face. The router now converts the projected local cell
through `CubeNetLayout.toPlanarUnbounded` before writing the canonical cell;
the block-state rotation is still applied through the seam transform.

Evidence after rebuilding the Fabric client and restarting the isolated server:

* Forward water: proxy cells at `(15360,100,-3..2)` reported levels
  `3,2,1,0,0,0`. After loading the target face, canonical cells
  `(10239..10236,100,5120)` reported `1,2,3,4` in the corresponding reversed
  order. Both sides reported sky light 14.
* Reverse water: the return fixture reported proxy levels
  `0,1,2,3,4,5,6`; after loading the proxy side, the target cells reported
  `3,4,5,6`. This confirms both seam directions write through and vanilla fluid
  propagation reaches the apron.
* A native client recording (`tellus-seam-spectator-fixed.mp4`) finalized at
  854x480, 30 fps, 650 frames, with no encoder error. The player crossed from
  `(15360.5,-29.5)` to `(10215.94,5120.47)` and the server logged one deferred
  apron rebase followed by one completed seam transfer. Sampled frames showed
  the guide strip before and after the transfer; this is evidence of loaded
  geometry and a connected session, not a proof of pixel-perfect frame
  continuity.

The same run also found a persistence gap: the canonical lantern/marker remains
after the proxy chunk unloads, but regenerating the derived proxy chunk reads
only the deterministic terrain sampler and restores the original terrain. The
derived-chunk rebuild must overlay canonical block edits (including fluids,
block entities, and light invalidation) before persistence acceptance can pass.

## 2026-10-18 runtime follow-up

The sparse edited-cell overlay and tested chest block-entity path now pass a
normal server-restart check in the isolated `server-11` world. A diamond in the
proxy chest `(15360,100,10)` was visible in its canonical owner
`(10229,100,5120)` before and after restart. Earlier online edits also passed
in both directions: proxy diamond to canonical, canonical emerald to proxy,
then proxy diamond back to canonical. This verifies the chest path only.

The first restart fix deferred proxy refresh from `CHUNK_LOAD` to the end of
the world tick and used a persistent sparse index to avoid scanning unedited
chunks. A later live return crossing found a separate queue-iteration crash
when refreshing neighboring chunks. Refresh retries now process a stable key
snapshot. The full `mc1201` test suite passed after that correction: 719 tests,
0 failures, 0 errors, 2 skipped. Repeated crossings on the patched runtime
then completed without another server exception.

After the queue fix, all four manual routes completed using the ordinary
Fabric client and the visual fixtures: cut and return while walking, and cut
and return while sprinting. The four final positions were approximately:

| Route | Final position | Yaw |
| --- | --- | --- |
| Walk cut | `(10215.95, 100, 5120.5)` | `90` |
| Walk return | `(15360.5, 100, -24.03)` | `-180` |
| Sprint cut | `(10215.59, 100, 5120.5)` | `90` |
| Sprint return | `(15360.5, 100, -24.22)` | `-180` |

The post-fix server log recorded four deferred apron rebases and four completed
seam transfers. At the end, `/tellus cube_net status` reported `pending
transfers: 0`, `sent=4`, `acknowledged=4`, `resent=0`, `expired=0`, and three
rejected stale movement packets. The log had no vanilla `moved too quickly`,
`moved wrongly`, or runtime exception entries for this run.

Native recordings finalized at 854x480 and 30 fps: `walk-cut-fixed.mp4`,
`walk-return-fixed.mp4`, `sprint-cut.mp4`, and `sprint-return.mp4`. Sparse frame
inspection showed the lime guide strip and no captured black/blank frames.
However, the fixture is a finite platform and each recording reports dropped
frames (9 to 20 on these four runs). That evidence does not establish
frame-by-frame continuity at the cut or rebase; the visual gate remains open.

The client profile contained Fabric API, Tellus, and the MC Pilot helper only;
it did not contain Distant Horizons, Sodium, or a shader pack. Third-party
renderer compatibility was therefore not exercised. General entity ownership
and block-entity types beyond the tested arrow, boat/passenger tree, local leash
bundle, chest, and sign remain open. The redstone neighbor fixture is now
covered in the live acceptance record below.

The 2026-10-18 moving-arrow probe adds a limited entity result: an unmounted,
un-leashed non-player entity staged in the native apron, waited for destination
chunks, then committed at canonical `(10215.25,100,5120.5)` with rotated heading.
A later query found it at `(10190.54,100,5120.5)` facing `-90°`, confirming
continued movement toward negative target X. This does not cover mounts,
player passengers, entity persistence, or interaction-sensitive types; the
separate leash fixture below covers only the local non-player leash bundle.

The companion `/function tellus_visualtest:entity_relationship_probe` spawned
a no-gravity boat at `(15360.5,101,24.5)` with a no-gravity armor stand as its
passenger. The server staged two members, committed the root at
`(10215.5,101,5120.5)`, and reported the armor stand at the same translated
position. A server NBT query still showed the armor stand in the boat's
`Passengers` list. This covers a non-player vehicle tree; any tree containing a
player was not covered by this live acceptance run.

The companion `/function tellus_visualtest:leash_probe` first creates two
no-AI, no-gravity cows outside the apron, establishes the leash UUID, and then
moves them into the POS_X apron after two ticks. In the clean run, the runtime
staged exactly two members and committed the target at `(10215.5,101,5120.5)`
and the holder at `(10209.5,101,5120.5)`. The target `Leash.UUID` matched the
holder UUID. After a clean server restart, both positions and the leash UUID
were unchanged and `/tellus cube_net status` reported `pending transfers: 0`.
This is server-side acceptance for a local non-player leash bundle; it does not
cover player passengers, distant leash endpoints, or client rendering.

## 2026-10-19 player-mounted transfer

The dedicated `/tellus cube_net mount_probe` command creates a boat and attaches
the player before the apron rebase. In a fresh `server-mount-check6` world, the
server staged a two-member bundle, sent the target 3x3 chunks to the client,
and completed the same-Level rebase at `(10215.5,101,5120.5)`. The client
position was `(10215.5,100.55,5120.5)` and the server `RootVehicle` query still
pointed to the boat at the target position after a 12-second wait. The final
status was `pending transfers: 0`.

The first packet-enabled run exposed two separate server/client issues: a stale
vehicle movement packet could restore the boat to the source coordinate, and a
second server rebase could start while the boat was still in the destination
apron. The runtime now keeps the mounted correction gate through a bounded
acknowledgement window, rate-limits vehicle resends, and adds destination-apron
hysteresis. Focused Java tests and a remapped jar build pass.

On the next live run, after explicitly dismounting the persisted probe from the
previous save, the server produced one `staged -> complete` pair for the
two-member boat/player bundle. The run then hit severe tick delay in the
force-loaded disposable world; the client timed out and the native recording
failed finalization. This is evidence that the duplicate-rebase symptom was
reduced in this setup, not a stability or visual pass.

The client cache center is moved before the target chunks are sent, so the
frame-by-frame visual gate remains open. The first finalized recording
(`mount-transfer-20261019.mp4`) showed a sea/sky-only handoff and the client
logged `Received passengers for unknown entity` plus chunks rejected as outside
the view range. The follow-up recording could not be finalized because the
server stalled. The next run must use a fresh, bounded world with no oversized
force-load region and inspect the handoff for sky-only frames, marker
disappearance, camera jumps, unknown-entity passenger packets, and a visible
cache-center gap.

At this point in the sequence, the mounted commit path was tightened to send an
explicit `ClientboundAddEntityPacket` for each non-player bundle member before
the vehicle teleport and passenger packets. The remapped jar and focused tests
passed; its live-client verification is recorded in the follow-up below.

The follow-up used the latest remapped jar in the bounded `server-mount-check6`
world. The probe placed the player and boat at `(15360.5,101,32.5)`, facing
north. **Correction on 2026-10-19:** positive Z at this X is already in the
POS_X apron, not in the face interior. This run did not cross the physical cut.
The server logged one two-member
`staged -> complete` transfer from approximately `(15360.5,31.97)` to
`(10208.03,5120.5)`. At the end of the 120-second native 854x480/30 fps
recording, the player was at `(10219.31,100.07,5120.5)`, yaw `-90`, still
mounted on the boat at the matching target coordinate. The server reported no
pending transfer and counters `sent=1, resent=19, acknowledged=1, rejected=196,
expired=1`. This is a mounted apron rebase and a server-side bundle stability
check for this run; it is not a full mounted cut traversal.

The latest client log contains neither `Received passengers for unknown
entity` nor chunks rejected as outside the view range. The recording
(`mount-crossing-real.mp4`) finalized with 3600 encoded frames at 30 fps; the
source capture counted 3598 frames, 15 duplicates and 13 drops. Its sampled
transition frames keep the red landmark visible, but the view changes
abruptly from the source-side sea/horizon to the enlarged green landing
platform over roughly 20–25 seconds into the recording. This does not meet the
pixel-continuity gate. Investigate the cache-center/rebase visual jump before
expanding the entity matrix.

## 2026-10-19 render staging investigation

Moving chunk staging and the server rebase into the same server tick was
tested and rejected. `rebase-cache-order-valid.mp4` still contains a sky-only
frame around 2.3 seconds. The test finalized at native 854x480/30 fps with
2216 encoded frames, 2214 captures, 11 duplicates and 9 drops. The server's
client-ready acknowledgement gate has been retained.

The corrected mounted probe starts at `(15360.5,101,-32.5)`, yaw 0, and drives
south across the physical cut at Z=0, then the rebase threshold at apron depth
24. The first render-staging attempt reached depth 24.26775 but timed out
without a ready acknowledgement; `render-stage-real-cut.mp4` records that
failed attempt, not a completed handoff. It finalized with 3857 encoded
frames, 3840 captures, 37 duplicates and 20 drops over 128.54 seconds.

An intermediate source-snapshot implementation also failed on login: the
snapshot was taken before any source chunks arrived, then the early cache-center
change rejected incoming source data. That run crashed in the client's light
calculation; it is not a rendering or stability pass. Its log is retained as
`server-mount-check7/render-staging-source-cache-crash.log`.

The ordinary renderer adapter now keeps the native source cache and its center
active, accepts the remote area into a separate bounded chunk map, and builds a
second native `ViewArea`. A 5x5 chunk data area supplies
the neighbor halo needed to compile its inner 3x3. Vanilla's remote rebuild
path rejects a section without its horizontal chunk neighbors, so sending
only 3x3 is insufficient to compile all nine remote chunks. The client checks
actual compiled meshes near the camera before acknowledging. This dimension is
4064 blocks high; compiling every underground section would be unnecessary for
the bounded view and could exceed the staging timeout. The vertical staging
slab extends one chunk beyond the configured view distance above and below the
camera. At commit the client promotes remote data through the ordinary chunk
packet handler, installs the staged view in the player-position handler, and
completes its first visibility update before drawing. Local boat propulsion is held while this staging barrier is
active. A single section compile is indivisible; the per-tick batch budget is
not a hard frame-time guarantee.

The first independent-cache run fixed the login-time transfer in this setup:
all eight logged post-commit frames had renderable terrain. Its mounted
follow-up compiled 63 sections and acknowledged in 1679 ms, but then failed.
The first mounted frame had 9 renderable sections; the next camera position
returned to the source boat, and the subsequent frames reported zero terrain.
The server disconnected the client for an invalid movement packet two seconds
after commit. `dual-cache-mounted-cut.mp4` is that failed 90-second recording
(2700 encoded frames, 2688 captures, 75 duplicates, 63 drops).

That trace isolates the mounted ordering problem: the player moved while its
retained, locally controlled boat still had source coordinates. Vanilla ignores
`TeleportEntity` for a locally controlled entity. The runtime now sends the
native `MoveVehicle` packet for the root before the player correction. The
client places and rotates its passengers, resets their interpolation history,
and installs the staged chart in the same vehicle-packet handler. The explicit
entity recreation remains only as a fallback for clients without the staging
channel; staged clients retain their original mount graph.

The first native-vehicle run (`native-vehicle-staged-cut.mp4`) still failed:
the committed player was on NEG_Z while its boat remained on POS_X, and the
second and following frames returned to zero visible sections. The finalized
65-second native 854x480 recording contains 1950 encoded frames, 1947 fresh
captures, 12 duplicates and 9 drops (29.954 fresh captures/s; the final renderer
estimate was 118 fps). This is failed evidence, not visual acceptance.

A diagnostic build isolated a server race. Both movement packet Mixins ran
at handler HEAD, before vanilla's `ensureRunningOnSameThread`. On the Netty
invocation they could call `holdEntity` concurrently with the main-thread
commit and restore the boat's source pose. The diagnostic server log records
the outgoing vehicle packet at source `(15360.5,101,25.2814)` even though the
transfer's target was `(10214.7186,101,5120.5)`; the client received that same
source pose. Both filters now run immediately AFTER the native thread check.
The off-thread invocation therefore schedules the packet and exits before
Tellus mutates any entity.

With that change, the corrected face-interior route completed at 19:31:07.
The native vehicle packet carried the target pose and the retained boat and
player committed together. All eight logged post-commit frames stayed in the
target chart with visible-section counts 8,8,8,8,8,13,13,14. The bounded run's
`server-thread-mounted-cut.mp4` finalized at 854x480/30 fps, 45 seconds, 1350
encoded frames, 1339 fresh captures, 30 duplicates and 19 drops (29.756 fresh
captures/s; final renderer estimate 118 fps). A 10 fps contact sheet over
seconds 9..16 shows the boat, runway and water throughout those samples.
This improves the observed mounted handoff but is not a per-frame continuity
pass. The server still reported one `moved too quickly` warning: vanilla's
`resetPosition` resets only the player's coordinates, leaving the six separate
vehicle first/last-good coordinates at the source until its next tick. A narrow
server-connection adapter now resets those vehicle baselines at commit before
the root correction is sent; its follow-up live result is recorded below.

The baseline adapter's follow-up used jar SHA-256
`3C1AAEC3262F9F4A69E8864187B820939B76DF3D875C8BB2B627DE54DB324652`.
The corrected mounted route staged at 19:38:12 and committed at 19:38:14;
the 19:38 handoff window contains no movement warning, disconnect or error.
Earlier warnings at 19:37:46..47 came from the explicit dismount/setup teleport
and are not counted as a warning-free whole session. All eight logged frames
contained terrain (9,9,12,14,15,15,15,15 visible sections). At 19:38:47,
the player's RootVehicle was still the same boat at
`(10212.89055,101,5120.5)`, with no pending transfer. However, counters showed
19 resends and 264 rejected packets, so this is not ordinary movement acceptance.

`baseline-landmarks-mounted-cut.mp4` finalized at native 854x480/30 fps,
45 seconds, 1350 encoded frames, 1343 fresh captures, 17 duplicates and 10
drops (29.844 fresh captures/s; final renderer estimate 117 fps). The new
`mounted_landmarks` function placed asymmetric red/yellow pillars at apron
depths 30/28; server predicates confirmed their canonical owners. The recording
file was created at 19:37:56, so the commit at 19:38:14 is around second 18.
The dense **17..21 second** sheet covers the actual commit; the 9..15 and
13..16 second sheets cover the approach and must not be called commit evidence.
That dense window retains water and landmarks but still shows camera/boat
jitter and brief underwater views. Full visual acceptance remains open.

The retained mounted correction gate compared every later packet with the
original destination using a two-block radius, even after acknowledgement.
That rejects legitimate movement away from the landing point and explains
the repeated correction traffic. After acknowledgement the vehicle filter now
compares with its current authoritative position, using the existing bounded
chart-jump guard; vanilla still validates ordinary movement. The player filter
also stops applying the fixed destination ball to an acknowledged passenger.
The final movement/visual follow-up is recorded below.

The final tested jar has SHA-256
`4364F8B20569284EE404BD8E9918FE489228338F73B534B018E1F0A6E3842B4A`.
Client compilation, remapping and all 733 MC 1.20.1 tests passed with zero
failures/errors. In the same bounded warm world, the corrected mounted route
completed twice with continued W input: first person at 19:59:07..08 and
third person at 20:01:40..41. Both continued from the rebase landing
`X=10215.73225` to `X=10140.93536`, a further 74.79689 blocks, while keeping
the same tagged root boat. The two-transfer status was `sent=2, resent=0,
acknowledged=2, rejected=130, expired=2`, with no pending transfer. Rejected
packets are still counted, so this is not a zero-rejection claim. Neither
handoff window contains a vanilla movement warning, error or disconnect.

| Final recording | Native size / output rate | Duration / encoded | Fresh captures / effective rate | Duplicates / drops | Final renderer estimate |
| --- | --- | --- | --- | --- | --- |
| `acknowledged-motion-valid-cut.mp4` (first person) | 854x480 / 30 fps | 40 s / 1200 | 1191 / 29.775 fps | 23 / 14 | 119 fps |
| `acknowledged-motion-third-person.mp4` | 854x480 / 30 fps | 35 s / 1050 | 1044 / 29.829 fps | 16 / 10 | 118 fps |

Both recordings finalized successfully with no encoder error. Their dense
15..19 second sheets cover the actual commits (the first file was created at
19:58:50.121; the third-person file at 20:01:22.925). Water and the asymmetric
pillars remain present in these windows, and the earlier repeated large
camera/boat oscillations are absent in the inspected final windows. All eight
traced frames contain terrain; third-person visible-section counts are
9,9,12,16,16,18,18,18. A later native-resolution review corrected the initial
claim that the local-player model disappeared: the player remains visible at
16, 18, 20 and 30 seconds. The fixed coverage still prevents complete visual
acceptance. A startup
attempt named `acknowledged-motion-mounted-cut.mp4` began before the client
was in-world and never ran the mount setup; it is excluded from crossing
evidence.

The remaining order from that recording is superseded by the configured-view
follow-up below:

1. Completed evidence correction: native-resolution frames retain the mounted
   player; no player-registration/render patch is justified by this recording.
2. Make target mesh coverage and source-apron coverage match the actual view
   radius, including the depth already walked into the apron and the neighbor
   halo. The current radius-2 data area compiles only radius-1 meshes, and a
   32-block apron leaves just 8 blocks ahead at the depth-24 handoff. These are
   bounded probes, not full-view seamless coverage.
3. Repeat return, walking/sprinting and cold-load visual routes, then address
   shading/orientation and renderer compatibility. Keep additional entity and
   block-entity families deferred until these visual results are recorded.

Final logs are retained in `server-mount-check7/motion-final-client.log` and
`motion-final-server.log`, with frame sheets and recorder metadata beside them.
The test client stopped and the interactive server shut down normally with
all dimensions saved. These changes remain local RFC work, not a mainline or
production acceptance claim.

The long `visual_setup` fills require all their chunks to be loaded. A function
reporting its command count does not establish that each fill succeeded. Warm
the complete source rectangle X=15344..15391, Z=-256..63 and target rectangle
X=10000..10271, Z=5088..5151 before calling it, then verify representative guide
and marker blocks on the server. These rectangles cover 128 chunks in total,
including overlap with the smaller tickets above; this is a warm fixture test.

## 2026-10-18 sign block entity

The `/function tellus_visualtest:sign_probe` fixture placed an oak sign at
proxy `(15360,100,12)`. Once the target chunk was loaded, its initial
`front_text` payload appeared at canonical `(10227,100,5120)` with the four
lines `Proxy seam sign`, `Tellus`, `2026-10-18`, and `POS_X`. Editing the
canonical sign to `Canonical sign`, `Tellus`, `2026-10-18`, and `NEG_Z` then
returned the same payload to the proxy. This is a bidirectional live check of
the generic block-entity payload hook for signs; it does not establish
unloaded-target persistence or coverage for every block-entity type.

## 2026-10-18 extended visual route

The visual fixture was lengthened after the first 100-block cut route fell from
the end of the short canonical runway. The fixture now reaches to `X=10000` on
the landing face and to `Z=-256` on the source approach. With the revised
fixture, 160-block walks completed after both `/function
tellus_visualtest:visual_cut` and `/function tellus_visualtest:visual_return`.
Final positions were approximately `(10155.91,100,5120.5)` and
`(15360.5,100,-84.33)`, respectively.

The native 854x480 recordings are `visual-continuity-cut.mp4` and
`visual-continuity-return.mp4`. Inspection at 5, 15, 25, 35, 50, 65, and 80
seconds showed the surface and lime guide in those sampled frames. A full
encoded-frame scan counted 2525 and 2496 frames at 30 fps, found no all-black or
near-black frame, and found no encoded timestamp gap over 50 ms. The transition
grids around frame 1016 on cut and frame 1012 on return nevertheless show a
short sky-only interval around the rebase before the runway returns. The cut
recording reported 721 dropped and 817 duplicate source frames; return reported
484 dropped and 628 duplicate frames. The visual gate remains open because the
recorded path has a visible rendered gap and the source capture does not prove
pixel-continuous crossing.

The entity probe is available as `/function
tellus_visualtest:entity_probe`; it replaces any prior tagged probe with a
moving arrow at apron depth 24. After the run, query its `Pos`, `Rotation`, and
`Motion` NBT from the server console. In this run the server logged a committed
position of `(10215.25,100,5120.5)`. A later NBT query found the arrow at
`(10190.54,100,5120.5)` with a `-90°` heading and near-zero velocity after
continued flight.

## 2026-10-18 redstone neighbor updates

The neighbor probe is available as `/function
tellus_visualtest:neighbor_setup`, followed by `/function
tellus_visualtest:neighbor_trigger`. The fixture is a redstone source at proxy
`(15360,100,21)`, three wire cells at proxy `Z=22..24`, and a lamp at proxy
`(15360,100,25)`. Their canonical owners run along negative X at `Z=5120`.
The line spans the player rebase threshold inside the target-face apron; it
does not span the face boundary at `Z=0`.

Server-side block predicates verified the lamp at canonical
`(10214,100,5120)` and the proxy lamp both lit. Wire at proxy `Z=23` and its
canonical owner `(10216,100,5120)` both had power `14`; wire at proxy `Z=24`
and its owner `(10215,100,5120)` both had power `13`. Removing the proxy
source turned both lamps off and reset the last proxy/owner wire pair to
power `0`. After a server restart, a canonical source write at
`(10218,100,5120)` also lit both lamps and set the last wire pair to power
`13`. That reverse run verifies owner-driven updates reaching the loaded
proxy; it is not a persistence check for the powered state.

Client `block get` initially returned `void_air` for the distant canonical
positions because those chunks were not in the client's current view. Those
responses are not server-state evidence. The face resolver independently maps
the lamp center to `(10214.5,5120.5)`; server-side `execute if block` predicates
were used for the acceptance result instead. This fixture does not establish
coverage for all scheduled ticks, piston movement, or block-state families.

The first server run shut down normally and saved its chunks. The reverse
probe's server had been launched without an interactive console; after the
client stopped, that test server required process termination. No state from
that forced shutdown is used as restart/persistence evidence.

## 2026-10-19 configured-view coverage

Native-resolution review corrected the preceding model-disappearance claim;
no entity registration or player renderer change was needed. Native frames
from the original recording are retained as `server-mount-check7/third-native-*`.

The runtime now captures the client's ordinary advertised render distance,
caps it by the server distance and stages that radius plus one neighbor chunk.
The radius is retained through async loading, native chunk packets and ticket
cleanup. The client prepares every vertical section in the requested horizontal
view, rather than only the former seven-section camera slab. The source apron
must cover the view/simulation radius, threshold chunk and neighbor halo;
undersized configurations refuse to begin staged handoff and report the needed
width. `create_cube_net_datapack.py --apron-chunks` configures new packs. The
recorded run below used a default of 2 and maximum 32. The review correction
now uses a default of 5 and supports widths through 35 chunks (view/simulation
distance 32); existing measurement results below remain tied to their recorded
configuration. Legacy clients and non-player probes retain their previous
bounded preload.

Jar SHA-256:
`4DD9FF6F9CF3A500E04DAD37102F967383E1ACB45262F94FEDCBEA24F9A51FEC`.
Compilation, client compilation, remapping and the MC 1.20.1 suite succeeded:
736 tests discovered, 0 failures, 0 errors, 2 skipped. This is the final rerun
after correcting an erroneous far-edge expression in the new coverage test;
the initial run had that one test failure.

The isolated `server-view-check1` world uses scale 1000, sea Y=100, minY=80,
height=64, apron=8 chunks, server view=6 and simulation=4. This deliberately
bounded height tests full vertical coverage in that dimension; it does not
measure the original 4064-block dimension's cost. Source/target fixture chunks
were forced loaded, and server predicates verified the lime guide and canonical
red landmark. These are warm synthetic-terrain routes.

| Client view / actual camera | Target data / compiled sections | Ready elapsed | Commit time | Continued distance after landing |
| --- | --- | --- | --- | --- |
| 2 / third person | 7x7 / 25 columns x 4 sections = 100 | 2448 ms | 23:25:21 | 120.00 blocks |
| 4 / first person | 11x11 / 81 columns x 4 sections = 324 | 7367 ms | 23:31:34 | 80.40 blocks |

The 4-distance recording was named `view-coverage-4-third-person.mp4`, but the
client restart restored first person. The camera trace and video confirm first
person; the filename does not establish the camera mode. Both routes began at
`(15360.5,101,-32.5)` aboard the probe boat and crossed the physical cut before
the depth-24 rebase. The first eight committed frames had 19 and 41 visible
sections respectively, with no empty terrain frame in those traces. The
inspected frame sheets and native 2-distance frames retain the water, landmarks,
boat and mounted player. The 4-distance sheet retains terrain and landmarks.
Neither inspected handoff window contains a movement warning or disconnect.
Two-transfer status was `sent=2, resent=0, acknowledged=2, rejected=398,
expired=2`, with no pending transfer; rejected source packets during preparation
are still counted.

| Recording (both 854x480 H.264, silent, 30 fps output) | Duration / encoded | Fresh frames / capture fps | Duplicated / dropped | End renderer estimate |
| --- | --- | --- | --- | --- |
| `view-coverage-2-third-person.mp4` | 35 s / 1050 | 1050 / 30.000 | 9 / 9 | 117 fps |
| `view-coverage-4-third-person.mp4` (actual first person) | 35 s / 1050 | 1049 / 29.971 | 6 / 5 | 117 fps |

Both recorders finalized without encoder error. Evidence is retained under
`server-view-check1`: `view2-client.log`, `view4-client.log`,
`coverage-final-server.log`, `coverage-events.json`, recorder metadata, native
screenshots and commit sheets. Videos remain in the isolated client's
`minecraft/recordings` directory. The client stopped, its render distance was
restored to 2, and the interactive server saved all dimensions and stopped.

This closes the fixed-radius coverage defect for these measured configurations,
but **does not close smooth traversal acceptance**. Local dependency inspection
confirms vanilla already checks for empty regions; adding an ad hoc empty-section
bypass is not justified by these measurements. The next ordered work is:

1. Repeat the landmark route in both directions, walking/sprinting and cold
   loading, including dense frames across the actual commit.
2. Address remaining shading/orientation and renderer combinations, then expand
   entity/block-entity families according to the reuse audit.

## 2026-10-20 asynchronous full-height preparation

The first full-height attempt used the old render-thread `compileSync` loop. It
compiled 6,350 sections but timed out at 60 seconds; the finalized recording is
`server-approach-check1/revised-fullheight-view2.mp4` and is retained as a
negative performance result. Its screenshot shows the source-side boat still
waiting, so it is not a traversal pass.

The client now keeps the vanilla rebuild workers active, limits the in-flight
queue to 64 sections, reuses one `RenderRegionCache`, and supplies the target
chart's camera to worker compilation. It still requires every section in the
requested horizontal view and does not hide empty sections with an ad hoc
shortcut. The build with jar SHA-256
`FD3C50677FB441315DDABDCAF1DA025AA6783A5D3F770B39F98E4AF8A5F84325` passed
738 tests (0 failures, 0 errors, 2 skipped).

The live fixture was the original 4064-block-height synthetic world
(minY=-2032, height=4064, scale=1000, apron=8, server view=6, simulation=4).
The source and target tickets were warm. Both mounted routes began at
`(15360.5,101,-128.5)` and reached the same target `(10215.7,101,5120.5)`.

| Client view | Sections | Data/light wait | Worker compile wall time | Total ready | Continued distance |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2 | 6,350 | 854 ms | 5,747 ms | 6,602 ms | 127.0 blocks |
| 4 | 20,574 | 2,212 ms | 18,547 ms | 20,762 ms | 406.8 blocks |

The view-2 recording `async-fullheight-view2.mp4` finalized at 60 seconds,
854x480 H.264/30 fps, with 1,795 captured and 1,800 encoded frames, 20
duplicates, 15 drops, and no encoder error. The view-4 recording
`async-fullheight-view4b.mp4` finalized at 116.97 seconds, with 3,462 captured
and 3,509 encoded frames, 90 duplicates, 43 drops, and no encoder error. Native
contact sheets around the commit retain the boat, player, water and asymmetric
landmarks. Server logs show zero correction resends and completed two-member
vehicle transfers. These are warm synthetic routes; they do not close cold-load
or reverse traversal acceptance.

This result closes the previously observed full-height preparation timeout for
the measured distances. It does not make the route seamless by itself: the
boat is still held at the actual commit until preparation finishes, and the
20.8-second view-4 preparation only succeeds because the probe starts 128 blocks
from the cut. The next ordered gate is return traversal and cold loading, then
walking/sprinting and renderer combinations.

## 2026-10-20 reverse mounted route

The target-side boat was placed at `(10111.5,100,5120.5)` with its forward
heading toward the reverse cut. A fresh view-2 preparation staged 6,350 sections
in 5.786 seconds, committed at source-side `(15360.5,101,-24.3)`, and kept the
two-member boat bundle together. The route then continued to
`(15360.5,100,-147.86)`, about 123.6 blocks past the landing point. Server logs
reported a completed transfer with no correction resend; the client log contains
eight committed terrain frames immediately after the rebase.

`return-fullheight-view2b.mp4` finalized at 50.16 seconds, 854x480 H.264/30 fps,
with 1,498 captured and 1,505 encoded frames, 20 duplicates, 13 drops, and no
encoder error. The route used warm source/target tickets and synthetic terrain.
It is a reverse-direction mounted result, not cold-load or walking/sprinting
acceptance. The earlier exploratory attempt is retained as
`return2-failed.png` and `return2-failed-client.log`; it moved away from the
directed cut because the boat heading was reversed, so it is excluded from the
pass result.

## 2026-10-20 cold reload and elevated movement routes

The cold run first removed all forced tickets and verified both the source and
target positions with `unless loaded` predicates. This is a reload of saved
synthetic chunks, not a first-generation terrain benchmark. From
`(15360.5,101,-128.5)`, view distance 2 staged 6,350 sections. Server load took
6,376 ms and the client reported ready at 13,268 ms (data/light 6,572 ms,
worker compile wall 6,694 ms, 117 batches). The seam committed at source Z=24.3
and the player continued on the target face to X=9,972.9, about 242.8 blocks
after landing. `cold-fullheight-view2.mp4` finalized at 85.63 s, 854x480 H.264
at 30 fps output, with 2,569 encoded frames, 2,552 fresh captures (29.805 fps),
38 duplicates, 21 drops, renderer estimate 118 fps, and no encoder error. Dense
video samples retain the boat, player and colored landmarks; no sampled frame
was all black.

The four movement routes were then repeated with the player on a smooth-stone
runway at Y=101 (player Y=102, `onGround=true`) so water movement could not
obscure the result. Each route began 29.5 blocks from the cut, staged 6,350
sections, committed at depth 24, and continued after landing.

The same setup is retained in `tools/fixtures/cube-net-visuals` as
`/function tellus_visualtest:elevated_cut` and
`/function tellus_visualtest:elevated_return`. Copy that pack into the disposable
world and reload before invoking either function. Hold W to walk, or Ctrl+W to
sprint; verify Y=102, `onGround=true` and travelled distance. The setup functions
do not preload chunks and require their fixture areas already loaded.

| Route | Client ready | Landing | Post-landing distance | Recording |
| --- | ---: | --- | ---: | --- |
| Walk, source → target | 5,237 ms | X=10,215.8 | 53.6 blocks | `walk-elevated-cut.mp4` |
| Walk, target → source | 5,350 ms | Z=-24.2 | 53.6 blocks | `walk-elevated-return.mp4` |
| Sprint, source → target | 5,347 ms | X=10,216.0 | 85.7 blocks | `sprint-elevated-cut.mp4` |
| Sprint, target → source | 5,454 ms | Z=-24.0 | 85.9 blocks | `sprint-elevated-return.mp4` |

Each input lasted about 25 seconds. The total travelled distance was about 108
blocks walking and 140 blocks sprinting; sprint is established by the measured
distance as well as the held Ctrl key. All endpoints remained at Y=102 with
`onGround=true`. The earlier sea-level walk/reverse attempt is retained as
exploration, with `wet-runway.png`; it is not the sprint acceptance result.

| Recording (854x480 H.264, silent, 30 fps output) | MP4 duration / encoded | Fresh frames / capture fps | Duplicate / dropped | Renderer estimate |
| --- | --- | --- | --- | --- |
| `walk-elevated-cut.mp4` | 44.97 s / 1,349 | 1,340 / 29.816 | 22 / 13 | 119 fps |
| `walk-elevated-return.mp4` | 30.57 s / 917 | 911 / 29.831 | 19 / 13 | 118 fps |
| `sprint-elevated-cut.mp4` | 31.97 s / 959 | 954 / 29.852 | 18 / 13 | 118 fps |
| `sprint-elevated-return.mp4` | 31.67 s / 950 | 946 / 29.888 | 18 / 14 | 118 fps |

All four recordings finalized without encoder error. The inspected dense
commit sheets retain the runway, hand and guide; forward landmarks move past
the camera rather than disappearing before the crossing. Three deliberate
setup teleports each produced four `moved too quickly` warnings, with deltas
of 48 or 80 blocks. No such warning occurred in the actual seam-crossing
intervals. The first eight traced post-commit frames contain terrain.

Evidence is retained in `server-approach-check1`: `cold2-client.log`,
`cold2-record-stop.json`, `cold2-dense-sheet.png`, `elevated-movement-client.log`,
`elevated-movement-server.log`, `elevated-movement-events.json`, and the four
`*-elevated-*-record-stop.json`, `*-position.json` and `*-commit.png` files.
Videos remain in the isolated client's `minecraft/recordings` directory.

This closes the measured cold reload and elevated walking/sprinting routes for
this synthetic fixture. It does not close zero-hold pixel continuity,
first-generation terrain creation, or Sodium/Distant Horizons/other renderer
combinations. The next work is commit-time continuity and shading/orientation
review, followed by the renderer matrix before expanding entity families.

### Dense encoded-frame review

A complete scan of these five MP4 files decoded 6,744 frames. None had at
least 90% of pixels below grayscale 10. This is a black-frame check only;
it cannot establish fresh source frames because the recorder is CFR and
reports duplicate and dropped captures.

Native frame review found a visible geometry discontinuity at the reverse
handoff: frame 494 → 495 (16.467 → 16.500 s) of
`walk-elevated-return.mp4`, and frame 391 → 392 (13.033 → 13.067 s) of
`sprint-elevated-return.mp4`, abruptly replace the water ahead with a longer
runway. The runway exists both before and after commit; its visible extent
changes. Forward frames 518 → 519 (17.267 → 17.300 s) and 409 → 410
(13.633 → 13.667 s) also have large pixel changes around the landmarks.
The mean absolute BGR differences at 427x240 are 17.58/7.41 for walk
cut/return and 16.73/6.35 for sprint cut/return. These metrics identify review
windows; they are not pass thresholds for a moving camera.

The review therefore leaves the visual handoff gate **failed**, rather than
merely unmeasured. Check apron edit overlays and source/target visible
coverage before expanding renderer or entity support. Evidence includes
`frame-continuity-scan.json`, the four `*-handoff-native.png` contact sheets,
and their individual native frames under `server-approach-check1`.

The committed walking transitions are `57d2a97a` (5,237 ms) and `76700dd3`
(5,350 ms). An earlier forward preparation, `21b1d500` (5,226 ms), expired
before movement and must not be assigned to a completed route.

### Saved-edit apron reload repair

An unchanged-save restart reproduced an actual missing apron edit, beyond the
rendered extent difference above. Owner cells at `(15360,101,-1)`, `-9` and
`-16` were lime concrete, while the mapped apron cells at
`(10240,101,5120)`, `10248` and `10255` were not. Farther apron cells from
`10256` onward were lime. `overlay-warm-return.mp4` records the consequence:
the player falls from Y=102 to Y=100 and stops at X=10255.7 against the next
restored strip, without completing a seam transfer. This is failed reload
evidence, not an accepted return route. No blocks were re-placed in this probe.

`CubeNetApronChunkSync` had two missing lifecycle steps. Its retry loop dropped
an entry when `getChunkNow` still returned no proxy, and it never requested
the distant canonical owner. The first retry-only repair exposed owner-wait
timeouts instead of silently losing entries. The final repair requests each
edited owner asynchronously under a temporary region ticket, retries for a
bounded 1,200 ticks, and releases its ticket on success, unload or timeout.
Failed overlays continue to block packet staging. `CubeNetRuntime` now waits
for deferred overlays in the destination area before sending its chunk set.
Untouched natural terrain does not enter this saved-edit path.

The final jar SHA-256 is
`1B68D73B66CE5399E1F22F45C4B618D9A9A1C6A0E3FE3647B5E734A4B398C042`.
Client compilation, all 741 test cases (0 failures/errors, 2 existing skips),
and remapping passed. Three new regression cases cover delayed readiness,
reentrant queuing and one-time timeout reporting with staging kept blocked.

The final unchanged-save restart restored every one of the ten paired probes
from owner Z=-1 through -65 to apron X=10240 through 10304. Logs now include
the formerly missing `[640,320]` overlay (288 blocks), followed by the other
edited chunks, with no overlay timeout in this run. The player was repositioned
at Y=102 after joining; the test did not rebuild the runway or force-load it.
The return walk committed transition `2303891d` after 7,299 ms of client
staging and ended at `(15360.5,102,-77.4348)`, `onGround=true`, about 53.23
blocks past landing. All eight traced post-commit frames contain terrain.

`overlay-fixed-return.mp4` is 31.73 s, 854x480 H.264/30 fps, with 952 encoded
frames, 947 fresh captures (29.848 fps), 19 duplicates, 14 drops, renderer
estimate 119 fps, and no encoder error. Its SHA-256 is
`5376018E7F43FFCBBA9E5E802F736F100D2AB7580B9682521564009A683825E7`.
All 952 encoded frames were scanned with zero near-black frames. Native frames
486–490 retain the long runway across the handoff; the previous abrupt
water-to-runway replacement is absent in this route. Maximum inter-frame
difference near the handoff is 5.18, compared with 7.41 in the earlier return
walk. The camera/hand still change across these frames, and recorder drops
remain; this does not establish zero-hold or pixel equivalence.

Evidence under `server-approach-check1` includes `overlay-block-probe-events.json`,
`overlay-before-fix-server.log`, `overlay-retry-only-server.log`,
`overlay-fixed-server.log`, `overlay-fixed-client.log`, and
`overlay-fixed-return-{record-stop,position,scan}.json`, plus native peak and
timeline sheets. Repeat forward, sprint and mounted routes on this repaired
jar before reassessing the overall visual gate, then address shading/orientation,
first-generation loading and the renderer matrix.


## 2026-10-20 repaired route matrix and pause lifecycle

These five additional routes used the saved-edit overlay/owner-load repair
`1B68D73B66CE5399E1F22F45C4B618D9A9A1C6A0E3FE3647B5E734A4B398C042`.
The earlier `overlay-fixed-return.mp4` supplies the repaired reverse-walk case.
All five new recordings finalized without a recorder error; their scans found
no near-black frames. The mounted server traces rebased two members and the
client retained the player/boat relationship.

| Route | Final position | Encoded / captured frames | Captured fps |
| --- | --- | --- | --- |
| forward walk | `(10174.4677, 102, 5120.5)`, yaw 90, on ground | 916 / 909 | 29.782 |
| forward sprint | `(10129.9235, 102, 5120.5)`, yaw 90, on ground | 977 / 971 | 29.830 |
| reverse sprint | `(15360.5, 102, -110.3571)`, yaw -180, on ground | 983 / 977 | 29.836 |
| mounted forward | player `(10012.1032, 120.0509, 5100.5)`, yaw 90 | 1470 / 1461 | 29.822 |
| mounted reverse | player `(15340.5, 100.55, -187.8968)`, yaw -180 | 1316 / 1308 | 29.838 |

### Negative pause case

The server preparation previously expired 1200 ticks after starting, including
already-ready charts. The server-only fix retained successful preparation and
its ticket. Its jar was
`C785A36E96EE61BCFEA5501334A960C0293AA7F7A287E005AE2B6873A46ED5AA`.
However, the corresponding client watchdog still discarded a ready chart. The
client log shows preparation ready at 13:12:50, timeout at 13:13:42, and renewed
preparation when the server transferred at 13:16:18. Movement completed on the
runway, but recorded frames 4672-4679 contained sky and the hand without terrain
(8 frames / about 0.267 seconds). This is a visual failure, even though the
5105-frame scan reports zero near-black frames. Do not mark it as accepted.

### Both-side ready retention and live regression

`CubeNetClientTransition.tick` now returns after validating the current world,
cache and view distance when its ready acknowledgement has been sent, before
spending the loading timeout. Explicit cancellation, world/cache/view changes,
disconnection and ownership transfer still release the staged view. Server-side
unfinished/failed preparation still expires; ready preparation keeps its region
ticket while the player remains on the same approach.

The final jar is
`2BEEA75920715D841E2AF52ABF64B74A4A0E3A76AFDE062AC3CB58D3B444BC15`.
Client compilation and remapping succeeded. The Java suite reports 744 tests,
0 failures, 0 errors and 2 existing skips. The final client fix is additionally
covered by the real-client regression below; it is not covered by the server-only
unit tests.

The same transition `1fb33a7e-2e42-48bb-881f-a28b4152e1be` became ready at
13:33:07 and committed at 13:35:25 (138 seconds later), after more than a minute
of stationary waiting. It did not time out or restart. The client committed all
6350 prepared sections; its first eight traced rendered frames retained 17
visible terrain sections. The player continued to `(10162.7810, 102, 5120.5)`,
yaw 90, on ground.

`pause-ready-fixed-20261020.mp4` finalized with 4013 encoded / 4007 captured
frames, 21 duplicates, 15 drops, 29.962 captured fps and no recorder error.
Its SHA-256 is
`BA46312EBC3F24A62893EB0B5E96BCE9CB92C3B0F843E8B4428C8E12746332D0`.
The scan found no near-black frames. A fixture-specific lower-left terrain
check found 8 sky-only frames in the negative handoff window and 0 in the fixed
window; `pause-terrain-comparison.json` records the region and thresholds.
Native contact-sheet review around frame 3527 retains the runway and both marker columns through the commit, with no
repeat of the previous sky-only interval. Hand/camera changes remain visible;
recording drops also limit the claim to captured frames. This repairs the pause
lifecycle gap; exact seamless visual acceptance remains open.

Recording hashes, scan paths, build provenance and copied client/server logs
are indexed in `server-approach-check1/repaired-build-route-summary.json`.

### Short-pulse commit window

To reduce camera travel through the rebase, a separate live route started at
`(15360.5, 102, 23.98)`, yaw 0, on the runway. After the destination chart was
ready, a 100 ms forward input (actual hold 135 ms) carried the player only to
apron depth `24.078`; transition `8edc6d24-2d47-4f62-90dc-a8cc98a02f20`
committed at `(10215.922, 102, 5120.5)`, yaw 90. The client staged 6,350
sections in 5.719 s. This isolates the commit to roughly 0.1 block of player
motion, but it is not a stationary-camera capture.

`server-approach-check1/one-tick-handoff-20261020.mp4` is a finalized 854x480,
30 fps, 6.30 s recording (190 encoded frames; 186 fresh captures at 29.516 fps,
12 duplicates, 8 drops, no recorder error). SHA-256:
`EF40EE3CA33A37E82DCBE9DAED7A07D372826B843CEBBBD3CBD4C4AF86A25E3A`.
The full-frame scan found zero near-black frames. Its highest measured adjacent
frame difference is 7.77 at frame 82 / 2.733 s (terrain region 5.06). Native
review shows the runway and marker columns still present around the commit; the
brief yellow stripe movement follows the player's forward motion, and there is
no obvious full-view jump or blank interval in this bounded pass.

This is evidence against a gross visual discontinuity for this warmed,
synthetic runway route. Recorder duplicates/drops mean some source frames were
not captured, and player motion remains. Therefore it does not prove exact
pixel equivalence or close the overall visual gate. Scan, peak sheet, summarized
metadata, filtered client events and the captured server-console excerpt are
retained beside the MP4 under `server-approach-check1/`.

### Paired stills at the same cut

An additional same-run comparison captured the source view at
`(15360.5, 102, 23.98)`, yaw 0, then triggered the live commit with one short
forward input (actual 183 ms). The server committed at depth `24.078` to
`(10215.922, 102, 5120.5)`, yaw 90. The client had prepared 6,350 sections in
8.043 s. Both screenshots are 854x480 and retain the runway centerline,
cross-stripe, and marker columns in nearly the same screen positions.

The comparison also exposes a repeatable lighting discontinuity: on the yellow
marker, representative left/right side-face crop averages change from RGB
`(189,137,17)` / `(141,103,12)` before the commit to `(142,103,12)` /
`(189,137,17)` afterward. The brighter and darker sides exchange positions
while the silhouette and camera composition remain aligned. The red marker
shows a corresponding side-intensity change. This is a visible seam defect;
the overall visual acceptance gate remains **failed** even though this pair has
no missing runway or gross geometry jump. The observation is consistent with
directional block shading changing with the chart axes, but the exact rendering
cause is confirmed by the local 1.20.1 client renderer implementation below.

The local Minecraft 1.20.1 client bytecode confirms the cause. `ClientLevel`
returns shade factors `0.8` and `0.6` for the two horizontal direction pairs,
and `ModelBlockRenderer` requests this factor using each quad's `Direction`.
Their ratio is `0.8 / 0.6 = 1.333`, close to the measured marker ratio
`189 / 141 = 1.340`; the before/after capture shows those bright and dark sides
exchange across the quarter-turn. This supports vanilla direction-based face
shading as the source of this observed defect. It does not establish that all
lighting channels or block models are seam-continuous.

### Live movement trace across the cut

The existing flat synthetic runway was used for a real client/server trace.
The client held W for 12,026 ms continuously, with player Y=102 over the level
Y=101 runway and grounded before movement. The source position was mapped back
from the target chart by the inverse 90-degree seam rotation. The trace records
authoritative server positions, position-derived velocity, `deltaMovement`,
ground state and the commit tick in
`server-approach-check1/motion-continuity-20261020.json`.

| Window | Server ticks | Mapped Z range | Average displacement speed |
| --- | ---: | ---: | ---: |
| Moving on source face | 11348–11355 | 22.6281 → 24.1391 | 4.317 blocks/s |
| After movement resumes on target face | 11361–11369 | 24.4358 → 26.0975 | 4.154 blocks/s |

The target-face window is 3.8% slower than the source window. W remained held,
yet mapped position stayed fixed for three complete server ticks immediately
after commit (11358–11360). The same-tick commit position maps 0.0196 blocks
backward from the preceding source sample. After resuming, individual tick
samples alternate between zero displacement and larger steps; averaging across
several ticks gives a more useful speed comparison. Server `deltaMovement` had
zero horizontal components while the player was moving, so the measured
position differences provide the meaningful speed signal for this route.

This confirms a short movement hold across the handoff; the data does not show
a sustained speed change after movement resumes. It is one warmed, synthetic,
walking route in one direction. Sprinting, the return seam, cold loading and
other surfaces still need separate traces. Movement measurements also do not
resolve the independently confirmed shading discontinuity or the remaining
visual gates.

The initial `/tp` used only to stage the source-side test position emitted a
server `moved too quickly` warning; that setup warning is separate from the
short-pulse crossing. `handoff-pair-summary-20261020.json` records the two
screenshot hashes, positions, client/server event excerpts and the staging
telemetry. The stills are `handoff-pair-before-20261020.png`,
`handoff-pair-after-20261020.png`, and `handoff-pair-20261020.png` in the same
evidence directory.

### Directional-shading repair and clean bidirectional rerun (2026-10-24)

The confirmed bright/dark side exchange was repaired at the chart boundary.
The server now sends the seam's `targetToSourceClockwiseQuarterTurns` in
`cube_net_transition_prepare_v3` and `cube_net_transition_commit_v2`. During
staging, a `ModelBlockRenderer` worker context identifies the target block
position and `ClientLevel.getShade` samples the vanilla factor after applying
the pending target-to-source direction mapping. The same rotation is promoted
when the staged view is committed. A cancelled transfer keeps the current
chart; disconnect resets the chart orientation.

The isolated RFC build passed `compileJava`, `compileClientJava`, the full
`-PtellusTargets=mc1201 :mc1201:test` suite, the payload round-trip test and
the apron rotation contract test. The focused live run used the disposable
offline Fabric server and the real `tellus-1.20.1` client. A clean forward
rebase committed transition `f65fcf5b-5b19-4681-b8e2-65fd3dc8ea51`, changing
the client shading state from `0` to `3`. A clean reverse rebase committed
transition `a5b0b67b-44a3-4463-817b-f22ce21284ff`, changing it from `3` back
to `0`. The reverse movement helper continued beyond the seam after the
commit and ended at Y=64.76, so that reverse run is evidence for protocol and
orientation state recovery, not a clean runway geometry capture. The client
log, server excerpt, JSON summary and three screenshots are
under `server-live-rerun-20261023/visual-shading-fix-20261024*` and
`shade-fix-clean-*.png`.

This closes the implementation and focused runtime check for the directional
shading defect. It does not yet close the complete visual gate: the run used a
synthetic runway, no external renderer or shader stack, and the screenshots
are not a frame-by-frame pixel continuity measurement. The next ordered gate
is the renderer matrix, followed by entity/block-entity visual checks.

### Ordinary renderer frame gate (2026-10-24)

The available client has Fabric API's Indigo renderer and no Sodium, Distant
Horizons, or shader stack. A fresh server/client run used the ordinary
Fabric/Indigo path with the repaired jar. From the source approach at
`(15360.5, 100, -5.5)`, a real W hold lasted 12.107 seconds and crossed to the
target chart near `(10215.94, 100, 5117.22)`. The client committed transition
`264b8446-367d-4090-9770-d380382dea92` with `shadingTurns=3`, `previous=0`.

The native framebuffer recording is
`server-live-rerun-20261023/ordinary-renderer-framegate-20261024b.mp4`:
854x480, 30 fps, 15.133 seconds, 454 encoded frames, 448 fresh samples,
14 CFR duplicates and 8 recorder drops. The frame scan found no near-black
frame using the recorded threshold (at least 75% of pixels below luma 12 or
at least 50% below luma 4). The maximum adjacent-frame mean absolute
difference was 2.184, with a 95th percentile of 1.331. The source marker
and runway remained visible in the captured approach; no blank or terrain-free
interval was observed in the reviewed transition window.

This closes the ordinary-renderer no-blank-frame gate for this warmed,
synthetic forward route. Recorder drops and player motion mean it is not an
exact pixel-equivalence result. Sodium, Distant Horizons, shader combinations,
the clean reverse geometry route, and entity/block-entity visual checks remain
open. The machine currently has no external renderer jars installed, so those
matrix cells are untested rather than passed.

### Clean reverse runway frame gate (2026-10-24)

The reverse route was rerun from a fixed target-side runway position
`(10215.5, 100, 5120.5)`, yaw `-90`, on ground. Direct teleport was used only
to stage that start position; the seam itself was crossed with a real 12.013
second W hold. The player reached `(15360.5, 100, -26.4541)`, yaw `-180`,
still on ground. Transition
`cfae176d-49c2-485d-a367-2d213f7191bf` committed with
`shadingTurns=1`, `previous=0` because this run began from a fresh client
orientation baseline.

The native recording is
`server-live-rerun-20261023/ordinary-renderer-reverse-framegate-20261024.mp4`:
854x480, 30 fps, 15.267 seconds, 458 encoded frames, 452 fresh samples,
13 CFR duplicates and 7 recorder drops. No frame reached the near-black
threshold. The maximum adjacent-frame mean absolute difference was 4.615 and
the 95th percentile was 1.342; manual review attributes the peak to the
changing dawn sky, not to the seam commit. The runway remained present and
the player stayed at the expected height through the reverse crossing.

This closes the clean reverse runway geometry gate for the warmed synthetic
fixture. It is still not an exact pixel-equivalence result, and it does not
prove orientation recovery from a preceding forward chart because the run
started with a new client baseline. Entity/block-entity visual checks and
third-party renderer combinations remain open.

### Mounted entity visual gate (2026-10-24)

The existing `tellus cube_net mount_probe` was rerun after explicitly
dismounting the prior probe through `ride @s dismount`. The valid third-person
recording is
`server-live-rerun-20261023/mounted-thirdperson-framegate-20261024b.mp4`.
It starts at `(15360.5, 100.55, -128.5)`, yaw `0`, with the player mounted,
crosses the seam, and ends at `(10192.9031, 100.55, 5120.5)`, yaw `90`.
Transition `be78e762-291a-47ca-8eb0-279bdf0f7409` committed with
`shadingTurns=2`, `previous=3`.

The recording is 25.167 seconds at 854x480/30 fps with 755 encoded frames,
750 fresh samples, 14 duplicates and 9 drops. The same client boat UUID
`febcd4f9-fe31-4462-9812-86881b839985` remained alive at the target; the
client entity query reported boat id `234` at the target and a player-to-boat
distance of `0.45`. The third-person contact sheet retains both the player and
boat through the commit window, and the client log has no unknown-entity or
passenger warning for this run.

This closes the mounted player/boat relation and visible-retention probe for
the ordinary Fabric/Indigo renderer. The recording uses a night segment, so a
global near-black threshold is not a valid pass criterion for this clip; the
claim is based on the direct entity query, commit log, and frame review. It is
not generic entity coverage and does not close chest, sign, or other
block-entity families.

### Sign and chest block-entity live gate (2026-10-24)

The repaired RFC jar was rerun with the disposable offline Fabric server and
`tellus-1.20.1` client. The loaded `cube-net-visuals` fixture placed a sign at
proxy `(15360,100,12)` and observed its canonical copy at `(10227,100,5120)`.
The target `front_text.messages` were `Proxy seam sign`, `Tellus`,
`2026-10-18`, `POS_X`. After changing the canonical sign to `Canonical sign`,
`Tellus`, `2026-10-24`, `NEG_Z`, the proxy query returned the same four lines.

The same run placed a chest at proxy `(15360,100,10)` and observed the
canonical copy at `(10229,100,5120)`. The canonical query contained
`CustomName=Proxy chest` and one `minecraft:diamond` in slot 0. After the
canonical edit, the proxy query contained `CustomName=Canonical chest` and one
`minecraft:emerald` in slot 0. These checks exercise the generic
`BlockEntity#setChanged` mirror in both directions for a text sign and an
inventory block entity. The command transcript and exact NBT observations are
in `server-live-rerun-20261023/block-entity-live-20261024.md`; the server log
records the four fixture calls at 22:19:50, 22:22:02, 22:23:17, and 22:23:58
CST.

This closes the loaded-apron, bidirectional sign/chest payload gate. It does
not close unloaded-target persistence, container interaction across the seam,
or every block-entity family. The earlier chest restart result remains the
applicable persistence evidence.

### Proxy chest interaction gate (2026-10-24)

A second server/client run opened the proxy chest through the real client GUI.
The player was staged at `(15360.5,100,7.5)` and aimed at the chest with yaw
`0`, pitch `20`. A right-click opened a `minecraft:generic_9x3` screen titled
`Proxy chest`; slot 0 contained one diamond. The captured GUI is
`server-live-rerun-20261023/block-entity-interaction-open.png`.

A real left-click removed the diamond from slot 0. The follow-up server queries
showed `Items: []` at both canonical `(10229,100,5120)` and proxy
`(15360,100,10)`, so the proxy container interaction reached the canonical
owner and mirrored back. The exact GUI and query evidence is appended to
`server-live-rerun-20261023/block-entity-live-20261024.md`.

This closes proxy-side chest open/read/write interaction. It does not test
keeping a container screen open while the player itself is rebased across a
seam, and it does not generalize to every block-entity GUI.

### Residual seam handoff stutter deferred (2026-10-25)

The duplicate/rebound handoff defect is fixed: forward ground movement,
airborne movement with horizontal input, and free fall all completed through
the atomic custom commit path without a second vanilla commit or an upward
correction. A short visual pause of several frames is still visible while the
client promotes the staged chart at the boundary. It is a brief handoff
stutter, not a repeated transfer or a movement-state loop.

This is recorded as a low-priority visual follow-up and is not a blocker for
the current RFC work. Do not spend more implementation time on it before the
remaining visual and compatibility gates have a concrete acceptance run. If
it is revisited, measure the exact frame interval and split it into client
chart promotion, chunk-cache handoff, and frame-scheduling time before changing
the transfer protocol again.

### Visible exclusion boundary (2026-10-26)

The Minecraft 1.20.1 RFC client now renders a local translucent blue plane
with the vanilla forcefield texture and scrolling diagonal stripes. The server
locates the nearby wall using the existing movement admission rules, including
the conservative chunk allowance. It refreshes the marker once per second near
the exclusion boundary; opacity decreases with distance and reaches zero at
96 blocks. Ordinary face seams do not trigger this proximity cue.

The visual surface is offset half a block into the blocked side so the camera
does not clip it away at contact. Fine boundary refinement fixes the
near-contact ray ties that previously rotated the plane edge-on. This remains
a local tangent segment, rather than a complete mesh of the exclusion outline.

Focused build and tests passed: 10 boundary contracts, 9 chunk-generator checks
(including movement/visual alignment, ordinary seam exclusion, and the observed
sub-block contact regression), and 5 singularity-policy checks; no failures or
skips. These are focused checks, not a full project test pass.

The final jar was installed on both the disposable synthetic-terrain Fabric
server `tellus-boundary-20261025` and client `tellus-1.20.1`. In the actual
client, holding W for eight seconds from the west-facing approach stopped at
`(10848.010883156105, 80, -619.5)`, just outside the blocked chunk. The plane
remained visible at contact. Earlier sustained-contact screenshots also show
the action-bar warning. Retreating approximately six blocks showed the full
diagonal pattern; moving to `(11020.5,80,-779.5)` removed the plane.

Screenshots reviewed directly are in
`D:/workshop/Game/mc-pilot-deployment/`: `boundary-final-contact-20261026.png`,
`boundary-final-near-20261026.png`, `boundary-final-away-20261026.png`, and
`boundary-south-contact-20261026.png` (warning text). The client and server
were left running for human review at `(10854.5,80,-619.5)`, yaw 90, pitch 12.

### Real terrain generation stability (2026-10-26)

This run used the installed Fabric 1.20.1 cube-net jar with real Mapterhorn
elevation and land-cover readers, `world_scale=1`, Y range `[-2032,2032)`,
6 GiB server heap, and the synthetic sampler disabled. It exercised ordinary
face interiors, with no additional boundary acceptance run.

Two fresh 5x5 FULL-chunk areas were generated: Switzerland near
`46.02176 N, 7.74811 E` (planar chunk `616246,-351597`) and Chengdu near
`30.67 N, 104.06 E` (chunk `226404,-724012`). Both completed 25/25 requests,
in 11.052 s and 13.643 s respectively. A read-only saved-region inspection
confirmed all 50 requested chunks had `minecraft:full` status and nonempty
terrain. WORLD_SURFACE heights were Y=1669..1674 and Y=563..570 respectively.

The real client then flew approximately 100 blocks west at each location to
stream neighboring terrain. Near-ground screenshots showed terrain at both
locations. After saving and restarting the server, every requested chunk's
full block-state hash matched its initial hash (50/50). Generating those same
areas in a second empty save, using the now-warmed source cache, also matched
all 50 initial hashes. This checks first generation, saved reload, and repeat
generation separately. A final six-second ground walk covered about 26 blocks
in Chengdu and ended at Y=566 with `onGround=true`.

First entry into a region produced server-lag warnings of 2.693 s and 3.581 s
in separate runs. These are server scheduling observations, not measured frame
freeze durations. The synchronous benchmark commands also caused larger
11--14 s lag warnings while requesting their batches. This run establishes
bounded terrain consistency, not hitch-free loading or global elevation and
dataset coverage.

The [portable evidence directory](validation/terrain-stability-20261026/) contains
the compact comparison, per-chunk hashes, saved-region inspection and two
near-ground screenshots. Full logs, test saves and remaining captures stay in
`D:/workshop/Game/mc-pilot-deployment/terrain-stability-20261026/`. The original synthetic saves were retained. The
client/server were left running on `world-terrain-repeat-20261026` for review.
