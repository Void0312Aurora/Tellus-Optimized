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

The model is deliberately not wired into block collision or the existing
terrain coordinate path yet. That keeps current worlds byte-compatible while
we validate chart continuity and error bounds. The next integration point is a
chart-aware provider request: terrain, roads, buildings, and distant-horizon
LOD can ask for the same `ChartKey` and use the same local coordinate frame.

A practical starting configuration at scale 1 is a 1024-block chart and
third-order expansion. `GlobalMapModelTest` checks negative-coordinate chart
boundaries, projection round trips, error reduction with order, and the safe
footprint calculation. These tests are geometry checks; they do not claim that
Minecraft has spherical collision or a wrapped world.
