# Next gate — Native Minecraft world save/reload

This is a verified implementation map, **not a passed persistence gate**. The
Round59 generic `.txn` backend writes transaction records; it neither serializes
Minecraft chunks nor participates in a live world's save ordering.

## Verified native seams

The cached official-mapped Minecraft APIs expose:

| API | mc1201 | mc1211 |
|---|---|---|
| `ChunkSerializer.write` | `(ServerLevel, ChunkAccess) -> CompoundTag` | same |
| `ChunkSerializer.read` | `(ServerLevel, PoiManager, ChunkPos, CompoundTag) -> ProtoChunk` | adds `RegionStorageInfo` before `ChunkPos` |
| `ChunkStorage` construction | `(Path, DataFixer, boolean)` | `(RegionStorageInfo, Path, DataFixer, boolean)` |
| `ChunkStorage.write` | `void` | `CompletableFuture<Void>` |
| Native read/flush | `read(pos) -> CompletableFuture<Optional<CompoundTag>>`, `flushWorker()`, `close()` | same shape |

`ServerChunkCache.chunkMap` is the live server's existing `ChunkStorage` owner.
A live-world test must reuse its IO queue; opening a second `RegionFile` or
`IOWorker` over the same region would violate writer ownership.

Native NBT includes coordinates, inhabited/last-update values, status, sections
and biome palettes, light flags/arrays, block/fluid ticks, post-processing,
heightmaps and structures. Existing high-Y serializer mixins and canonical-only
Cube-Net persistence must remain in effect. Derived apron chunks stay excluded.

## Smallest next verified implementation

1. Add version-local explicit world-bound snapshot/roundtrip adapters, retaining
   the native NBT format and the existing region worker. Bind world identity,
   dimension key, exact chunk coordinates, generator profile and expected status.
2. Admit only an isolated test-world main-thread/quiescent operation. Capture the
   entire original native payload and coordinate the loaded-chunk lifecycle;
   disk NBT readback alone is not a true unload/restart/reload acceptance.
3. Serialize through native `ChunkSerializer`, await existing worker completion
   and flush, stop/restart the isolated server, and compare decoded native blocks,
   biomes, markers, ticks, light, heightmaps, structures and status.
4. Inject failures before publication and during world-bound recovery. Prove
   recovery restores the original valid chunk rather than silently accepting
   regeneration, data loss or a partial region record. Do not truncate user data.
5. Independently verify wrong-world/dimension/seed/profile and apron admission,
   cleanup and rollback. Keep default hooks disabled until these gates pass.

## Test environment discovery

The documented local MC Pilot wrapper is
`D:/workshop/Game/mc-pilot-deployment/mct.cmd`, not the unpacked
`package/bin/mct`. The wrapper reports CLI version `0.20.0`; its `schema` and
historical project `info` were read this round. The historical spherical-cave
server was confirmed stopped and was not started. Its synthetic sampler is not
real GIS/world-persistence evidence. A new isolated test run must use the current
build and record the actual selected client/server versions.

Default lifecycle, real-world recovery, live GIS coverage and the existing
nondefault Cube sea-offset Codec drift remain outstanding gates.
