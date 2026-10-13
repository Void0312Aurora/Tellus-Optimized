# Singular-cap baseline experiment, 2026-10-13

## Material Passport

- Task: RFC #1 capped quadrilateral lattice feasibility.
- Type: deterministic code experiment; research-only baseline.
- Inputs: current equiangular and spherified embeddings; unrotated cube;
  spherical Earth radius 6,371,008.8 m; nominal `worldScale=1.0` reference.
- Outputs: [raw CSV](cap-radius-baseline.csv), enclosing-cap policy and focused
  regression suite. No external coastline, population, or terrain data used.
- Limit: no conformal-map implementation, exact curved-cell area integral,
  overset aliases, Minecraft collision, or client acceptance.

## Reproduce

From the repository root with JDK 21 and JUnit Console Standalone 1.10.2:

```powershell
./tools/run-spherical-incision-study.ps1 -OutputCsv docs/research/cap-radius-baseline.csv
```

Use `-JunitJar <path>` if the jar is outside the local
`build/rfc-junit` directory. The runner compiles all five required RFC sources,
four test classes, and the study into a fresh build directory; it aborts on a
compilation, test, or study failure. It does not use old compiled classes or
download dependencies. Default output goes under `build/rfc-incision` so a
routine rerun does not overwrite the published record.

A second fresh compilation and run reproduced all 16 CSV rows byte for byte,
verified by comparing SHA-256 of the recorded and reproduced files.

The controlled variables are mapping, subdivision (64/128), cap radius
(0/100/300/360 km), and fixed 50 km transition width. There are 24,576 or
98,304 surface cells; their nominal edges are 156.368 or 78.184 km. These are
coarse full-globe experiments, not one-metre gameplay meshes. Rotation would
leave these geometric metrics invariant; the eight user-proposed geographic
coordinates have not been applied to a coastline/coverage study here.

## Results

At subdivision 128 with a 300 km cap and 50 km transition band:

| Mapping | Ordinary polygon area | Transition polygon area | Excluded polygon area | Ordinary edge scale | Max right-angle error |
|---|---:|---:|---:|---:|---:|
| Equiangular | 99.2242% | 0.0887% | 0.6871% | 0.707125–1.000000 | 27.5074° |
| Spherified | 99.1505% | 0.1344% | 0.7151% | 0.735115–1.263495 | 23.9801° |

The ordinary-only report counts each incident edge once and no extraordinary
cell. Area is computed from great-circle polygons through cell corners; it
is not the exact integral of the curved mapped footprint. The eight ideal
nonoverlapping 300 km spherical caps occupy 0.4434% of Earth. Excluded polygon
area is larger because whole cells are conservatively quarantined, including
cells whose enclosing cap intersects the excluded cap. The difference must
not be described as additional physical land loss without a geographic study.

Resolution materially affects a coarse cap report. For a 300 km cap, going
from 64 to 128 reduces excluded polygon area from 0.7980% to 0.6871% for
equiangular and 0.7828% to 0.7151% for spherified. Maximum enclosure radius
falls from 126.641 to 63.577 km or 135.139 to 67.492 km respectively. This is
two-resolution evidence of discretization error, not a convergence proof.

Ordinary-region angle errors remain substantial at every swept radius.
With a 360 km cap at subdivision 128 they are still 27.0815° and 23.1279°.
Likewise, corner cuts do not improve the worst ordinary edge scales in these
fixtures. The earlier subdivision-5 spherified maximum edge scale of 1.094868
is a low-resolution measurement; at subdivision 128 it is 1.263495. It must
not be used as a bound on fine-resolution block geometry.

## Validation and interpretation

The fresh standalone suite has 31 passing checks: 10 new incision checks and
21 existing topology/seam/lattice checks. Whole-cell enclosure derivations are
checked against curved-edge/interior samples for both mappings. Ordinary
footprints are sampled against all eight transition bands; ordinary traversal
rejects excluded/transition sources and destinations and preserves reciprocal
ordinary face seams. Generation-order reversal produces exactly the same
classification evidence after canonical vertex-use selection.

`:mc1201:compileJava --offline --no-daemon` also passes. Standalone checks and
Fabric source compilation are separate evidence; no full Gradle test or
player-visible collision/rendering pass is claimed.

This experiment establishes an executable quarantine and measurement gate.
It does not show that a 300 or 360 km cap is acceptable for the proposed ocean
locations. Some coarse policies have no transition cells between ordinary and
excluded cells; the traversal gate rejects that crossing rather than pretending
to provide continuous movement. The policy currently enumerates a bounded
research topology and is not a demand-driven full-Earth index.

The next geometry gate is a reference conformal mapping with its own enclosure
and derivative metrics, followed by the same radius/resolution sweep. Acceptance
requires bounded physical scale outside the cap and independent geographic
coverage scoring. Overset canonical identity and client collision remain
separate integration gates.
