# Mapping decision baseline

## Scope

This is the first common-harness comparison of the three current geometry
families:

- `equiangular-cube`: gnomonic/equiangular cubed sphere;
- `spherified-cube`: the algebraically spherified cubed sphere;
- `conformal-cube-reference`: the 30-term Rančić/CCAM reference adaptation.

The study is research-only and does not select a Minecraft save format or
register a runtime mapping. It uses the same Earth radius, topology,
subdivision, cap radius, transition width, cell-area calculation, metric
harness, and seam checks for every row.

## Reproduce

From the repository root with JDK 21:

```powershell
./tools/run-mapping-decision-study.ps1
```

The runner performs a fresh compilation and requires 24 rows: 2 resolutions x
3 mappings x 4 cap radii. It rejects any seam mismatch above `1e-7 m`. The
recorded result is [mapping-decision-baseline.csv](mapping-decision-baseline.csv);
the companion local differential result is
[mapping-decision-baseline-local-metrics.csv](mapping-decision-baseline-local-metrics.csv).
The shared grid definition and provisional threshold contract are recorded in
[mapping-decision-baseline-metadata.json](mapping-decision-baseline-metadata.json).
The metadata must travel with any regenerated CSV because the numbers are only
comparable when the Earth radius, topology, subdivisions, cap policy and
threshold status are unchanged.
The default scratch output is `build/rfc-mapping-decision/`.

## What this establishes

The CSV separates finite-cell behavior from local differential claims. The
edge, angle, and area columns describe the ordinary cells admitted outside a
whole-cell conservative quarantine. The conformal candidate's local
orthogonality is covered by its separate derivative study; this common study
does not silently infer local conformality from the finite-cell polygon rows.

The cap is a geometric exclusion policy. Its area fraction is not a land-loss
estimate. Geographic loss must be measured against actual coastline and
feature data after the same orientation and cap policy are applied.

The metadata uses `tellus:earth-cubed-sphere-rfc-1` as the current research
grid version. The thresholds in that file are explicitly **provisional**;
they are numerical gates for reproducibility and do not yet constitute the
player-facing gameplay budget.

## Initial observation

For subdivision 128 with a 300 km excluded radius and a 50 km transition band,
the common finite-cell harness reports:

| mapping | ordinary edge scale | finite-cell angle error | ordinary area scale | excluded polygon fraction |
|---|---:|---:|---:|---:|
| equiangular-cube | 0.707125–1.000000 | 27.5074° | 0.711436–0.999950 | 0.6871% |
| spherified-cube | 0.735115–1.263495 | 23.9801° | 0.766846–0.932388 | 0.7151% |
| conformal-cube-reference | 0.594445–1.035596 | 1.2078° | 0.362804–1.072446 | 0.5633% |

The conformal row has a much smaller finite-cell angle error, while its
conservative cell quarantine and area-scale range are different. These are
measurements for the common mesh policy, not a final gameplay judgment. The
conformal local derivative study remains the source for its differential
orthogonality claim. The companion local study uses the same one-metre
finite-difference step and samples a regular face grid plus radial points at
each cap boundary. It reports local angle error and tangent aspect separately
from the finite-cell table. The conformal whole-cell bound is evaluated with a
cell-local derivative upper bound; this avoids charging every cell with the
worst derivative from the entire octant.

## Decision gate

This baseline is followed by three independent gates:

1. **Geographic value:** weighted land, settlements, roads, buildings, and
   infrastructure loss for the fitted cube orientation and nearby candidates.
2. **Feature shape:** synthetic and real road/building footprints projected
   through each map, measuring width, angle, area, and cross-face continuity.
3. **Gameplay geometry:** an ordinary-region seam scene with movement,
   collision, editing, reload, and globally stable block identity.

Strict conformality is selected only if its cap and scale policy pass the first
two gates. If it does not, the next candidate is a quasi-conformal mapping
optimized for weighted geographic coverage and bounded scale, rather than a
decision made from the existing runtime implementation.
