# Prism live acceptance: DH plus cube-net seam transfer

Date: 2026-10-28

This is the rerun after rejecting the earlier post-commit screenshot that was aimed at the test sign. The run used a fresh Prism client cache, the Tellus Sodium native backend, Iris 1.7.6 with Complementary r5.5.1, and the Tellus DH fork 3.3.1-tellus.1.

## Acceptance evidence

- `source-before-transition.png`: source-face view before the transition; open ground, horizon, and no test sign.
- `staged-before-commit.png`: client staging view while the target chart was compiled and held; the frame is responsive and contains terrain/sky references.
- `postcommit-remote-view.png`: a new frame captured after the same transition committed and after the camera was reframed away from the test sign. It contains the target ground plane, distant colored structure, sky, and horizon; it is not the rejected sign close-up.
- `server-evidence.log` and `client-evidence.log` bind the screenshots to transition `a65cf7af-6a7d-4fe0-baff-6fde7862d1de`.

The server trace records `Cube-net apron rebase deferred`, `approach consumed ... ready=true`, `seam correction armed ... mechanism=atomic-custom`, `seam transfer complete`, and `first custom motion accepted` at target `(10215.5, 100.0, 5120.5)`. The same run requested and generated DH detail-4 target tiles on the destination side. The client trace records all 121 destination chunks, Sodium staging readiness, and repeated post-commit `committed frame` records with detail `D: 4`.

## Scope and limits

This is a controlled live seam handoff: the server command placed the player at apron penetration 24.5 to exercise the existing apron-rebase path, then the normal DH/Sodium client protocol staged and committed the destination. The screenshot proves the post-commit renderer remained responsive and showed a valid remote scene. It does not claim that the Wayland desktop input path was usable for manual walking in this run; the virtual keyboard did not reach the XWayland window, so the trigger was server-side.

Known non-fatal warnings remain: the server-side test teleport reports vanilla "moved too quickly" before the guarded rebase, Iris reports the Complementary `BIOME_PALE_GARDEN` variable, DH reports the OpenGL capability probe warning while GLProxy initializes, and DH reports the G1 collector warning. No renderer exception or transition timeout occurred in the accepted run.
