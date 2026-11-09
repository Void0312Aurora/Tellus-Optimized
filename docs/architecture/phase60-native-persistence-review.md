# Phase60 — Native save queue and restart probe boundaries

## Explicit native seam

`TellusNativeChunkPersistence` binds the real world root, dimension, seed, active
ServerLevel and exact generator object, registry-aware Codec profile, bounds and
sea level. It rejects noncanonical Cube-Net/apron owners, stale bindings,
non-main-thread work, coordinate overflow and missing native FULL baselines.
All IO uses the live world's existing ChunkMap/ChunkStorage queue. No second
RegionFile/IOWorker is opened and no NBT format, native Codec or high-Y/apron
persistence mixin is changed.

`readExistingNBT` flushes and reads before any demand chunk load. `snapshot`
accepts only that level's exact loaded LevelChunk and uses native ChunkSerializer.
`commit` preflights a bounded complete native tag, submits, flushes and checks
exact typed whole-NBT equality. Existing status spelling and DataVersion are
retained; explicit native version upgrade uses ordinary server save on a new
world copy before transaction testing. Three fault points exercise complete prior
NBT restoration/readback. Complete Error recovery preserves Throwable identity;
incomplete recovery reports original cause plus suppressed failure and places the
physical world/dimension/chunk in process-local quarantine, including new adapter
sessions. There is no public quarantine reset.

This is **not** a write-ahead log or process-crash-atomic region transaction. The
quarantine is not durable across JVM exit. External native-engine saves still
require caller main-thread/quiescence coordination. General loaded-world light,
entity/tick scheduling rollback is not implemented by this disk seam.

## Typed NBT and tests

The canonical digest sorts compound keys, retains list order, type, arrays and
UTF-16 string code units, and applies node/depth/byte/string bounds before native
recursive copy. Optional exclusions apply only to specified root keys. The
stored native payload is never converted into this digest format.

The21 injectable-store/actual-NBT-object JUnit contracts are synthetic contract
tests, not server or GIS acceptance. An initial negative-zero fixture was invalid:
Minecraft FloatTag.valueOf caches both signed zeros as ZERO. The corrected test
uses1.0f versus Math.nextUp(1.0f), distinct actual native tags. Production digest
code was unchanged. The later malformed-shape test adds11 candidate rejection
cases and a valid typed-empty positive case.

## Isolated live-world probe

Only `tellus.persistence.probe=true` registers the command. A fresh UUID and exact
copied world root must be supplied. Capture first proves an existing native FULL
record, then materializes it; missing chunks fail rather than regenerate. It
changes one existing opaque non-block-entity STONE cell to GOLD_BLOCK, checks all
three publication faults, restores that loaded cell/dirty flag and compares the
complete serialized loaded NBT after every recovery. Final commit keeps the
sentinel in the loaded chunk so ordinary shutdown saves retain the intended state.

The runner observes readiness, capture PASS, normal `stop`, actual process exit,
then launches the same copied world/UUID and performs verify. Verification reads
existing native NBT before demand load, compares the full persisted payload, then
compares all decoded block cells, every biome quart cell and full reserialized
native metadata after materialized reload. Only root LastUpdate and InhabitedTime
save clocks are excluded. Sections/palettes, ticks, lights, markers, heightmaps,
structures and unknown native fields remain significant. Any differing root keys
are reported as a failed gate, not silently normalized away.

Historical dataset origin is corroborated manually by the specified reports/logs;
runner-provided origin strings and provenance-file hashes are not a machine proof
of geographic origin. Current-build real-world save/restart execution and source
world/cache immutability are separate recorded gates. Loading an existing real-GIS
world does not establish new current-build GIS generation coverage.

## Unclosed objective gates

Even a passing isolated restart probe does not enable default runtime lifecycle.
Crash-durable journal/recovery, persistent quarantine, world-key aliases across
other engine writers, complete biome/terrain/cave/snow/decoration transactions,
Earth later-stage production integration and the known nondefault Cube sea-offset
Codec drift remain separate gates. Normal hooks stay unchanged and opt-in.
