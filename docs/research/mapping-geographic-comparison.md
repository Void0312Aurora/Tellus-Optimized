# Mapping geographic comparison

## Scope

This study applies the same fitted cube orientation, subdivision 128, cap
radii, and 50 km transition width to the equiangular, spherified, and
conformal candidate maps. Each mapping exports its own conservative whole-cell
quarantine perimeter. The same pinned GSHHG 2.3.7 coastal masks then measure
the actual represented land envelope intersected by each perimeter.

The result is a comparison of mapping-induced quarantine geometry. It is not a
population census, a complete road/building loss estimate, or a gameplay
acceptance result.

## Reproduce

The pinned geographic study must already have produced the fitted orientation
and local GSHHG cache. Then run:

```powershell
./tools/geography/run-mapping-geographic-study.ps1
```

The result is [mapping-geographic-loss.csv](geography/mapping-geographic-loss.csv).
It contains 192 rows: 3 mappings x 2 GSHHG resolutions x 2 Antarctic coastline
definitions x 8 cap radii x 2 boundary zones.

The ideal spherical cap fraction is deliberately absent from this table. It is
not a land-loss metric. The table reports the mapped surface area and actual
coastal land-envelope overlap separately.

## Initial result

For the full-resolution GSHHG ice-front mask, the quarantined zone, and the
300 km cap plus 50 km transition band:

| mapping | mapped quarantine area | land-envelope overlap |
|---|---:|---:|
| equiangular-cube | 3,956,940.916 km² | 4,979.353 km² |
| spherified-cube | 4,333,093.253 km² | 16,786.136 km² |
| conformal-cube-reference | 3,858,089.858 km² | 4,491.032 km² |

The conformal enclosure was tightened before this comparison using a
cell-local derivative bound. The result therefore measures the mapping and
the current whole-cell policy together; it does not establish that a production
conformal boundary has been proven minimal.

For the selected 300 km excluded cap and 50 km transition band, the conformal
quarantine's full-resolution coastal-land overlap is 4,491.032 km² over
3,858,089.858 km² of mapped quarantine area, or 0.1164% of the quarantined
region. This is a coastline-envelope result, not a complete settlement,
road, building, or gameplay-value audit; those remain open before changing the
radii.
