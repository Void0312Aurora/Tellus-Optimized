# Cube-Net DH seam rendering design

## Problem statement

The native Cube-Net transition and the Distant Horizons (DH) render tree have
different coverage contracts.

* The native route is bounded by the configured apron.  The apron is a
  gameplay and chunk-loading buffer; it must not turn into a second,
  half-face world.
* DH renders planar LOD sections and has no face identity.  A section that
  crosses a cut therefore cannot be sampled as one column unless all of its
  cells have the same chart owner.
* A seam rebase changes the planar center seen by DH.  DH's current public API
  has no operation to prewarm a remote render section and no operation to keep
  an old render section visible until the new one is ready.

The current Tellus fail-closed behavior is deliberate: an unsupported column
fails the direct generator instead of being cached as air, and a coarse request
that crosses a cut is split.  This protects data correctness, but it does not
by itself guarantee a pop-free frame at the instant of a rebase.

## Required behavior

For a seam transition to be accepted as visually continuous, all of these
conditions must hold:

1. Native chunk loading and entity/player transfer remain inside the native
   apron.  No request outside the apron may create a persisted or gameplay
   chunk.
2. Every DH section has one deterministic chart owner.  Canonical face
   rectangles own themselves; a non-canonical cut strip is accepted only when
   it is within the native apron and has a unique, non-tied owner.
3. A coarse DH section that samples more than one chart is split before any
   terrain data is written.  An unsupported finest-detail column is retried;
   it is never represented by cached air.
4. Before a seam rebase commits, the target render envelope must either be
   ready or the source render envelope must remain visible while it is being
   prepared.  Releasing the source envelope first is a visible hole/pop.
5. Source and target samples on a seam must resolve to the same canonical
   terrain layers, including water, bedrock, and block orientation.

## Selected architecture

Use two explicitly separate envelopes:

* **Native envelope:** the existing `CubeNetApronResolver` and runtime
  transfer radius.  It controls chunk tickets, chunk staging, simulation, and
  rebase admissibility.
* **DH render envelope:** a render-only set of canonical face LOD sections
  needed for the next seam.  It is allowed to be generated before the player
  is rebased, but it must not be materialized as native chunks or become a
  second source of world persistence.

The transition sequence is:

1. `CubeNetRuntime` identifies the source seam and computes the target face
   plus the DH detail levels needed by the visible render radius.
2. The DH integration asks the DH fork to prewarm those target sections.  The
   request is keyed by canonical planar section position and carries a
   transition id, so stale preparations can be cancelled.
3. The server/client native staging continues to use the existing apron gate.
4. The seam commit is held until native staging is ready and the DH target
   envelope reports ready.  If the DH target is delayed, the source DH
   sections remain enabled; the player is held by the existing transition
   state rather than accepting a blank remote view.
5. After the new center is installed and target sections have uploaded, the
   DH fork releases the source envelope.  A timeout cancels the preparation
   and leaves the player on the source chart; it does not publish partial
   target data.

This avoids enlarging the native apron and avoids mapping a request across an
arbitrary half-face strip.  It also makes the visual continuity guarantee a
state transition that can be tested instead of an assumption about queue
timing.

## DH API feasibility result

The inspected DH fork exposes `IDhApiTerrainDataRepo.overwriteChunkDataAsync`,
but that method applies a Minecraft chunk update to the normal data updater.
It does not enqueue an arbitrary LOD render section and it does not provide a
render-section hold/release primitive.  `LodQuadTree` currently closes sections
that leave its center window and then queues new sections; `LodRenderSection`
replaces its old GPU buffer only after a new upload completes, but a section
that was closed during recentering cannot serve as the old envelope.

Therefore the selected architecture needs a small DH fork extension rather
than a Tellus-only workaround.  That extension is now implemented in the
local DH fork.  `DhApiSeamTransition` exposes only a transition-scoped
`begin/ready/end` contract; it does not expose the internal quad-tree or allow
arbitrary persisted chunks.  `LodQuadTree` switches its detail center to the
target, retains source sections that leave the target window, and releases
them only after target sections have uploaded.  Cancellation recenters the
source tree and restores retained sections.

Tellus calls this bridge reflectively from the client transition.  A stock DH
build without the bridge remains compatible through the existing fail-open
path; the DH fork build activates the hold.  Native chunk loading and the
entity transfer still use the existing apron and are not widened by this
extension.

## Validation plan

### Automated Tellus checks

* Enumerate all fourteen non-planar cube-net cuts and compare every accepted
  apron address with its canonical target face address.
* Check canonical ownership, unique cut ownership, vertex/tie rejection, and
  rejection beyond the configured apron.
* For DH detail levels, assert that a section crossing a cut returns SPLIT and
  that a finest-detail unsupported column returns WAIT/empty rather than air.
* Compare native and LOD columns at seam-adjacent positions, including water,
  bedrock, and all layer states.
* Exercise the runtime staging gate and ensure the rebase cannot commit before
  the native target area is loaded.

### DH fork checks

The fork extension should eventually have direct unit tests for:

* idempotent prewarm requests;
* cancellation of a stale transition id;
* readiness only after data source retrieval and GPU upload;
* source-section retention while target data is pending;
* release after success and rollback after timeout/failure.

### Prism graphical acceptance

Run with a cold DH cache, the pinned Tellus/Iris/Sodium/DH versions, and a
long DH distance.  Approach each representative cut, capture frames before the
apron, during the held transition, immediately after commit, and after the
target LOD upload.  Collect the Tellus transition log and DH generation logs.

Pass criteria are: no blank or black remote band, no disappearance of the
source far terrain while the target is pending, no direct-tile failure in the
visible envelope, no repeated generation loop, and no visible terrain/material
discontinuity at the seam.  A deliberately delayed target tile is a required
negative test: the source remote view must remain visible and the transition
must stay held.

The automated suite now proves the ownership/fail-closed portions and compiles
the fork bridge into the Fabric runtime jar.  A Prism run with the new pair is
still required before claiming pop-free graphical continuity; source retention
and target readiness are implemented but not accepted from compilation alone.
