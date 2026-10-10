# Headless Tellus chunk benchmark

The GUI client is not part of the generation benchmark. A benchmark server is
started with `-Dtellus.benchmark.enabled=true`; once the server prints its ready
marker, the runner sends a server-console command:

```text
tellus benchmark <chunkX> <chunkZ> <radius>
```

The command synchronously requests `ChunkStatus.FULL` for the square
`(2 * radius + 1)^2` around the center chunk. It records the total elapsed time,
per-chunk minimum and maximum, requested/completed counts, and the active Tellus
parallel settings as JSON under `run/tellus/benchmarks/`. With
`-Dtellus.benchmark.exit=true`, the server stops after writing the result.
The command is not available to normal users unless the benchmark property is
explicitly enabled.

The batch runner is:

```text
python3 tools/run_headless_benchmark.py \
  --world-template /path/to/Tellus-world-template \
  --cache-template /path/to/prewarmed/tellus/cache \
  --chunk-x 54914 --chunk-z -360480 --radius 1 \
  --workers 1 --heap 8g --label serial-r1
```

Run the parallel candidate against the same world and the same cache snapshot:

```text
python3 tools/run_headless_benchmark.py \
  --world-template /path/to/Tellus-world-template \
  --cache-template /path/to/prewarmed/tellus/cache \
  --chunk-x 54914 --chunk-z -360480 --radius 1 \
  --workers 44 --parallel --heap 8g --label parallel44-r1
```

The script copies the world template, clears the previous Tellus cache by
default, copies the supplied cache template, starts `:mc1201:runServer`, sends
the command over standard input, captures the server log, and writes the Git revision and test settings into
the result JSON. It never modifies the
world template. `--reuse-cache` is available for a deliberate warm-cache run;
it should not be mixed with cold-cache results.

The first local controlled sample used the same template, cache snapshot,
coordinate, heap, and server settings:

| Case | Area | Parallel setting | Elapsed |
|---|---:|---:|---:|
| serial-warm | 1 chunk | off, max 1 | 5,674.1 ms |
| parallel44-warm | 1 chunk | on, max 44 | 6,793.6 ms |
| serial-r1 | 9 chunks | off, max 1 | 8,853.9 ms |
| parallel44-r1 | 9 chunks | on, max 44 | 9,254.0 ms |

These are smoke measurements for the new path, not a claim that the parallel
implementation is faster. They show why the next comparison should use larger
regions and repeated runs while retaining identical cache snapshots. The first
16.4-second versus 5.37-second experiment was discarded because the two runs did
not use the same cache state.

Rendering remains a separate phase. After generation data has been selected, a
client can open one finished world for screenshots under fixed Sodium/Iris,
shader, view-distance, and resource-pack settings.
