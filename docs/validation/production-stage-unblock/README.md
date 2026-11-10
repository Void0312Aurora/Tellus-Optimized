# Production-stage unblock

- Earth's actual projected vanilla-density cave/ore/geology loop now uses a pure decision kernel, retaining native source acquisition, depth projection, replaceability tags, write order and fluid markers.
- Earth and both Cube generators call one shared realtime-snow production sink/kernel. Geographic/weather eligibility stays native; ordered reads/writes, rejected setters, snowy-ground behavior and flags 2/260 match the frozen old body.
- Both Cube encoders persist the original sea-level constructor input. The field schema and decoder are unchanged. Non-default offsets no longer accumulate at each save; previously stored values keep their legacy runtime decode meaning rather than receiving a speculative migration.

The selected serial regressions pass **mc1201 540/540; mc1211 531/531**.
The added native differential test compares all cells, source/write traces,
fluid markers and dirty state across the actual projected-field loop.
Codec tests cover offsets -12/0/+12 and four successive saves of old encodings.

This is not a passed full-generation transaction, new GIS generation run or
non-default-offset world restart. Configured vanilla carvers, decoration plans,
multi-stage ownership, crash recovery and default lifecycle still need integration.
