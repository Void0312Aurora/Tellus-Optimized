# Geodetic data path

The spherical world kernel owns the Minecraft geometry. A surface sample is a
`SphereWorldModel.GeoPoint` with latitude, longitude, and elevation; it is not
converted to a projected Minecraft block coordinate before external data is
looked up.

`TellusElevationSource` now exposes geodetic overloads for terrain and resolved
terrain sampling. They select a source zoom from the physical metres-per-block
scale, then pass the point directly to `WebMercatorTileMath`. Web Mercator is
used only to address the Mapterhorn/OpenWaters raster services. Tile pixels are
bilinearly sampled across tile boundaries, wrapping the x index at the
antimeridian. Blocking, local-cache-only, and memory-only lookups share this
sampling logic and preserve the existing provider fallback decisions. Missing
or unsupported samples remain unavailable (`NaN`), rather than becoming sea
level.

`TellusLandCoverSource` has matching geodetic entry points for the categorical
WorldCover/Overture path. Its visual transition noise uses a continuous
three-dimensional Earth-centred field derived from the geodetic point. This
avoids a longitude seam and retains variation in both surface directions at
the equator; it does not use the legacy `EarthProjection` block conversion.

`TellusOsmBuildingSource`, `TellusOsmRoadSource`, and `TellusOsmWaterSource`
accept `GeodeticBounds`. Date-line-crossing bounds split into two source-query
windows; returned geometry and metadata stay in their original geodetic form.
The readers reuse their existing tile caches and query modes. The road entry
point currently returns centerlines, not the separate road-area overlay.

`SphereChunkSampler` joins the geometry and readers. One terrain request samples
the 256 block centers of one addressed chunk through its chart's truncated map.
Vector queries use a conservative spherical cap around that chunk. The shared
source factory is `TellusWorldgenSources.sphericalSampler(model)`; no world-wide
pre-generation is required.

The previous block-coordinate overloads remain the compatibility path for the
current generator. They still use `EarthProjection`, so this change does not
silently switch existing worlds to spherical coordinates. The spherical
Fabric 1.20.1 face prototype now calls the sampler through
`SphereFaceChunkGenerator` and `SphereTerrainMaterializer`. This entry point
fills Minecraft terrain blocks and heightmaps. Buildings and roads remain
geodetic feature queries, and chunk loading, player movement, collision, and
rendering across faces still require engine integration.

`TellusLandMaskSource` also accepts geodetic points. Sea-level water requires
known ocean membership rather than inferring it from cover class 80 or a
bathymetry sample. Unknown masks and unresolved inland water levels do not
invent an ocean surface.

The normalized elevation cache is not used by geodetic overloads. Its key space
is currently projected block coordinates; enabling it for spherical points
would alias distinct locations. A later cache-key migration must use a face,
chart, or canonical latitude/longitude key before that optimization is enabled
on the new path. The projected downsample grid is also excluded; geodetic
samples select the source zoom directly instead.

Spherical geometry is valid at the poles, but the current Web-Mercator DEM and
OSM services cover only approximately +/-85.05 degrees latitude. WorldCover
has its own narrower coverage limits. Clipping a source query never makes its
unsupported portion complete; new polar datasets are still needed for actual
global content.

## Validation

The Fabric 1.20.1 target compiles against the real Minecraft APIs. Selecting
that target avoids configuring the unrelated Forge plugin:

```sh
./gradlew -PtellusTargets=mc1201 :mc1201:check --offline --no-daemon
```

The full Fabric 1.20.1 build completed with 621 passing tests in the default
test JVM and six in the parallel-fill JVM, for 627 passing tests total, with
three skips and zero failures.
Two skips select the opposite executor configuration; the third is the opt-in
official WorldCover HTTP-range test.

The focused tests cover five geographical coordinates, scale=1 agreement with
the legacy interior DEM interpolation, date-line interpolation and vector
queries, bathymetry/fallback selection, polar missing-data status, chunk query
envelopes at face edges, sub-metre chart round trips, and continuous cover noise.
Raster and vector fixtures are synthetic cached data, and the chunk field is
analytic. These tests verify coordinate/data behavior, not real scenery quality
or gameplay. The face generator adds tests against real Minecraft `ProtoChunk`
blocks and a separate real DEM server smoke test. Other Minecraft targets,
cross-face movement, and global curvature rendering have not been validated.
