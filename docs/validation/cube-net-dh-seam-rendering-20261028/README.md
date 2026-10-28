# Cube-Net DH seam-rendering design validation (2026-10-28)

This record validates the bounded ownership/fail-closed portion of the seam
design and the first implementation of its DH render-envelope gate in [the
design note](../../design/cube-net-dh-seam-rendering-20261028.md).  It also
contains a real Prism run with the newly built pair.  That run proves the
bridge lifecycle and one committed transfer; it does not claim pop-free
graphical continuity over every face or long-duration stability.

## Tellus checks

The targeted seam and DH tests were rerun with `--rerun-tasks`:

```text
:mc1201:test
  CubeNetLodSurfaceTest                 5 passed
  CubeNetRemoteProjectionTest           5 passed
  CubeNetViewCoverageTest               7 passed
  CubeNetApronRotationContractTest      1 passed
  ManagedTerrainAvailabilityTest         5 passed
```

The full `mc1201` test task then passed 971 tests with 0 failures, 0 errors,
and 11 skips. `:mc1201:testParallelChunkgen` passed 7 tests with 0 failures,
0 errors, and 1 skip. The combined result is 978 tests, 0 failures, 0 errors,
and 12 skips.

The resulting 1.20.1 jar before the DH bridge implementation was:

```text
db894f64ac244333b4fce39589626f6e781a13602c5c819922fae9995ccd0417
```

`git diff --check` passed.

These checks establish that canonical face ownership remains valid, all
fourteen non-planar cut seams agree with the apron resolver, coarse cells that
cross a cut are split, unsupported finest-detail columns are not converted to
air, and native/LOD columns retain the same block layers.

## DH fork implementation and checks

The fork now contains a render-only transition contract:

* `DhApiSeamTransition.begin(level, id, targetX, targetZ)` records the target
  planar center without creating native chunks.
* `LodQuadTree` uses that target center for its detail-level decisions, keeps
  source sections that leave the target window in a retained set, and includes
  renderable retained sections in the active list while the target is built.
* `ready` requires an observed target tree, at least one in-bounds target
  section, completed GPU uploads, and no in-bounds missing/queued generation.
* `end(..., true)` releases source sections; `end(..., false)` recenters the
  source tree and restores retained sections.

Tellus calls the bridge reflectively, so a stock DH build continues through
the previous compatibility path.  The native apron and native chunk ticket
radius are unchanged.

Using JDK 25, after the implementation:

```text
./gradlew --no-daemon --max-workers=2 -PmcVer=1.20.1 :api:test :core:test
```

The API/core test reports contain 5 API tests and 75 core tests, all passing;
there were no skips.  The Fabric `:fabric:remapJar` task also passed and the
resulting all-in-one jar contains `DhApiSeamTransition.class`.

The new DH fork production jar (the jar without the `-all` suffix) is:

```text
eefb199ed0b1309d020913848a3df04a6c8b713bda7a3ba43ee132490cab5c71  fabric/build/libs/DistantHorizons-fabric-3.3.1-tellus.1-1.20.1.jar
```

The `*-all.jar` produced during the earlier build was retained as an
intermediate artifact only.  Its access widener was `named`, so it is not the
correct direct Fabric runtime artifact; the installed production jar has an
`intermediary` access widener.

The Tellus build was rerun against the implementation:

```text
:mc1201:test                   978 tests, 0 failures, 0 errors, 12 skips
:mc1201:testParallelChunkgen     7 tests, 0 failures, 0 errors, 1 skip
:mc1201:build                  BUILD SUCCESSFUL
```

The resulting Tellus jar is:

```text
44d771c56578b6c1fa0194de0f2b02357941c2a65e179aa12b32022e3c09ff75  mc1201/build/libs/Tellus-fabric-1.20.1-0.8.4.jar
```

The fork source confirms that `overwriteChunkDataAsync` applies a Minecraft
chunk update to the normal data updater. It is not a render-section prewarm
operation. `LodQuadTree` removes sections outside its recentered window, and
there is no public hold/release operation for retaining the previous remote
envelope. This is why a Tellus-only change cannot prove visual continuity.

## Prism live acceptance

The isolated Prism run and its evidence are recorded in
[prism-live-20261028](prism-live-20261028/README.md).  It used the production
DH jar above, Tellus `44d771c56578b6c1fa0194de0f2b02357941c2a65e179aa12b32022e3c09ff75`,
Iris 1.7.6 with Complementary Reimagined r5.5.1, and Sodium 0.5.13 on an RTX
3090.  Transition `7e071ae2-adce-47cf-bef3-b234f6b21f19` received 121
destination chunks, reached DH/Sodium staging readiness with 1,296 sections
after 12,433 ms, held the source envelope until the target was ready, and
committed the server transfer, DH envelope, render chart, pose, and first
custom motion without a client crash.

This is sufficient to accept the implemented lifecycle and one real seam
handoff.  It is not a visual-quality sign-off: the captured post-commit frame
is aimed at the test sign and is invalid for judging distant continuity.  The
run did not provide a controlled panorama proving pop-free distant continuity
on every seam.  Iris also logged the nonfatal `BIOME_PALE_GARDEN`
shader-variable warning; the client continued rendering.
