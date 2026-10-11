# Physical-core chunk generation experiment

This branch experiments with using the host's physical CPU cores for the CPU-heavy part of Tellus chunk generation. It does not change the terrain rules or the Minecraft chunk format.

## First stage: independent solid-section fills

When the fast full-chunk path is active, fully solid sections are independent until the final section-count recalculation. The branch can submit one task per eligible section to a bounded Tellus chunk-compute executor and waits for all section tasks before column-level surface work starts.

The experiment is opt-in:

```text
-Dtellus.chunkgen.parallelSolidSections=true
-Dtellus.chunkgen.threads.max=44
```

The default worker target is half of `Runtime.availableProcessors()` on machines that report an even logical CPU count of at least four. This gives 44 workers on the current 44-core/88-thread host, while allowing an explicit override for other machines.

Relevant controls:

- `tellus.chunkgen.parallelSolidSections`: enable the parallel section-fill experiment; default `false`.
- `tellus.chunkgen.threads.min`: minimum chunk-compute workers; default `1`.
- `tellus.chunkgen.threads.max`: maximum chunk-compute workers; default is the physical-core heuristic described above.
- `tellus.chunkgen.threads`: legacy alias for the maximum worker count.
- `tellus.chunkgen.queue`: optional bounded queue; default `0` uses direct handoff so the pool can grow toward the configured physical-core target; positive values use an `ArrayBlockingQueue`.
- `tellus.chunkgen.parallelSolidSections.minSections`: minimum candidate section count before parallel submission; default `8`.

The executor is separate from the prefetch and deferred-detail pools. A rejected task runs inline so generation remains correct when the bounded queue is full. The same section is never written by two tasks; the `ChunkSectionWriter` only releases and recalculates sections after all section tasks have joined. The water-region experiment uses the same executor, but caps row ranges per region because source/cache contention can outweigh parallel arithmetic.

## Validation required before enabling by default

The first implementation must be compared against the serial path with the same seed, settings, coordinates, and data-cache state. Record:

- total `fillFromNoise` time;
- `FILL_BLOCKS_SOLID_SECTIONS` and `sectionWrite` timings;
- generated block-state hashes for every section;
- dirty-section count and section block counts;
- CPU busy time per physical core;
- RSS and GC time.

Sweep `1`, `8`, `16`, `22`, `32`, and `44` chunk-compute workers. A higher CPU percentage is not sufficient evidence of an improvement: the candidate must preserve block-state output and reduce p50/p95 generation latency or increase completed chunks per second.

A bounded water-region row pipeline is now available behind `tellus.water.parallelRegionBuild=true`, with `tellus.water.parallelRegionBuild.tasks` controlling the per-region cap. Ocean/coast macro tiles use single-flight construction, and cache generations prevent an in-flight build from repopulating a cache after a clear. This path remains opt-in until a broader equality and cold/warm benchmark matrix is collected.

## Algorithmic water-region optimization

The expensive cold path was not solid-section filling. At scale 1, a normal 64×64 water region had been expanded by the waterfall marker query radius and could allocate a working grid larger than one million cells. The query halo is now kept for OSM feature lookup, while the per-cell analysis grid is bounded by the shoreline and flow context. At scale 1 the tested working margin is 192 blocks, and the waterfall lookup margin remains separate.

OSM-water terrain sampling now has a region path. It precomputes the projected X/Z coordinates, loads the small set of Mapterhorn tiles touched by the rectangle once, and reuses those rasters for bilinear interpolation. Preload packages have a matching grid sampler that avoids a registry lookup and cover-class transition calculation for every terrain-height cell. A thread-local scratch buffer reuses the large temporary arrays between region builds.

The path is controlled by `tellus.water.batchTerrainGrid`, which defaults to `true`. Set it to `false` to reproduce the scalar path for an A/B comparison. The fallback remains scalar when a preload package only partially covers the region, so a partially covered region does not silently change its source precedence.

In the same warm cache and coordinate `(55400, -360900)`, the target water region measured about 291 ms on the scalar path and 106–157 ms on the batched path across repeated runs. The batch diagnostic reported 200,704 primary samples and zero per-cell fallback samples for that region. With `tellus.debugWaterChecksum=true`, the batch and scalar paths produced the same region checksum (`-809781633`) at the tested coordinate. On the final commit, a 25-chunk radius-2 run measured 3.105 s with the 44-worker configuration and 3.523 s with one chunk-compute worker; the executor reached 25 live workers in that run. This is an 11.9% end-to-end improvement for this sequential benchmark command, not evidence that all 44 physical cores are saturated.

The benchmark now has an optional output check that hashes every block state in
each completed chunk after the generation timer stops. In the fixed-cache
25-chunk comparison at `(55400, -360900)`, the serial path (`workers=1`, no
parallel flags) took `3520.584 ms`; the 44-worker path with solid-section and
water-region experiments took `3234.779 ms`, an `8.12%` lower elapsed time in
that sample. Both runs produced the same aggregate hash
`5233117473582585816` and all 25 per-chunk hashes matched. The parallel run's
largest observed Tellus pool was 32 and it completed 876 chunk-compute tasks.
This validates output equality for the tested snapshot; it is one fixed-cache
sample, not a claim of stable 44-core saturation or a general speedup.

The concurrent-request probe on the same fixed snapshot completed in
`3400.363 ms` versus `3234.779 ms` for the sequential request loop (about
`5.12%` slower), while producing the same aggregate and per-chunk hashes. The
shared chunk/source path therefore remains a contention point; submitting more
requests at once does not by itself expose more useful CPU parallelism.

A separate whole-chunk pipeline experiment submitted each `fillFromNoise` task
for a 25-chunk request set to a dedicated 44-worker executor. The executor did
reach 44 active workers, and the generated block-state hashes still matched the
ordinary 44-worker path exactly, but elapsed time was `3687.633 ms` versus
`3234.779 ms` for the existing path, about `14.0%` slower. The experiment was
removed after measurement. This rules out whole-chunk request fan-out as a
standalone speedup; shared terrain/source access and the block-writing phases
need algorithmic changes before more CPU parallelism can help.
