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

For isolated generator tests on Fabric 1.20.1, the runner accepts `--run-dir`,
`--dimension namespace:path`, and `--world-type minecraft:flat`. It executes the
benchmark in that dimension and records the generator class, dimension, Git
revision, and whether the worktree contained uncommitted changes. The explicit
run directory keeps these tests separate from the regular save and cache.

For the six-face runtime smoke check, use a new template with a datapack made by
`tools/create_sphere_datapack.py`, and pass `--sphere-check-planet tellus:earth`
instead of `--chunk-x`/`--chunk-z`. The runner sends `tellus sphere verify` and
collects `sphere-runtime-check-*.json`. This exercises a real engine
`ServerPlayer` with a packet sink and real chunk loading, without a client. It
does not test walking, client rendering or speedup. The normal real-data run
also leaves the target collision probe disabled; an explicit synthetic fixture
run can exercise the target FULL-chunk AABB query and source rollback with:

```text
--jvm-property tellus.sphere.syntheticSampler=true
```

That property is test-only analytic terrain and makes no geographic-data claim.
On Windows the runner selects `gradlew.bat` automatically. Controller timeouts
also apply while the server produces no log lines.

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

Use `--parallel-water` to exercise the opt-in water-region row pipeline, and
`--jvm-property key=value` to pass an additional JVM property. The water path
also accepts `tellus.water.parallelRegionBuild.tasks`; its default caps a single
region at eight row ranges because the source/cache layer becomes slower when
all physical cores contend on the same region. This is an experimental path,
not a default setting.

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

The initial measurements above are smoke checks and do not claim a speedup.
The benchmark JSON also records whether the executor was configured, its largest
observed pool size, and completed chunk-compute tasks. A 44-worker water run
reached a largest pool size of 44 with the direct-handoff queue. On the local
scale-1 sample, uncapped 44-way row fan-out made a target water region slower
(about 4.62 s versus 1.95 s serial); the capped eight-range path measured about
1.75 s for that region while the whole server run stayed roughly flat because
startup and neighboring prefetch regions dominated. These numbers are evidence
for scheduling behavior, not a universal speedup claim.

Rendering remains a separate phase. After generation data has been selected, a
client can open one finished world for screenshots under fixed Sodium/Iris,
shader, view-distance, and resource-pack settings.

For controlled scheduling probes, the runner also supports three diagnostic flags:

```text
--concurrent-requests   submit all FULL-chunk futures before joining them
--area-prefetch         prefetch the complete benchmark rectangle before timing
--state-hash             hash every generated block state after generation timing
```

`--concurrent-requests` measures request overlap, not a production scheduling
policy; it can expose contention in the shared source/cache path and can be
slower than the sequential command. `--area-prefetch` reports its preparation
time separately as `areaPrefetchMillis`; the measured `elapsedMillis` starts
after that preparation. `--state-hash` adds per-chunk `stateHashes` and a
`stateHashAggregate` to the JSON. The block-state hash pass is excluded from
`elapsedMillis`, so it can be used to compare output equality without folding
its cost into the generation timing.

On the fixed scale-1 snapshot used for the physical-core experiment,
`--concurrent-requests` produced the same 25 chunk hashes as the ordinary
request loop but took `3400.363 ms` versus `3234.779 ms` for the 44-worker
parallel configuration. This is why the flag is retained as a measurement
probe rather than enabled by default.

The optional `--chart-probe` flag records the `GlobalMapModel` chart assigned to
each benchmark chunk, the local chart coordinates, the recommended expansion
order, and the truncation error bound. It runs inside the real Tellus benchmark
server, so it verifies chart mapping against the active world projection. It
does not make terrain generation progressive or change the generated blocks.

At the tested scale-1 boundary around chunk X `55424`, a radius-1 run generated
nine chunks across chart X `865` and `866`. The local coordinate changed from
`1016` in chart `865` to `8` in chart `866`; both sides selected expansion order
`1` with a calculated chart error bound of about `0.01986 m`. This is a chart
coordinate seam test, not a progressive terrain provider test.
