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
- `tellus.chunkgen.queue`: bounded executor queue; default `1024`.
- `tellus.chunkgen.parallelSolidSections.minSections`: minimum candidate section count before parallel submission; default `8`.

The executor is separate from the prefetch and deferred-detail pools. A rejected task runs inline so generation remains correct when the bounded queue is full. The same section is never written by two tasks; the `ChunkSectionWriter` only releases and recalculates sections after all section tasks have joined.

## Validation required before enabling by default

The first implementation must be compared against the serial path with the same seed, settings, coordinates, and data-cache state. Record:

- total `fillFromNoise` time;
- `FILL_BLOCKS_SOLID_SECTIONS` and `sectionWrite` timings;
- generated block-state hashes for every section;
- dirty-section count and section block counts;
- CPU busy time per physical core;
- RSS and GC time.

Sweep `1`, `8`, `16`, `22`, `32`, and `44` chunk-compute workers. A higher CPU percentage is not sufficient evidence of an improvement: the candidate must preserve block-state output and reduce p50/p95 generation latency or increase completed chunks per second.

The next stage is region-batched terrain preparation. It is intentionally not included in this first change, because it requires a separate equality harness for elevation, cover, and water arrays before changing the normal chunk-generation dependency graph.
