# Follow-up renderer validation — 2026-11-02

## Result

The reproducible source-terrain disappearance on joining near a seam is fixed
by preserving the order of the Cube-Net preparation, cache-center and chunk
packets. The source terrain now remains visible during destination staging,
and both directions retain geometry in the first eight logged frames after
the actual chart commit.

This validates the pinned Fabric 1.20.1 / Sodium 0.5.13 / Iris 1.7.6 /
DH 3.3.1-tellus.1 stack, with Complementary enabled and view distance 4.
It does not establish compatibility with every renderer, shader or view
distance. The server fixture is the Cube-Net unfold world on
`127.0.0.1:25582`.

## Root cause and correction

`TellusClient` registered Fabric's typed `PlayPacketHandler` receivers and
then called `Minecraft.execute()` again inside the preparation handler.
Fabric already dispatches this receiver on the render thread. While
Minecraft is executing a queued task, its `ReentrantBlockableEventLoop`
queues a nested `execute()` instead of running it inline. A startup packet
backlog therefore allowed subsequent vanilla cache-center and chunk packets
to run before `CubeNetClientTransition.prepare()`.

The destination center moved the live cache before Tellus installed the
staging interception. Source chunk packets were then rejected as outside the
cache's view range. This is visible in `v5-unpatched-client.log`: the source
chunk rejection warnings precede `client preparing`, and the terrain remains
absent more than a minute after destination staging is ready.

The final change calls prepare, commit, cancel and layout configuration
directly from the typed receivers. The disconnect callback remains explicitly
scheduled onto the client thread because that separate Fabric lifecycle
event can run on Netty. No active-manager queue repair, forced rebuild or
culling override was added.

The local Fabric 1.3.15 networking sources and Minecraft 1.20.1
`ReentrantBlockableEventLoop` bytecode were inspected to verify these
dispatch semantics.

## Evidence

| Capture | State | Visible geometry | Built / managed sections | DH |
| --- | --- | ---: | ---: | --- |
| `v4-01-reconnect-pending` | Unpatched startup, source retained | 0 | 1315 / 1776 | Disabled |
| `v4-02-postcommit-after-reconnect` | Unpatched, actual forward commit | 82 | 1296 / 1296 | Disabled |
| `v5-02-reconnect-pending-queue` | Unpatched startup at opposite side, staging ready | 0 | 1297 / 1760 | Disabled |
| `v6-01-fixed-reconnect-pending` | Fixed startup, destination still compiling | 81 | 856 / 1104 | Disabled |
| `v6-02-fixed-staging-ready` | Same source, destination ready | 81 | 856 / 1104 | Disabled |
| `v6-04-fixed-reverse-commit-dh-on` | Fixed actual reverse commit | 84 | 1296 / 1296 | Default |
| `v6-06-fixed-forward-ready-dh-on` | Fixed source, forward destination ready | 84 | 1296 / 1296 | Default |
| `v6-07-fixed-forward-commit-dh-on` | Fixed actual forward commit | 82 | 1296 / 1296 | Default |
| `v7-01-fixed-source-reconnect` | Second fixed startup, other side, staging ready | 81 | 857 / 1104 | Default |

The strongest same-pose comparison is `v5-02` versus `v6-01/02`: player
`(10215.5, 110, 5119.4653017)`, yaw -90, pitch 25, shaders enabled, DH
disabled. The other startup orientation is `v4-01` versus `v7-01`: player
`(15359.4653017, 110, -24.5)`, yaw 0, pitch 25. DH is enabled in the latter
fixed run; its native Sodium geometry count is independently nonzero.

The diagnostic probe added `manager.rebuildQueues` and `pendingUpdates`.
In `v5-02`, all three queues are empty even though 463 sections are marked
`INITIAL_BUILD` and 833 are marked `REBUILD`. Splitting those sections by
coordinate is essential: all 1296 sections with pending work are in the
**remote destination**, outside the source view. The 464 source sections
remaining in the active manager are all built air sections. Thus the empty
queues are a symptom of missing source data, not proof of a lost source
build queue. Empty queues also occur in healthy fixed captures.

The earlier stable-world `v3` run already passed forward and reverse commits.
The fresh startup `v4` run reproduced the source hole and then passed the
forward commit. These controls narrowed the failure to preparation ordering
under a startup backlog, rather than promotion of the staged renderer.

`v6-fixed-client.log` contains the fixed commit frame traces. The reverse
commit at 15:33:15 reports 84 visible sections in frames 1–8; the forward
commit at 15:34:43 reports 82 in frames 1–8. No zero-geometry frame appears
in those eight-frame samples. This is frame-counter evidence; no continuous
video or claim about every possible frame is implied.

## Lifecycle and build verification

The test account was disconnected by the test server at 15:39:53 while a
fully built destination renderer was still pending. Both native renderer
workers stopped on the render thread, and the saved client log contains no
`Rendersystem called from wrong thread` exception. It then reconnected for
`v7-01`, which also passed. A server `handleDisconnection() called twice`
warning is retained in the log; no client renderer exception accompanied it.

`:mc1201:test` and `:mc1201:remapJar` passed. The JUnit XML totals are
986 tests, 0 failures, 0 errors, 11 skipped. Output is retained in
`packet-order-build-test.log`. The diagnostic probe also rebuilt and remapped
successfully.

Client jar SHA-256:

* Unpatched: `f1aa87c263c1af5e10d02d9ea4e463e3fddaea54f2d27a27538e4d17668b3eab`
* Fixed: `53ffea52b735a5f419a7386248d35e6aba2a81e1121fdecadfe0afa6f429adb6`

## Historical controls and naming limitations

The `v2` captures were taken at the source while a transition was pending;
their names containing `target` do not imply a completed commit. Native
geometry was zero with DH on, DH off and fog occlusion off. DH could still
supply a visible far-view runway while the native near terrain was absent.

* `v2-12-culling-off` is invalid as a culling A/B: Sodium restored
  `smartCull=true`.
* `v2-14-shaders-off` is invalid as a shader A/B: Iris reload failed and the
  probe still reported `shaders=true`.
* `v3-06-source-stable-after-reverse` was actually captured during the next
  fresh reconnect, not a stable reverse-commit frame. The same capture is
  archived under the accurate name `v4-01-reconnect-pending`.
* `v5-01-active-queue-diagnostic` was captured after an actual commit, so it
  is a healthy queue control, not the startup-failure sample.
* `v6-03` toggled DH on and consequently reloaded Sodium. The existing staged
  renderer was correctly invalidated and a new preparation completed before
  `v6-04`; it is not a same-renderer continuity test across that toggle.
* `v6-05`, `v6-08` and `v6-09` set the next camera direction. Their captures
  precede the resulting preparation log and must not be counted as ready
  staging captures.

Authentication/profile-key errors from the offline test account and the
existing shader uniform warning are present on both sides of the fix; they
are not counted as new renderer failures.

## Follow-up: residual stutter

The frame-time study, timing-probe output, and current diagnosis are recorded
in [lag-study-20261102.md](lag-study-20261102.md).

面切换后的 Iris 方向光照验证见
[`orientation-render-study-20261103e/README.md`](../../../orientation-render-study-20261103e/README.md)。该验证覆盖太阳/阴影方向的正向四分之一转和反向恢复；逐像素材质亮度 A/B 仍未完成。
