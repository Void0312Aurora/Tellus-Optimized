# Eight-candidate geographic validation

This is an offline research study for the capped conformal-cube RFC. It fits the
eight rounded positions supplied in the conversation, applies one proper rigid
rotation to the Java reference map, measures coast clearance, and intersects
both ideal caps and the actual whole-cell quarantine footprint with GSHHG.
The Minecraft runtime and save format are not integrated with this candidate.

## Inputs and attribution

- `candidate-vertices.csv`: transcription of the user's image, including its
  reported distances as unverified comparison fields. These distances are not
  inputs to fitting or land-loss scoring.
- [GSHHG 2.3.7](https://www.soest.hawaii.edu/pwessel/gshhg/), released 2017-06-15,
  maintained by Paul Wessel and Walter H. F. Smith; LGPL-3.0-or-later. The
  official native-binary archive and its two used files are pinned by SHA-256.
  The cache retains the distributed license and documentation. Dataset binaries
  and large generated boundary files stay under ignored `build/`.
- `osm-nukumanu-snapshot.json`: a frozen, bounded OSM map response reduced to
  relevant place nodes and tagged building/highway ways, preserving object IDs,
  versions, tags and geometry. Data © OpenStreetMap contributors,
  [ODbL 1.0](https://www.openstreetmap.org/copyright). Account/editor fields are
  omitted. This is a limited feature probe, not a population census or a
  complete OSM extract of all eight regions.

## Reproduce

The full runner requires JDK 21, JUnit Console Standalone 1.10.2, Python 3.12,
and the pinned packages. Bootstrap the isolated environment and download data
explicitly from the repository root:

```powershell
python -m venv build/geography/venv
build/geography/venv/Scripts/python.exe -m pip install -r tools/geography/requirements.txt
build/geography/venv/Scripts/python.exe tools/geography/fetch_gshhg.py
```

The GSHHG archive is approximately 119 MB. `fetch_gshhg.py` verifies its recorded
digest and extracts only named members, including the full/high shoreline files
and license. The main experiment makes no network requests:

```powershell
./tools/geography/run-geographic-study.ps1
```

The runner runs 12 Python checks, fits all 40,320 point correspondences, freshly
compiles the Java research sources, runs 48 Java checks, exports actual
quarantine perimeters, evaluates full/high coastlines and both Antarctic
definitions, compares shoreline feature-area policies, scores the frozen OSM
probe, and renders PNG/SVG figures. It aborts
on a failed stage. Defaults write to `build/geography/results`; use a different
`-OutputDirectory` for reproduction. Local data processing can take several
minutes. Geometry checks do not download data.

Regenerating the OSM probe is a separate explicit network action:

```powershell
build/geography/venv/Scripts/python.exe tools/geography/fetch_osm_probe.py --output build/geography/new-osm-snapshot.json
```

That response will represent a new observation and may differ from the committed
snapshot. A normal study always uses the frozen input, so server edits cannot
silently change the recorded experiment.

## Geometry and output interpretation

Distances use the configured spherical Earth radius, not an ellipsoid. Native
unsplit contours avoid artificial dateline shoreline edges. Minimum distance is
to minor great-circle segments rather than only stored vertices. Coordinates
and areas are interpreted in the project's `(Greenwich,north,90E)` convention.

Areas use local spherical Lambert equal-area projections. The land mask unions
level-1 coastal envelopes with Antarctic ice-front (5) or grounding-line (6)
polygons; inland lakes remain included. It is a conservative coastal footprint,
not measured dry land after subtracting every lake. Planar/polar geometry repairs
are recorded by polygon ID. Results are estimates under this dataset and edge
approximation, not a formal guarantee of today's smallest islands.

The coarse mesh is n=128, with roughly 78 km nominal cells. Its actual footprint
can extend substantially past an ideal cap plus transition band. Boundary
exports preserve all directed cycles, including diagonal point contacts and
holes, using even/odd filling; they are closed `LineString` features rather than
individually filled GeoJSON polygons. Sampling each mapped edge 8/32 times
quantifies boundary discretization sensitivity. The source-data resolution
comparison is separate from that numerical comparison.

The archived [study record](../../docs/research/eight-vertex-geographic-validation.md)
links the compact CSV/JSON outputs. The complete approximately 13 MB fine
perimeter export and overlap geometry can be regenerated locally. Population
and global road/building losses stay explicitly unknown; missing observations
are never converted to zero.

`shoreline-feature-thresholds.csv` records three policies: every level-1 land
polygon plus Antarctic ice contours, level-1 polygons at least 10 km² plus
Antarctic ice contours, and the level-1 GSHHG continent records. The 10 km²
cutoff is a sensitivity probe for coarse source maps; it is not a proposed
gameplay rule.
