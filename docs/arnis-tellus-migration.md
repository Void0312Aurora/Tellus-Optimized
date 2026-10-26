# Arnis 3.x migration audit

The local Arnis source audit is against tag `v3.3.0` (`c96872e`) in
`earth_compare/arnis-src`. Tellus already carried a Java port of the older
Arnis facade and road rules, so the useful 3.x work was selected by comparing
source behavior rather than copying files wholesale.

The first migrated piece is `ArnisBuildingVariants`: deterministic high-rise
style selection based on material hints, building proportions, and a stable
blueprint seed. It covers glass, glass-corner, grid, contemporary, modern,
and masonry treatments while preserving Tellus' existing `BuildingStyle` and
material palette. All chunks of one blueprint receive the same result.

Arnis' photograph manifest and Mapillary facade pipeline are intentionally not
copied into Tellus' procedural block generator. They require external image
assets, loading and scaling rules, and a separate data/licensing path. Arnis'
bridge modules likewise depend on bundled schematic assets; Tellus' current
bridge overlay path has different inputs and should be compared separately
before any asset migration.

The migration is therefore incremental: portable deterministic building
selection is in the Java generator now; asset-backed facades and modular bridge
schematics remain separate candidates pending data and output comparisons.
