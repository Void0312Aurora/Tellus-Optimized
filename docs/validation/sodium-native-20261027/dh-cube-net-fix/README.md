# DH Cube-Net generator fix

The failure was an integration coverage gap. Tellus only handled
`EarthChunkGenerator` in its DH level-load callback, so a Cube-Net level never
registered a Tellus DH generator. DH fell back to its generic `DhWorldGenerator`,
whose constructor inspected `CubeNetChunkGenerator` and emitted the
unknown-generator warning.

The fix is scoped to Tellus:

- Tellus recognizes a `CubeNetChunkGenerator` level and registers the existing
  vanilla-chunk LOD adapter. The adapter calls `ServerLevel.getChunk`, so the
  actual Cube-Net canonical sampler and native seam apron remain authoritative.
- No DH core timing change is required. Its existing level-load event occurs
  before the queue's delayed generator capture, so the Tellus registration is
  visible without changing DH's public event ordering.

Validation used the rebuilt Tellus DH fork jar
`DistantHorizons-fabric-3.3.1-tellus.1-1.20.1.jar` and an isolated server run
with the real `tellus:earth_cube_net` datapack. The log contains:

```
Tellus DH LOD setup dimension=tellus:earth_cube_net generator=CubeNetChunkGenerator generatorMode=FEATURES path=chunk
Registered Tellus Cube-Net DH LOD generator path=chunk
```

There is no `Unknown Chunk Generator detected` or `Distant Generation May Fail`
message. The same run completed all 24 directed face-edge crossings, 14 exact
cut-edge starts, and collision rollback (`passed=true`). The headless run does
not log a real far-distance LOD request because its startup gate requires a
player join; this validates registration and queue construction, not a client
visual or long-distance Cube-Net performance acceptance.
