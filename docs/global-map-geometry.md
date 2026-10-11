# Global map geometry kernel

`GlobalMapModel` is the first executable layer of the global-map design. It
keeps the existing Tellus block projection as the world coordinate system and
adds deterministic local charts on top of it:

- a `ChartKey` is obtained with floor division over projected block coordinates,
  including negative coordinates;
- each chart has a geodetic center and local east/north offsets;
- local globe operations use the spherical exponential map;
- `expansionOrder=1` is the tangent-plane approximation, while higher orders
  retain more Taylor terms of `sin(c)` and `cos(c)`;
- `truncationErrorBoundMeters` and `maxFootprintMeters` provide a numerical
  limit for choosing a chart size or LOD before expanding a region;
- chart data can be requested progressively because the chart key and its
  anchor are deterministic and independent of generation order.

The model is now wired into `EarthChunkGenerator` for spawn projection and
block-to-geodetic conversion. It still does not change Minecraft collision or
wrap the world: terrain, roads, and buildings continue to use the existing
projected block lattice. The generator exposes the shared `GlobalMapModel` so
future providers and distant-horizon LOD can request the same `ChartKey` and
local coordinate frame.

`recommendedExpansionOrder` selects the smallest order that satisfies the
configured truncation tolerance for a footprint, while
`chartHalfDiagonalMeters` supplies the conservative chart footprint estimate.
A practical starting configuration at scale 1 is a 1024-block chart and
third-order expansion. `GlobalMapModelTest` checks negative-coordinate chart
boundaries, projection round trips, error reduction with order, safe footprint
calculation, and chart-based order selection. These tests are geometry checks;
they do not claim that Minecraft has spherical collision or a wrapped world.
