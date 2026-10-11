# Geodetic data path

The spherical world kernel owns the Minecraft geometry. A surface sample is a
`SphereWorldModel.GeoPoint` with latitude, longitude, and elevation; it is not
converted to a projected Minecraft block coordinate before external data is
looked up.

`TellusElevationSource` now exposes geodetic overloads for terrain and resolved
terrain sampling. They select a source zoom from the physical metres-per-block
scale, then pass the point directly to `WebMercatorTileMath`. Web Mercator is
used only to address the Mapterhorn/OpenWaters raster services. Tile pixels are
still bilinearly sampled and the existing cache-only policy is preserved.

`TellusLandCoverSource` has matching geodetic entry points for the categorical
WorldCover/Overture path. Its visual transition noise uses a deterministic
metre-based Earth-centred horizontal coordinate derived from the geodetic
point; it does not use the legacy `EarthProjection` block coordinate
conversion.

The previous block-coordinate overloads remain the compatibility path for the
current generator. They still use `EarthProjection`, so this change does not
silently switch existing worlds to spherical coordinates. The spherical
generator should call the geodetic overload once its chunk materializer is
ready.

The normalized elevation cache is not used by geodetic overloads. Its key space
is currently projected block coordinates; enabling it for spherical points
would alias distinct locations. A later cache-key migration must use a face,
chart, or canonical latitude/longitude key before that optimization is enabled
on the new path.

## Validation

`WebMercatorTileMathTest` covers the equator, antimeridian wrapping, finite
latitude bounds, and pixel/tile consistency. The new source path was also
compiled against the existing Tellus classes with temporary Minecraft API stubs;
the repository Gradle build remains blocked by the pre-existing unresolved
`xyz.wagyourtail.unimined:1.4.18-kappa` plugin in `mc1201-forge/build.gradle`.
