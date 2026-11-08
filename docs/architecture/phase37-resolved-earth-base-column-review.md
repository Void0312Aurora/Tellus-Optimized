# Round 37: native resolved Earth base-column bridge

## Native source boundary

Earth's getBaseColumn is a base-query path, not the complete surface/decorate pass. Its existing source access and resolveFastColumnHeights remain in EarthChunkGenerator. The returned integer terrain/water heights feed a new private baseColumnInput and materializeResolvedBaseColumn seam. The actual getBaseColumn now delegates its state expansion to that seam.

TellusEarthColumnRules is Minecraft-free and consumes those already resolved integers. It preserves the old shell support bottom, explicit bedrock, shifted deepslate boundary, stone and water-last precedence. It also retains the old non-shell rules for compatibility and independent tests, although the current native settings always use terrain shells. It does not turn raw elevation metres into block height or query any source.

The input range is bounded before layer expansion. The body is emitted as complete inclusive RLE layers, including explicitly resolved AIR below a terrain shell. This is known native void, not an invented fallback for missing terrain. The base query continues to return AIR above its resolved terrain/water and outside its accessor. The semantic materializer does not add sky-clearing writes.

## Resolved semantic path

TellusEarthColumnSnapshot binds the generation and coordinate identities to exact terrain Y, surface, water, biome and strata decisions. TellusEarthColumnDecisionKernel.resolvedColumns consumes this source-resolved snapshot unchanged. It validates request seed and snapshot identity, rejects unavailable snapshots and preserves terminal UNKNOWN/QUEUED before any resolver call. A partial sample or unknown snapshot component remains PARTIAL. Out-of-range resolved heights are rejected rather than rounded or clamped.

An injected snapshot can carry resolved geological materials and final surface decisions. The kernel does not replace these with stone or recalculate the integer height from raw sample metres. The old surface-only constructors remain as a compatibility bridge with their previous generic height/stone behavior; they are not the newly verified native path.

EarthChunkGenerator.semanticBaseColumnKernel is an explicit factory bound to the actual native generator. It requires matching scale, sea level and native generation bounds, reads the same native cover/height resolver as getBaseColumn, and converts its real base-column input to a snapshot. Incomplete elevation/water inputs and a native/sample water-presence disagreement return no snapshot. The factory leaves biome UNKNOWN because a base-column query does not classify biomes. No registry, lifecycle or default semantic generation registration was added.

The general snapshot can also accept resolved later-stage surface/strata data from a caller, but the new native factory only covers the native base-query definition. Its existence is not evidence that palette, badlands, strata-surface, ore or decoration stages have been migrated.

## Boundary behavior

The old materializer rejected every bottom-bedrock collision at terrain==minY. Native Earth can legitimately resolve this as one bedrock cell. The guard now permits only an explicitly BEDROCK top with semantic filler depth zero; other top or filler-depth collisions remain rejected.

An unusual non-shell accessor can place generator bedrock above the reported terrain. The shared native query preserves that old ordering, including water overwriting the bedrock if applicable. A semantic snapshot declines an extra dry bedrock above terrain, because ColumnDecision does not represent that extra post-terrain cell; it does not silently raise terrain Y to hide it.

The native settings constrain underground depth to 64..512. Tests use real settings in that range; the one-cell boundary refers to semantic surface depth zero, not a fabricated native setting with zero underground depth.

## Verification method

The new pure suite uses a literal frozen old base-column loop for its expected states and RLE. It covers resolved geology/height passthrough, raw metre disagreement, identity guards, missing/partial/terminal inputs, budgets, shell/classic precedence and the explicit bedrock boundary.

The native parity suite reflects the actual helper used by getBaseColumn and compares every BlockState against those same pre-extraction literal loops. It then passes the same actual native input through snapshot, resolved kernel and materializer and compares the semantic output at every Y. The bounded matrix covers seven valid underground depths, eight accessors, five terrain positions, seven water cases and extra single-cell columns. A source-free factory smoke covers terminal/incomplete inputs and profile rejection.

Final serial selections passed mc1201 203/203 and mc1211 196/196, with zero failures, errors or skips. Each version compared 1,981 complete native columns and 1,981 resolved semantic columns to the frozen old loop with zero differing blocks, including 413 minimum-terrain explicit bedrock tops. Seventeen pure resolved-input tests also passed, as did the previous full surface/spherical/materializer matrices and freshly checked original 13,824/0, 256/0 and biome seven/one-known-difference observations. Machine-readable counters, full suite selection, source hashes and limitations are recorded in docs/validation/round37-resolved-earth-base-column/test-evidence.json.

## Remaining production work

The full native surface pass still owns palette/depth overrides, badlands bands, geological surface columns, thin-shell ores and later decoration. Source-aware Cube/Sphere semantic biome/provider integration and persistent world save/reload remain separate gates. The newly shared native base-query expansion and explicit resolved factory do not enable a default full semantic lifecycle.

No generator Codec or existing save encoding changed. Rollback parent is recovery/tellus-round36-earth-cube-surface at 47c9d928f744b80a08d45325ff717aba4adc0c29. Restore the native base-query extraction, new rules/snapshot/kernel branch, narrow materializer guard and their tests together.
