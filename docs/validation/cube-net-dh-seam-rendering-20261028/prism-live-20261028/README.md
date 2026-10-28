# Prism live run — 2026-10-28

This run used the isolated Prism instance
`/home/void0312/.local/share/PrismLauncher/instances/tellus-dh-seam-live-20261028`
with the pinned Tellus/DH/Iris/Sodium pair and a local offline server on
`127.0.0.1:25580`.

Installed production artifacts:

```text
DistantHorizons-fabric-3.3.1-tellus.1-1.20.1.jar
SHA256 eefb199ed0b1309d020913848a3df04a6c8b713bda7a3ba43ee132490cab5c71
Tellus-fabric-1.20.1-0.8.4.jar
SHA256 44d771c56578b6c1fa0194de0f2b02357941c2a65e179aa12b32022e3c09ff75
Iris 1.7.6, Complementary Reimagined r5.5.1, Sodium 0.5.13, Fabric API 0.92.12
```

The client initialized DH and Iris on an NVIDIA GeForce RTX 3090, joined the
server, and rendered `tellus:earth_cube_net`. Transition
`7e071ae2-adce-47cf-bef3-b234f6b21f19` was accepted as follows:

* destination chunks: 121;
* native/Sodium staging: 1,296 sections, ready after 12,433 ms;
* the client logged `Cube-net DH render envelope preparing`; staging became
  ready at 21:12:46, while commit was held until 21:15:23 and the source-side
  player remained in the transition;
* at 21:15:23 the server logged apron rebase deferral, seam transfer complete,
  and first custom motion; the client logged DH envelope commit, chart commit,
  pose commit, and committed frames 1–8;
* the server then shut down cleanly after the client disconnected.

Screenshots captured during the run (the last one is retained for audit only,
not as a visual pass criterion):

* [connected world](connected.png)
* [held transition with F3](seam-held.png)
* [post-commit F3](seam-forward-f3.png)
* [post-commit rendered frame — invalid close-up of the test sign](seam-postcommit.png)

The complete selected evidence is in [client-evidence.log](client-evidence.log)
and [server-evidence.log](server-evidence.log).

## Scope and warnings

This is a real client/server run, not a build-only check. It proves that the
new production DH jar loads, Iris/Sodium/DH initialize, the render-envelope
bridge reaches `ready`, and one seam transfer commits without a client crash.
It does not establish a controlled, pop-free distant panorama over every face
or long-duration memory stability. No artificial target-tile delay was injected
in this run, so the delayed-target negative test remains open. The post-commit
image is aimed directly at the test sign and is therefore invalid as visual
continuity evidence; it is retained only to show that the client remained
alive and rendered a frame.

Iris emitted `Unknown variable: BIOME_PALE_GARDEN` while parsing the selected
shader pack. The client continued rendering; this is recorded as a nonfatal
shader compatibility warning. Offline authentication (401) and missing sound
assets were also nonfatal. The initially tested `*-all.jar` was not used: its
access widener was unmapped for the direct Fabric launch. The final run used
the production jar without the `-all` suffix, whose access widener is
`intermediary`.
