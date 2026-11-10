# Phase67 — native nonempty lifecycle payload matrix

## Contract matrix

The native persistence contract now exercises one complete candidate containing
all currently gated nonempty lifecycle payload families:

- one `block_entities` record with id and world coordinates;
- one scheduled `block_ticks` record with block id, position, delay and priority;
- one scheduled `fluid_ticks` record with fluid id, position, delay and priority;
- one `PostProcessing` marker column with ordered short offsets.

The candidate is submitted through the native validation and queue seam, then
compared against the queued disk tag with exact NBT equality and per-family
cardinality assertions. This verifies that validation, copy isolation and
rollback do not silently discard nonempty lifecycle fields.

## Boundary

This is a native-tag contract matrix, not yet a live Minecraft server
save/reload acceptance: the next gate must populate these families through real
loaded-world APIs, stop/restart the server, and compare the resulting native
chunk. Synthetic tags remain excluded from GIS/world persistence evidence.

## Verification

The matrix and complete selected cohorts pass on mc1201 and mc1211. Default
lifecycle registration remains opt-in until the live nonempty-world replay is
complete.
