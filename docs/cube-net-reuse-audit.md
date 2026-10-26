# Cube-net reuse audit

**Date:** 2026-10-18

**Scope:** Tellus RFC prototype on Fabric 1.20.1

**Status:** decision record; no third-party dependency has been added

## Decision

Tellus will reuse established portal and entity-transfer patterns where they
match the runtime contract, but it will not add Immersive Portals as a direct
dependency. The current upstream implementation is dimension-to-dimension
portal infrastructure. Tellus needs a single `Level` with six canonical face
charts, derived same-Level apron chunks, canonical ownership, write-through,
and a coordinate rebase after the player has already entered the native apron.

The reusable boundary is therefore an adapter-level concept, not a dependency
swap:

- retain Tellus-specific seam mapping, ownership, apron generation, persistence
  filtering, block-state rotation, and same-Level position correction;
- reuse or copy only narrowly scoped algorithms after checking their version,
  mixin assumptions, and Apache-2.0 obligations;
- keep generic entity bundle behavior behind a small internal contract so a
  later upstream implementation can replace the current proof path without
  changing seam mapping code.

## Verified community reference

[Immersive Portals 1.20.1](https://github.com/iPortalTeam/ImmersivePortalsMod/tree/1.20.1)
is the closest relevant implementation found in this audit. Its README
describes seamless portals, loading multiple dimensions on the client, and
synchronizing remote chunks and entities. Its core module declares
`Apache-2.0` in `fabric.mod.json` and `LICENSE`.

The source confirms these reusable patterns:

| Capability | Upstream evidence | Tellus disposition |
| --- | --- | --- |
| Passenger and vehicle transfer | [`ServerTeleportationManager`](https://github.com/iPortalTeam/ImmersivePortalsMod/blob/1.20.1/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/teleportation/ServerTeleportationManager.java) collects a root entity's passengers, moves them, and reattaches them. Player-containing passenger clusters are rejected before regular entity transfer. | Reuse the relationship checks and bundle model; keep Tellus's seam transform and same-Level commit path. |
| Player vehicle recovery on the client | [`ClientTeleportationManager`](https://github.com/iPortalTeam/ImmersivePortalsMod/blob/1.20.1/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/teleportation/ClientTeleportationManager.java) moves the vehicle and starts riding again after a dimension change. | Use as a reference for player-mounted cases; it is not a drop-in path because Tellus does not change dimensions. |
| Remote chunk loading | [`NewChunkTrackingGraph`](https://github.com/iPortalTeam/ImmersivePortalsMod/blob/1.20.1/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/chunk_loading/NewChunkTrackingGraph.java) maintains additional per-player chunk loaders. | Reuse the preload requirement and lifecycle shape; Tellus currently stages 5x5 chunk data to compile an inner 3x3 mesh area. Full configured-view coverage remains open. |
| Remote entity tracking | [`EntitySync`](https://github.com/iPortalTeam/ImmersivePortalsMod/blob/1.20.1/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/chunk_loading/EntitySync.java) replaces ordinary tracking updates while multiple dimensions are visible. | Reference only. Tellus uses native same-Level tracking plus a derived apron, so the tracking boundary differs. |
| Portal position and velocity transforms | [`TeleportationUtil`](https://github.com/iPortalTeam/ImmersivePortalsMod/blob/1.20.1/imm_ptl_core/src/main/java/qouteall/imm_ptl/core/teleportation/TeleportationUtil.java) handles portal-relative velocity and collision transforms. | Use the transform separation as a design reference; Tellus must apply cube-net quarter-turns and face-local coordinates. |

## Compatibility findings

The upstream regular entity path calls `changeEntityDimension` and can create
a replacement entity in another `ServerLevel`. The client path also switches
the active `ClientLevel`. That is materially different from Tellus:

1. Tellus keeps one `ServerLevel` and one planar chart.
2. An apron position is a derived view whose canonical owner is on another
   face, not another dimension.
3. The entity crosses the seam in the apron first and is rebased only after the
   destination chunks are ready.
4. Block and block-entity writes must remain single-owner and be reflected into
   derived chunks.

The upstream source does not provide a leash-bundle contract. Tellus's current
local leash probe is therefore useful as a seam-specific persistence check, but
it should not grow into a general portal-transfer framework without a reuse
decision.

The upstream repository is currently marked as archived and its current README
says it is no longer maintained. The 1.20.1 source is still valuable as a
reference and its license permits reuse with attribution, but version and Mixin
compatibility must be treated as an implementation cost.

## Tellus-specific surface

These components have no direct equivalent in the reviewed upstream path and
remain Tellus-owned:

- `CubeNetLayout` seam graph and reciprocal quarter-turn transforms;
- canonical `(face, chunkX, chunkZ)` ownership;
- `CubeNetApronResolver` and derived native `LevelChunk` materialization;
- inverse-rotated block-state and block-entity write-through;
- persistence exclusion and sparse canonical edit overlay;
- same-Level movement correction, PREPARE/STAGED/COMMIT/RELEASE handshake;
- holonomy, corner quarantine, and apron overlap rules.

## Work-order change

1. Keep the non-player passenger/leash and player-mounted boat fixtures as
   verified RFC probes, not as broad generic entity coverage.
2. Close the frame-by-frame visual handoff gate for the mounted bundle.
   The 2026-10-19 follow-up fixed a network-thread mutation race, native vehicle
   movement baselines and the acknowledged movement guard. The 2026-10-20
   full-height forward and reverse routes now complete with asynchronous staging
   and zero correction resends; see `cube-net-client-acceptance.md`. The forward
   mounted cold reload and elevated walking/sprinting in both directions now
   complete on the synthetic fixture. The saved-edit overlay/owner-load repair
   also has walking/sprinting and mounted routes in both directions. A pause
   regression found that ready preparation expired on both server and client;
   both lifecycles now retain it until commit/cancel. The final live case kept
   the same chart ready for 138 seconds and removed an eight-frame terrain gap
   seen with the server-only fix. See the acceptance record for build provenance
   and video limits. Exact zero-hold continuity remains open. Native-resolution
   review also corrected the earlier player-model disappearance claim.

3. Do not add more entity types or broad block-entity cases until that visual
   result is recorded.
4. After visual acceptance, expand the bundle matrix to dismount/re-mount,
   multi-player trees, and interaction-sensitive vehicles.
5. Only after that comparison, decide whether to extract a narrow Apache-licensed
   algorithm or continue with Tellus-owned code.

This audit changes the next step from feature expansion to a visual handoff
gate. The current live leash, passenger, and player-mounted results remain
valid evidence for Tellus's native apron path, but they are not evidence that
Tellus needs to reimplement a general portal engine.
