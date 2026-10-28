# Cube-Net seam-aware DH projection (2026-10-27)

The final build was tested with Minecraft 1.20.1, Sodium 0.5.13, Iris 1.7.6, ComplementaryReimagined r5.5.1, and the Tellus DH fork 3.3.1-tellus.1.

## Implementation gate

`-Dtellus.dhCubeNetSeamLod=true` selects the direct Cube-Net DH generator. The default remains the existing vanilla-chunk adapter until a saved-DH cache migration is available.

The server log records:

```
Registered Tellus Cube-Net DH LOD generator path=direct seamAware=true
```

Direct tiles were generated at DH detail 5 with 64x64 columns at chunk requests `[512,384]`, `[384,384]`, `[384,0]`, `[384,-128]`, and `[512,-512]`. Every completed tile reported `terrainColumns=4096` and `excludedColumns=0`; no server exception, API validation error, or unknown-generator warning was emitted.

## Scope and limits

The sampler folds one directed cut edge at a time and rejects native-apron overlaps, cube vertices, far-side multi-face ties, and coarse cells crossing a chart. Native gameplay chunks and the finite apron continue to use the canonical chunk path. A missing or out-of-range source sample fails the DH task so it can retry; it is not cached as air.

The graphical session reached the Cube-Net dimension, committed 121 native destination chunks / 1296 Sodium sections, initialized DH OpenGL, and kept the Iris shader active. The client still reports the known Complementary r5.5.1 `BIOME_PALE_GARDEN` uniform warning and NVIDIA OpenGL 3.3 capability probe; neither crashed the client. The million-column DH distance was still in progress during capture, so this is an actual direct-tile request/validation proof, not a claim that the full 512-chunk radius finished.
