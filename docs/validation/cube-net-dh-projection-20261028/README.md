# Cube-Net seam-aware DH long-distance validation (2026-10-28)

The long-distance graphical sample used a cold copy of the existing `tellus:earth_cube_net` world and an earlier diagnostic build before the runtime-override follow-up. That sample intentionally exercised the old half-face remote projection and is retained as historical evidence only. The original world and Prism instance were not used as the server save. The Prism Distant Horizons configuration was restored after the run.

## Configuration

- Minecraft 1.20.1, Tellus 0.8.4
- Sodium 0.5.13, Iris 1.7.6, ComplementaryReimagined r5.5.1
- Tellus DH fork 3.3.1-tellus.1
- `realTimeUpdateDistanceRadiusInChunks=512`
- `maxGenerationRequestDistance=8192`
- `maxSyncOnLoadRequestDistance=8192`
- `generationMaxChunkRadius=512`
- `lodChunkRenderDistanceRadius=512`
- `generatorPlan=SURFACE_THEN_CHUNKS`
- direct path enabled with `-Dtellus.dhCubeNetSeamLod=true`

## Result

The historical graphical client connected to `tellus:earth_cube_net` and received the long-distance configuration (`generation.maxRequestDistance=8192`, `generation.bounds.radiusInChunks=512`, `realTimeUpdates.playerDistance=512`). Its cold queue reported 1,061,632 LOD requests. The server registered the direct seam-aware generator and logged 16 detail-5 tile starts; 15 completed before shutdown. Thirteen completed tiles filled all 4096 columns. Two tiles filled 3628/4096 and 3617/4096 columns, with 468 and 479 columns outside the then-bounded resolver. Because that run predates the apron restriction, it must not be read as proof that current code projects half a face across a cut.

No active generation failure, unknown-generator warning, or DH API validation error appeared. One `CancellationException` was logged only when the test was deliberately stopped, as the queue closed. The client completed Cube-Net load, DH OpenGL setup, and shader loading without a crash. The client still emitted the known auth/profile-key, Complementary `BIOME_PALE_GARDEN`, OpenGL 3.3 capability-probe, and G1 GC warnings.

The 1,061,632-entry cold queue was still draining when the deliberate shutdown occurred. This is evidence about the earlier direct path only; it is not evidence that the entire 512-chunk radius completed or that the current restricted path has completed a graphical long-distance run.

The follow-up startup check used the prior latest jar (SHA-256 `4fb70b7e4319aedf353508ee93b062a88eafc2275d3a5a0ce8d9ddfaa4380737`). On successful Cube-Net direct registration it logged the grass-side, busy-server, fast-camera, and `SURFACE_ONLY` runtime overrides. The server shut down cleanly, the saved DH config remained unchanged, and no generator-plan recursion appeared.

The current implementation now limits `CubeNetRemoteProjection` to the configured native apron width. Canonical regions of all six faces remain valid in the shared cube-net plane; a non-canonical cut region farther than the apron is unresolved and cannot be folded into another face. The direct DH adapter rejects such columns instead of caching them as air; coarse requests can split and finest-detail requests remain unavailable. The restriction is covered by the automated Cube-Net projection and LOD tests. The current build passed 978 tests with 0 failures, 0 errors, and 12 skips; its jar SHA-256 is `db894f64ac244333b4fce39589626f6e781a13602c5c819922fae9995ccd0417`.

See `summary.json`, `server-latest.log`, `server-override-check.log`, and `client-latest.log`.
