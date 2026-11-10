# Actual native multi-chunk crash recovery

Both mc1201 and mc1211 pass the same native four-process acceptance on independent copies of the historical Swiss GIS world/cache. Two pre-existing canonical FULL chunks are used: `(616246,-351597)` and `(616247,-351597)`; neither is demand-loaded by the transaction.

1. Prepare the checksummed, file-forced original/proposed native journal; change a real stone palette through ChunkMap; flush and read back the first chunk while the second is untouched; halt the server JVM with exit 86. Independent Python Anvil reads verify that exact partial state.
2. Restart with an explicit copied-world recovery fault after native recovery submission. The server aborts before spawn preparation/readiness and retains a checksum-valid QUARANTINED journal. Minecraft catches this initialization failure and may exit zero; the acceptance uses actual fault logs, no readiness, and durable journal state rather than assuming a nonzero launcher exit.
3. Restart normally. Native startup recovery restores both complete original tags before `prepareLevels`, verifies exact queue readback without excluding save clocks, removes the journal and resumes engine saves.
4. Restart once more. Before any target demand-load, both complete original tags still match. Independent Anvil reads also match after each normal shutdown.

The [validation summary](validation.json) links each original report, complete native original SNBT, compressed unabridged server log, source manifest and serial regression result by SHA-256. The first mc1201 controller incorrectly required a nonzero exit for the deliberately failed startup; that original failure is retained, and recovery resumed on the **same quarantined world** without resetting it. The controller was corrected for the actual native exit behavior before mc1211 acceptance.

Source world/cache are byte-unchanged; Java sources remain unchanged throughout each real acceptance. No synthetic sampler or substitute store is used. Original chunk DataVersion is retained by the disk-only restore; native upgrade/nonempty loaded lifecycle behavior is covered separately by the already accepted Round68 evidence, not rerun here.

Scope: native unloaded two-chunk process-termination recovery, durable failure quarantine and engine queue admission. This is not a power-loss durability claim, active loaded-chunk batch replacement, arbitrary external-writer exclusion or unified runtime default rollout. Default generator/runtime routing remains legacy until the other gates pass.
