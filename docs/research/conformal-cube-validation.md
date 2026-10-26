# Capped conformal cube experiment, 2026-10-13

## Status and scope

The RFC now has an executable, source-traceable conformal cube candidate with
forward mapping, an inverse, local scale, whole-cell enclosing caps, seam
checks, and exclusion-radius sweeps. The geometry results support continuing
the finite-cap design. They do not establish that all twelve world requirements
are satisfied or select a production world format.

This experiment uses a spherical Earth of radius 6,371,008.8 m and the existing
six-face topology. It is unrotated: the eight geographic points supplied by the
user have not been fitted or assessed against coastlines. No DEM, OSM,
population, or land-mask data enters these results.

Outputs: [coarse cap sweep](conformal-cap-sweep.csv),
[local metre derivative samples](conformal-local-metrics.csv),
[grid version](conformal-grid-version.txt), and
[source-derived fixtures](../../tools/conformal/reference-fixtures.csv).
Code and reproduction instructions are in
[tools/conformal](../../tools/conformal/README.md).

## Source and implementation

The primary reference is the CSIRO CCAM
[`jimcc.f90` implementation](https://github.com/csiro/ccam-ccam/blob/02295ffb6decf764b7bf2ce518c412975ae68565/main/grid/jimcc.f90),
pinned to commit `02295ffb6decf764b7bf2ce518c412975ae68565` and SHA-256
`99cdf7d5caf75ad1712c31e6de9f99e89643ba523e03b56c5e191b7ffaf8862b`.
Its `vmtoc` and `mtocd` routines supply the forward series and derivatives;
`jimcc` explicitly imposes eightfold symmetry. Tellus retains the 30 printed
coefficients, uses binary64, expresses the rotations directly in the existing
face bases, and adds an inverse and an enclosure. It omits CCAM's tiny-argument
zero cutoff, so very small positive corner distances remain measurable.

This is a numerical reference approximation, not a claim to reproduce the full
CCAM program or an exact infinite-series conformal map. Four independent
70-digit evaluations of the source formula agree with Java Cartesian output
within 2e-15 per component. The high-precision generator reads the verified
upstream coefficients and sums powers directly rather than sharing Java's
Horner recurrence.

The CCAM adaptation and experiment live under `tools/conformal`, carry
GPL-3.0-or-later notices, and include the upstream license. Main Java sources
only provide a generic `SurfaceMapping` extension to the existing measurement
harness. The candidate is outside the Fabric source sets and has no game-runtime
registration. No production save-format or mapping selection is changed.

## Map and corner behavior

Face coordinates are `(u,v)` in `[-1,1]^2`. Fold to the nearest corner using
`x=(1-|u|)/2`, `y=(1-|v|)/2` and swap to obtain `0 <= y <= x <= 1/2`.
For `zeta=x+i y`, evaluate:

```text
z = zeta^4
P(z) = sum[n=0..29] a[n] z^(n+1)
w = exp(i*pi/6)/sqrt(2) * (-i*P(z))^(1/3)
(S,T,H) = (2*Re(w), 2*Im(w), 1-|w|^2) / (1+|w|^2)
```

Rotate from this corner-centered stereographic frame into the face frame with
orthonormal columns `(1,2,-1)/sqrt(6)`, `(1,0,1)/sqrt(2)`,
`(1,-1,-1)/sqrt(3)`; then restore the swap and signs. At exact symmetry axes,
zero the corresponding component; at the diagonal, average the equal
components. These numerical symmetry operations are explicit in the candidate.
Geographic latitude uses `atan2(y,hypot(x,z))`; `asin(y)` loses the precision
needed for metre derivatives close to geographic poles.

The analytic octant derivative is:

```text
dw/dzeta = (4/3) * w * zeta^3 * P'(z) / P(z)
localScale = (4/pi) * |dw/dzeta| / (1+|w|^2)
```

Scale is relative to the same equiangular nominal reference as the baseline,
`pi*R/4` metres per unit of `u` or `v`; it is not renormalized to this
candidate's center. The center scale is 1.035610393. Close to a vertex,
`w ~ constant*zeta^(4/3)` and local scale is proportional to
`|zeta|^(1/3)`, tending to zero. Removing the eight points alone therefore
does not give a positive lower scale bound on the accessible region. A finite
excluded neighborhood and a specified ordinary-region boundary are needed.

The inverse chooses the lowest numbered incident face on a boundary and uses
safeguarded Newton iteration on the local Cartesian ratios. Its derivative
stencil shrinks near corners, where a fixed stencil stalled convergence in the
initial check. Failed convergence raises an error. The inverse is a surface
lookup; input elevation does not change its face coordinates or supply a
vertical block index.

## Whole-cell quarantine

The incision policy needs a bound for the entire curved cell, not just its
corners. Write `P(z)=z Q(z)`; since `|z| <= 1/4`, coefficient sums give:

```text
Qlower = a[0] - sum[n=1..29] |a[n]|*(1/4)^n > 0
PprimeUpper = sum[n=0..29] (n+1)*|a[n]|*(1/4)^n
L = 4/(3*sqrt(2))*(1/sqrt(2))^(1/3)*PprimeUpper/Qlower^(2/3)
  = 1.139020392...  (upper bound on |dw/dzeta|)
```

Split each cell at the center axes and octant diagonals. Each piece is convex
in folded coordinates. Enclose it about an anchor using radius `2*R*L*delta`,
where `delta` bounds its folded coordinate distance and inverse stereographic
length has factor at most two. Add the anchor-to-cell-center spherical distance
and take the maximum across pieces. An extra 0.1 mm accommodates symmetry
snaps and floating-point arithmetic. Independent piece anchors avoid assuming
the numerical fold glue is exact.

This is an analytic conservative construction evaluated in ordinary floating
point with a numerical allowance, not a machine-checked interval proof of that
allowance. Tests sample all edges/interiors and folds at subdivisions 1, 3, 7,
and 17, and independently sample ordinary-cell clearance at subdivision 24.
All pass. Formal outward-rounded bounds remain a possible strengthening if
this candidate advances to production.

Classification retains `max(0,centerDistance-enclosureRadius)`. A cell is
ordinary only when this exceeds the cap plus transition width and it is not
incident to a degree-three vertex. Excluded and transition cells reject ordinary
materialization/traversal through the existing prototype gates. This still
requires an actual boundary and collision implementation before gameplay.

## Protocol and numerical gates

These thresholds are provisional implementation checks, not user-approved
gameplay acceptance thresholds or geographic loss budgets.

| Check | Provisional threshold | Evidence |
|---|---:|---|
| Independent source fixtures | 2e-15 per Cartesian component | Four high-precision fixtures |
| Shared edge position mismatch | < 1e-7 m | Every edge, 65 samples, n=5/7 |
| Surface inverse mismatch | < 1e-6 m | All-face axes/diagonals/seams/corners and fixed-seed samples |
| Seam inward tangent directions | unit-vector sum < 1e-6 | All twelve seams, seven edge midpoints each, both directions |
| Seam tangent length ratio | error < 1e-6 | Same frame checks |
| Local angle error | < 1e-4 degrees | Metre/half-metre differences, including symmetry axes and face edges |
| Local tangent aspect | < 1+1e-5 | Same derivative samples |
| Relative analytic scale error | < 1e-5 | Independent differences versus series derivative |
| Cell enclosure | All sampled points inside bound | Curved edges, interiors, and folds |
| Ordinary clearance | > cap+transition | Sampled cell footprints and policy gate |

The full-globe bounded mesh uses subdivisions 64/128, cap radii
0/100/300/360 km, and a fixed 50 km transition width. These meshes have nominal
156.368/78.184 km edges; they are not metre voxel grids.

The separate local experiment uses a 65x65 grid on each face plus 181 rays
from a symmetry-equivalent corner of each face, locating points 1 m outside
the cap-plus-transition boundary by bisection. It measures derivatives at
1 m and 0.5 m reference coordinate steps, with second-order one-sided
differences at face edges. This produces 25,692–26,364 points per radius.
Every 64th point is inverted. Extrema below are sample extrema, not rigorous
global optimization or a global lower-bound certificate. No global metre mesh
is allocated and no geographic orientation is optimized.

## Results

All 49 focused tests pass. The study uses a cell-local derivative bound in its
whole-cell enclosure; the public global bound remains available as a reference
gate. The Fabric
MC 1.20.1 `compileJava` task passes offline on Java 21. This is not a complete
Gradle test run, a server/client playtest, or a collision acceptance result.

For the n=128 mesh and 300 km cap plus 50 km transition band:

| Map | Ordinary corner-polygon area | Ordinary edge scale | Maximum polygon angle error |
|---|---:|---:|---:|
| Equiangular baseline | 99.2242% | 0.707125–1.000000 | 27.5074 degrees |
| Spherified baseline | 99.1505% | 0.735115–1.263495 | 23.9801 degrees |
| Conformal candidate | 99.2441% | 0.594445–1.035596 | 1.2078 degrees |

The conformal candidate's finite corner polygons still have angle error;
their edges represent roughly 78 km great-circle arcs. That metric is distinct
from local differential orthogonality. Polygon areas use great-circle corner
polygons, not integration over the mapped curved-cell boundaries. The
The conformal candidate's enclosure now uses a cell-local derivative bound
instead of applying one global octant bound to every cell. This reduces
conservative padding while retaining the whole-cell quarantine rule. The
remaining bound is still analytic with a floating-point allowance, not a
machine-checked interval proof.

For the local derivative study, the table gives the worse angular error over
both difference steps, and scales rounded to six decimals:

| Cap + transition | Minimum sampled scale | Maximum sampled scale | Maximum local angle error |
|---|---:|---:|---:|
| 0 + 50 km | 0.361199 | 1.035610 | 0.00000173 degrees |
| 100 + 50 km | 0.475309 | 1.035610 | 0.00000155 degrees |
| 300 + 50 km | 0.587110 | 1.035610 | 0.00000155 degrees |
| 360 + 50 km | 0.610637 | 1.035610 | 0.00000155 degrees |

At 300+50 km, maximum sampled aspect is 1.000000020446 and relative scale
disagreement is 2.07e-8. The 404 inverse study samples have at most 1.70e-8 m
physical mismatch. The all-edge n=7 seam check passes the 1e-7 m gate. The near-zero
angular error supports local orthogonality, while the substantial scale range
still needs a gameplay distortion budget. A nominal 1 m coordinate step can
represent approximately 0.587–1.036 m in these ordinary-region samples.

The ideal eight 300 km caps occupy 0.4434% of the spherical area. Actual
quarantined corner-polygon area at n=128 is 0.7559%, including the 50 km band
and conservative whole-cell padding. Neither percentage proves that the lost
region is low-value or avoids land.

## Next gates

The first geographic follow-up is now available in
[the eight-vertex validation record](eight-vertex-geographic-validation.md).
It fits the proposed rotation, checks full/high GSHHG coastlines, measures actual
coarse-cell quarantine, and scores a bounded OSM probe. The 300+50 km setting
affects represented islands and observed settlements, so its low-value loss
assumption has not passed. Tight cell bounds, geographic loss budgets, and real
gameplay acceptance remain open.

1. Completed as a first bounded study: fit one rigid cube rotation to the eight proposed geographic vertices,
   report fit residuals and pairwise cube constraints, then measure cap/band
   overlap with actual coastline and high-value geographic data. The proposed
   nearest-land distances alone do not certify whole-cell exclusion clearance.
2. Agree a physical scale range and geographic loss budget, and tighten cell
   enclosures or certify scale minima around the chosen ordinary boundary.
3. Version mapping/orientation alongside block identity before editing or
   persisting candidate worlds; unchanged face/i/j names are not sufficient
   when the physical embedding changes.
4. Implement the smallest real Fabric seam/boundary scene with authoritative
   block edits, reload, motion, and collision checks. The current six-face
   geometry and reciprocal topology are prerequisites, not that acceptance.
5. Validate radial block volumes, global elevation, demand generation, and
   DEM/OSM content on this geometry. The present implementation is a 2D surface
   study and does not demonstrate the -11 km to +8 km block interval.
