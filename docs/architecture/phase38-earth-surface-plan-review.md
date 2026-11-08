# Round 38: resolved Earth surface plans and exact semantic overlays

## Production changes

All four native Earth surface entrypoints (thin/full, cursor/section backend) now consume the same prepared surface plan. Existing palette source resolution, thin-shell strata/ore top resolution, full-mode badlands-before-strata source ordering and all resolved state values remain at the native boundary. The optimized section backend retains its bulk fill operations.

TellusEarthSurfaceColumnRules contains the shared primitive branch, clipped span and per-cell material-role decisions. Dry badlands with relief>=3 take precedence over strata; underwater suppresses both; absent palette is a thin-shell top-only operation or a full-mode no-op. Depth includes the top, as in the old native path. The same lowest-Y formulas retain bedrock clipping and thin-shell support clipping, including native minimum-terrain one-cell and full-mode empty spans. A bounded descending iterator preserves the cursor path's write order.

Standard surfaces preserve a separate shallow filler and deepest filler. Sand/sandstone and red-sand/red-sandstone pairs therefore retain shallow sand above the final sandstone cell. Strata retains a separately resolved top (including thin-shell ore), while badlands deliberately takes palette.top rather than the earlier ore candidate. Full state properties remain attached to generic plan materials.

## Semantic boundary

TellusEarthColumnSnapshot.withResolvedSurface overlays exact known cells onto a complete contiguous depth-zero body and normalizes the result to final RLE. It retains generation/coordinate identity and water, adopts the supplied biome, and emits an exact depth-zero final top. No unknown cell or incomplete body receives stone or guessed filler. Duplicate/out-of-body cells and oversized input are rejected before expansion. Explicit final native patches can replace a minY bedrock cell when the native thin-shell operation itself does so; this differs from the general materializer inventing a filler override.

The real explicit native semanticSurfaceColumnKernel consumes caller-resolved body and surface contexts and uses the same private prepareResolvedSurfacePlan as actual native generation. It checks native seed, scale, projection, sea level, generation bounds, supplied body identity and underwater agreement. Missing or unsupported facts return no snapshot; UNKNOWN/QUEUED remain terminal in the shared kernel. The source body must be the real filled terrain body; this factory never substitutes getBaseColumn's base-query definition. Its palette/top/strata context is already resolved, including ocean-beach caches, source overrides, snow/ore and biome facts. It performs no second palette/source approximation.

A common native semantic material mapper now serves both version adapters and reverse state conversion. The actual 41-value original enum prefix remains unchanged. Thirteen materials were appended: brown/yellow terracotta, dripstone and ten ordinary/deepslate surface ore states. The mapper preserves 53 exact vanilla default states. Non-default properties and foreign blocks return no reverse mapping, so the semantic path stops that column instead of erasing properties; the actual native writer still preserves those states exactly.

## Verification

The independent oracle was captured from the original four dispatch/loop implementations before extraction. It transcribes old branch priority, layer loops, clipping and literal material mappings, using the unchanged native depth/noise/geology sampling functions only as source boundaries. It drives the actual prepared native plan and both real ProtoChunk write backends, including section acquisition/release and untouched neighbors.

The bounded native matrix contains 2,622 cases across all modes, two real underground depths, three world positions, empty spans, shallow/deep sand, geology, all supplied ore tops, all seven badlands colors, minimum-Y behavior and non-default properties. Of these, 2,190 cases traverse exact semantic mapping, snapshot overlay, resolved kernel and materializer; 108 native non-default-state cases also demonstrate why reverse mapping must decline. Additional suites cover 14 pure plan/overlay contracts, nine exact-mapping contracts and five real native-kernel-factory contracts.

Final serial selections passed mc1201 232/232 and mc1211 225/225, with zero failures, errors or skips. Every new matrix above and all prior Earth/base/Cube/spherical/materializer matrices passed; original probes freshly retained 13,824/0, 256/0 and seven biome cases with only the known bare desert/stony_peaks difference. Executed stdout counters, suite lists and source SHA-256 values are recorded in docs/validation/round38-earth-surface-plans/test-evidence.json.

## Gates and rollback

This phase moves actual native surface rule expansion and both write backends onto one plan and verifies its explicit semantic boundary. A complete real-world filled-body/source-context producer, later hollow/snow/cave/decorate stages, richer Cube/Sphere provider/biome integration and durable save/reload remain gates. No default full semantic lifecycle or runtime registration was enabled. This JUnit/ProtoChunk evidence is not live world/server acceptance.

No generator Codec or existing vanilla save encoding changed. Native palette selection thresholds/salts are unchanged; old semantic material ordinals retain their prefix. Restore the native plan extraction, rules, snapshot overlay, exact common mapper, both version delegates and tests together. Rollback parent: recovery/tellus-round37-resolved-earth-base at 78bda59df6901967177e5abeb11d1dca392f6a2e.
