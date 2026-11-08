# Tellus Coordinate Context and Generator Ownership

> Read-only architecture audit. Scope: `EarthProjection`, `GlobalMapModel`, `SphereWorldModel`, `CubeNetLayout`, `CubeNetAddressAdapter`, `CubeNetApronResolver`, and their current Earth/Cube/Sphere consumers. This document proposes interfaces and migration boundaries; it does not modify source behavior.

## 1. Executive summary

Tellus currently has three coordinate systems that are all represented by untyped `double`/`int` pairs:

1. **Geodetic/spherical coordinates**: `SphereWorldModel.GeoPoint` (`latitude`, `longitude`, `elevationMeters`). This is the canonical surface identity for the cubed sphere and is the correct input to spherical DEM, land-cover, geology, OSM, ocean and feature queries.
2. **Earth compatibility coordinates**: `EarthProjection` block X/Z (`longitudeToBlockX`, `latToBlockZ`, and the inverse functions). This is the historical projected grid consumed by Earth data readers, Earth biome classification, preload packages, landmarks and compatibility code. It is not the owner of a Cube-Net block.
3. **Spherical noise coordinates**: the ECEF-derived, date-line-continuous integer pair currently computed independently by `CubeNetBiomeSource.noiseCoordinates` and `CubeNetSurfacePolicy.canonicalNoiseCoordinates`. This is appropriate for seam-stable deterministic noise, but not for Earth raster lookup.

The Cube-Net planar net is a **view**, not another physical surface. `CubeNetLayout` maps a canonical face cell into the planar Minecraft X/Z atlas; `CubeNetAddressAdapter` resolves ordinary planar chunks to canonical face chunks; `CubeNetApronResolver` maps a cut-edge proxy/apron cell to a canonical target face. An apron must never become a second data owner.

The proposed `TellusCoordinateContext` makes all three coordinate domains explicit and carries a typed owner view. The immediate repair boundary is `CubeNetBiomeSource.getNoiseBiome`: it currently passes `noiseCoordinates` as `blockX/blockZ` into `BiomeClassificationProviders`, whose RESOLVE provider interprets those values as Earth projected blocks. The same untyped pair is also passed to planar relief/snow helpers. This is the root of the current bare-biome mismatch and related Cube-Net/Earth divergence. The fix is to route each consumer through the appropriately typed view, not to change the cubed-sphere mapping or the Earth projection.

## 2. Coordinate vocabulary and authority

### 2.1 `GeoPoint` — canonical geodetic surface identity

Current type: `SphereWorldModel.GeoPoint`.

Semantics:

- Latitude is geodetic degrees, clamped to `[-90, 90]` by the record constructor.
- Longitude is normalized to `(-180, 180]`.
- Elevation is metres relative to the reference sphere/terrain datum; it is not a Minecraft Y value.
- A `GeoPoint` identifies a physical location on the canonical reference sphere. It is independent of the cube face, cube-net placement and Earth Mercator grid.
- It is the only coordinate that should cross into spherical source APIs (`SphereChunkSampler`, `TellusElevationSource` spherical overloads, spherical cover/geology/ocean sources and geodetic OSM bounds).
- It is not a suitable cache key by itself when a source has a resolution; pair it with source version/resolution and the canonical owner address.

Authority: `SphereWorldModel` plus its versioned `SphereSurfaceMapping` (`ConformalCubedSphere.MAP_ID`). `exactSurfacePoint` is the exact mapping; `surfacePoint` is the prepared-chart approximation and must remain tagged by its chart/error contract.

### 2.2 `EarthCompatibilityPoint` — projected Earth data view

Proposed immutable value type:

```java
public record EarthCompatibilityPoint(
   double blockX,
   double blockZ,
   double worldScale,
   String projectionModeId,
   boolean withinMercatorCoverage
) {
   public EarthCompatibilityPoint {
      if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)
         || !(worldScale > 0.0) || !Double.isFinite(worldScale)) {
         throw new IllegalArgumentException("invalid Earth compatibility point");
      }
      Objects.requireNonNull(projectionModeId, "projectionModeId");
   }
}
```

Semantics:

- `blockX = EarthProjection.longitudeToBlockX(geo.longitude(), worldScale)`.
- `blockZ = EarthProjection.latToBlockZ(geo.latitude(), worldScale)` using the active projection mode (`mercator` by default, `legacy` by system property).
- `worldScale` is metres per projected block, not the cubed-sphere nominal scale.
- `withinMercatorCoverage` records whether the source latitude is within `EarthProjection.MAX_MERCATOR_LATITUDE`. A projected point outside that range is a compatibility fallback/miss, not a valid statement that the polar cap owns an Earth-grid cell.
- The type is a bridge to Earth-grid readers. It must not be used to decide Cube-Net face ownership, seam adjacency, canonical spherical neighbors or apron ownership.
- `EarthProjection.METERS_PER_DEGREE` and `SphereWorldModel.EARTH_RADIUS_METERS` intentionally differ. Do not silently substitute one radius for the other; the former is the compatibility grid contract, the latter is the spherical reference surface.

For integer readers, define an explicit conversion (`floor` for cell ownership, or `round` only where the existing API documents nearest-cell behavior). Do not let each caller cast independently.

### 2.3 `SphericalNoisePoint` — seam-stable deterministic noise view

Proposed immutable value type:

```java
public record SphericalNoisePoint(
   double unitX,
   double unitY,
   double unitZ,
   int noiseX,
   int noiseZ,
   String algorithmId
) {
   public SphericalNoisePoint {
      if (!Double.isFinite(unitX) || !Double.isFinite(unitY) || !Double.isFinite(unitZ)) {
         throw new IllegalArgumentException("unit vector must be finite");
      }
      Objects.requireNonNull(algorithmId, "algorithmId");
   }
}
```

Semantics:

- `(unitX, unitY, unitZ)` is the normalized Earth-centred direction derived from `GeoPoint`; it is continuous across the ±180° meridian and cube-face seams.
- `noiseX/noiseZ` are the one canonical integer quantization of that direction. The current formula is:

```text
x = cos(lat) * cos(lon) * 1_000_000 + sin(lat) * 37_000
z = cos(lat) * sin(lon) * 1_000_000 + sin(lat) * 91_000
```

- Choose and document one quantizer. The existing `CubeNetSurfacePolicy.safeCoordinate` uses `Math.round`, while `CubeNetBiomeSource.coordinateForClassification` truncates with `(int) value`; these are not equivalent for negative/fractional values and can produce one-cell differences. The context must eliminate this duplicate implementation.
- This view is valid for deterministic random-biome mixing, cave noise, surface patch noise and other coordinate-hash decisions that must not jump at the date line. It is not an Earth raster coordinate.
- `algorithmId` must include the quantization/version, for example `tellus:spherical-noise-v1-round`; changing it is a world-generation compatibility change.

### 2.4 Face-local and planar view coordinates

Current types:

- `SphereWorldModel.ContinuousFaceCoordinate` / `SphereBlockAddress` / `SphereChunkAddress`: canonical face-local ownership, with block/chunk indices in `[0, faceBlocks)`.
- `CubeNetLayout.FacePoint`: continuous face-local point in `[0, faceSize]` for ordinary use; unbounded values are only for seam/apron derivation.
- `CubeNetLayout.PlanarPoint`: the fixed cube-net atlas X/Z coordinate used by Minecraft chunks and client rendering.

Semantics:

- Face-local coordinates identify the canonical chart and are authoritative for spherical address conversion.
- Planar X/Z identifies a **view location** in the fixed net. It is not a globally projected Earth coordinate and must not be sent to Earth rasters.
- `CubeNetLayout.fromPlanar` deliberately rejects exact net edges. Edge ownership must be selected by the same half-open cell rule used by the materializer; callers must not invent a second edge owner.
- `toPlanarUnbounded` is permitted only for derived seam/apron geometry.

## 3. Proposed context and owner interfaces

The following is an API contract sketch, not source code to add in this audit:

```java
public interface TellusCoordinateContext {
   SphereWorldModel.GeoPoint geo();
   EarthCompatibilityPoint earth();
   SphericalNoisePoint sphericalNoise();
   GeneratorOwnerView owner();

   // Optional when a request entered through a planar Cube-Net view.
   Optional<CubeNetLayout.FacePoint> facePoint();
   Optional<CubeNetLayout.PlanarPoint> planarPoint();
}
```

Prefer a final immutable record implementation produced by factories rather than public setters:

```java
public record ImmutableTellusCoordinateContext(
   SphereWorldModel.GeoPoint geo,
   EarthCompatibilityPoint earth,
   SphericalNoisePoint sphericalNoise,
   GeneratorOwnerView owner,
   Optional<CubeNetLayout.FacePoint> facePoint,
   Optional<CubeNetLayout.PlanarPoint> planarPoint
) implements TellusCoordinateContext { }
```

### 3.1 Owner identity

`GeneratorOwner` should describe the authoritative producer/data owner, not merely the current rendering location:

```java
public enum GeneratorOwner {
   EARTH_PROJECTED,
   SPHERE_FACE,
   CUBE_NET_CANONICAL,
   CUBE_NET_APRON
}
```

A typed owner view carries the fields needed by the owner kind:

```java
public sealed interface GeneratorOwnerView
   permits EarthOwnerView, SphericalOwnerView, CubeNetCanonicalOwnerView, CubeNetApronOwnerView {
   GeneratorOwner kind();
}

public record EarthOwnerView(
   EarthCompatibilityPoint point
) implements GeneratorOwnerView {
   public GeneratorOwner kind() { return GeneratorOwner.EARTH_PROJECTED; }
}

public record SphericalOwnerView(
   SphereWorldModel.SphereBlockAddress block,
   SphereWorldModel.SphereChunkAddress chunk
) implements GeneratorOwnerView {
   public GeneratorOwner kind() { return GeneratorOwner.SPHERE_FACE; }
}

public record CubeNetCanonicalOwnerView(
   SphereWorldModel.SphereBlockAddress block,
   SphereWorldModel.SphereChunkAddress chunk,
   CubeNetLayout.FacePoint facePoint,
   CubeNetLayout.PlanarPoint planarPoint
) implements GeneratorOwnerView {
   public GeneratorOwner kind() { return GeneratorOwner.CUBE_NET_CANONICAL; }
}

public record CubeNetApronOwnerView(
   CubeNetLayout.PlanarPoint proxyPoint,
   CubeNetApronResolver.ApronAddress blockProjection,
   CubeNetApronResolver.ChunkProjection chunkProjection,
   CubeNetCanonicalOwnerView canonicalOwner
) implements GeneratorOwnerView {
   public GeneratorOwner kind() { return GeneratorOwner.CUBE_NET_APRON; }
}
```

The exact Java shape can be adapted to the common source/MC-version split, but the invariants must remain:

- `CubeNetApronOwnerView.canonicalOwner()` is mandatory. An apron without a canonical target is unresolved and must not be sampled or materialized.
- `CubeNetApronOwnerView` may contain a proxy/source planar coordinate for transport, but all terrain, biome, feature and mutation data is read from `canonicalOwner`.
- `CubeNetCanonicalOwnerView` is the only Cube-Net owner allowed to persist terrain columns, structures, feature decisions and block edits.
- `EarthOwnerView` is used by the Earth generator and Earth compatibility readers. It is not a fallback owner for a spherical point merely because the Earth projection has coverage.

### 3.2 Factories and boundary rules

Recommended factories:

```java
TellusCoordinateContext fromGeo(
   SphereWorldModel model,
   SphereWorldModel.GeoPoint point,
   GeneratorOwnerView owner
);

Optional<TellusCoordinateContext> fromCubeNetPlanar(
   SphereWorldModel model,
   CubeNetLayout layout,
   CubeNetApronResolver apronResolver,
   double blockX,
   double blockZ
);

TellusCoordinateContext fromEarthCompatibility(
   double blockX,
   double blockZ,
   double worldScale,
   String projectionModeId
);
```

`fromCubeNetPlanar` must resolve in this order:

1. Resolve an interior `FacePoint` through `CubeNetLayout.fromPlanar`.
2. Build a `CUBE_NET_CANONICAL` owner from the face-local cell/chunk.
3. If the point is outside an interior, ask `CubeNetApronResolver` for exactly one cut-strip target.
4. Build a `CUBE_NET_APRON` owner that references the target canonical owner.
5. Reject net vertices, ties, mixed-face chunks and unresolved singularity/cap points.
6. Derive `GeoPoint`, `EarthCompatibilityPoint` and `SphericalNoisePoint` from the canonical target, never from proxy planar X/Z.

`fromEarthCompatibility` is intentionally one-way for compatibility sources. If a spherical path needs a physical point, use its canonical `GeoPoint` and the sphere mapping; do not infer Cube-Net ownership by inverse Mercator.

## 4. Lifecycle and cache contract

### Phase A — ingress / ownership resolution

- Earth generator ingress: receive engine X/Z, create an `EarthOwnerView`, and derive geodetic coordinates only through `GlobalMapModel`/`EarthProjection` where the Earth path requires them.
- Sphere face ingress: receive `SphereChunkAddress`/`SphereBlockAddress`, create a `SPHERE_FACE` owner, then derive exact or prepared-chart `GeoPoint`.
- Cube-Net ingress: receive planar engine coordinates, resolve canonical/apron ownership first, then derive all other views from the canonical owner.

### Phase B — source sampling

- Spherical sources consume `GeoPoint` plus requested resolution.
- Earth rasters/classifiers consume `EarthCompatibilityPoint` (including projection mode and coverage state).
- Hash/noise policies consume `SphericalNoisePoint`.
- Planar neighbor/relief operations consume the owner view's canonical face/chunk and use `CubeNetAddressAdapter`/`CubeNetLayout`/`CubeNetRemoteProjection`; they must not consume noise coordinates.
- Existing `SphereColumnContext(address, point, surface)` is the right shape for a sampled canonical column. It should be nested under, or be constructible from, the broader coordinate context rather than becoming a second coordinate authority.

### Phase C — terrain/biome/materialization

- Attach sampled `SurfaceData` to the canonical column context.
- Resolve biome and surface material using typed coordinate arguments.
- Materialize canonical chunks from canonical face columns.
- For aprons, transform/copy canonical columns to the proxy using `ChunkProjection`/seam transforms. Do not re-sample the proxy point.
- Structures, mutations, block entities and owner refreshes use the canonical owner. `CubeNetApronMutationRouter` and apron sync remain view synchronization, not independent generation.

### Phase D — cache and persistence

- Canonical caches key by canonical face/block/chunk address, mapping ID, world scale and source resolution/version.
- Earth compatibility caches key by projected integer cell plus projection mode/world scale and source version.
- Noise caches key by `SphericalNoisePoint.algorithmId` and quantized noise pair.
- Apron caches are ephemeral aliases keyed by proxy chunk plus seam/projection version; they must never become the persistent terrain owner.
- Do not cache by raw planar X/Z for source data unless the key also records the resolved owner and view version.

## 5. Current implementation audit

### Earth projection and projected generator

- `EarthProjection.java:14-63` defines longitude-linear X, Mercator/legacy Z, inverse conversion and local ground scale. It is a compatibility projection with a configurable mode; it does not describe spherical ownership.
- `GlobalMapModel.java:5-16, 33-73` explicitly calls itself a compatibility geometry kernel. `project`/`unproject` use `EarthProjection`; `GeoPoint` here duplicates the geodetic value semantics already present in `SphereWorldModel`.
- `EarthChunkGenerator.java:429-450` constructs `GlobalMapModel`, projects the configured spawn and stores projected spawn chunk coordinates.
- `EarthBiomeSource.java:227-231, 370-379, 542-616` receives engine X/Z as Earth projected block coordinates and runs cover, water, Köppen, RESOLVE and biome classification in that domain. This path is internally consistent.
- Earth data sources such as `TellusResolveSource.sampleEcoregion` (`TellusResolveSource.java:55-79`) explicitly convert input projected blocks back through `EarthProjection`; their input contract is therefore Earth compatibility coordinates.

### Spherical model and face runtime

- `SphereWorldModel.java:5-16` states that the cubed sphere, not Mercator, is authoritative. `exactSurfacePoint` (`150-167`) and `continuousForGeo` (`187-191`) are the canonical conversions.
- `SphereWorldModel.blockForGeo`/`chunkForGeo` (`322-335`) own geodetic-to-face-cell ownership. `neighbor` (`346-363`) handles seam transitions through canonical re-ownership.
- `SphereFaceTopology.java:5-11, 36-75, 82-117` adapts canonical face addresses to a centered engine-local face view; it is an address adapter, not a projection authority.
- `SphereFaceChunkGenerator.java:157-164` uses face-local addresses and `model.surfacePoint`; its fixed biome source is intentional for the face-only generator. It should not be made to infer Earth biome classification from engine-local coordinates.
- `SphereChunkSampler.java:19-23, 53-69, 141-170` samples external data from `GeoPoint`; no projected Minecraft coordinates enter the spherical sampler. `Column.context()` (`279-289`) already preserves canonical address + point + surface data.

### Cube-Net planar view

- `CubeNetLayout.java:8-14` documents the net as a coordinate view. `fromPlanar` (`68-84`) accepts only an interior; `toPlanarUnbounded` (`48-61`) is for derived seam geometry. `crossSeam` (`129-149`) transports a continuous face step and rejects corner crossings without an explicit vertex policy.
- `CubeNetAddressAdapter.java:6-10, 27-39` bridges canonical face blocks to planar centers. `chunkAddress` (`42-76`) returns empty when the planar chunk is outside a face or straddles a net cut. `neighbor` (`78-105`) returns a canonical target and seam metadata.
- `CubeNetApronResolver.java:8-14` defines an apron as a mirror/proxy band. `resolve` (`37-75`) rejects existing canonical footprints, requires exactly one directed strip, and maps to a target face block. `resolveCanonicalBlock` (`78-109`) is reverse lookup for owner refresh. `resolveChunk` (`112-144`) requires all four corners to agree on one directed projection and target chunk.
- `CubeNetRemoteProjection.java:7-11, 37-77` repeats the same principle for LOD: canonical footprints retain ownership, ties/overlaps are quarantined, and remote width cannot invent another face-sized world.
- `CubeNetSeamTransform.java:7-15, 32-53` states that target-face blocks remain canonical and are only rendered in a source-face apron.
- `CubeNetFeatureProjector.java:7-10, 14-31` correctly projects geodetic feature vertices through the sphere inverse and the cube-net layout. Its documentation is the desired rule for all OSM feature rasterization.

### Cube-Net biome and surface consumers

- `CubeNetBiomeSource.getNoiseBiome` (`526-579`) resolves planar quart coordinates to a canonical `SphereBlockAddress`, samples `GeoPoint`, then mixes Earth compatibility and spherical noise domains in one untyped `int[]`/`blockX`/`blockZ` flow.
- `CubeNetBiomeSource.sampleDefaultClimate` (`839-845`) correctly converts `GeoPoint` to Earth projected coordinates for Köppen/ocean sources.
- `CubeNetBiomeSource.sampleDefaultInlandWater` (`848-858`) correctly uses Earth projected cells for the Earth OSM-water compatibility resolver.
- `CubeNetBiomeSource.sampleVisualCover` (`743-749`) correctly uses Earth compatibility coordinates for fine visual-cover lookup, while retaining canonical raw cover as fallback.
- `CubeNetSurfacePolicy.resolveEarthEquivalent` (`74-159`) explicitly receives Earth `earthX/earthZ` for the native Cube-Net material path; this is the intended separation, but the caller still derives those values ad hoc.
- `CubeNetSurfacePolicy.canonicalNoiseCoordinates` (`287-305`) is a spherical-noise implementation. Its rounding must be unified with `CubeNetBiomeSource.noiseCoordinates`.
- `CubeNetPreloadAdapter.java:12-22, 35-73` intentionally adapts a spherical `GeoPoint` to the Earth preload grid and misses polar caps outside Mercator coverage. This is a compatibility adapter, not a Cube-Net owner mapping.

## 6. Bare-biome mismatch: coordinate root cause

### Primary root cause: wrong domain passed to Earth classification

The Earth path classifies a bare pixel using projected Earth blocks:

```text
EarthBiomeSource
  -> classifyLandBiome(visualCover, koppen, earthBlockX, earthBlockZ)
  -> BiomeClassificationProviders.findBiomeKey(..., earthBlockX, earthBlockZ, scale)
  -> ResolveBiomeClassificationProvider
  -> TellusResolveSource.sampleEcoregion(earthBlockX, earthBlockZ, scale)
```

The Cube-Net path currently does this instead:

```text
CubeNetBiomeSource.getNoiseBiome
  -> point = SphereWorldModel.surfacePoint(canonical)
  -> noiseCoordinates(point)                 // ECEF-derived spherical hash pair
  -> resolveLandBiome(..., noiseX, noiseZ)
  -> BiomeClassificationProviders.findBiomeKey(..., noiseX, noiseZ, scale)
  -> TellusResolveSource.sampleEcoregion(noiseX, noiseZ, scale)
```

`TellusResolveSource` interprets its arguments as Earth projected blocks. Therefore the Cube-Net path asks the Earth RESOLVE raster for the location represented by the ECEF-noise pair, not the location represented by the `GeoPoint`. The visual cover may still be sampled with the correct Earth pair (`sampleVisualCover`), so the same column can have correct `visualCover` but an ecoregion/biome selected from a different location. This is the direct cause of Earth/Cube bare-biome disagreement.

### Secondary root cause: planar helpers receive spherical noise coordinates

The same `blockX/blockZ` values passed into `resolveLandBiome` are later used by Cube-Net methods that expect planar net coordinates:

- `resolveSnowBiome` calls `sampleElevationAtPlanar(blockX ± baseline, blockZ)`.
- `sampleRegionalRelief` calls `sampleElevationAtPlanar(blockX ± offset, blockZ ± offset)`.
- `isCherryTerrain` and badlands promotion depend on those regional samples.

Spherical noise values are roughly unit-sphere coordinates multiplied by one million; they are not face-local or planar net coordinates. At common face sizes, these lookups fall outside the net or resolve an unrelated location, causing missing/incorrect relief and promotion decisions. This is especially visible in bare/badlands decisions because `resolveLandBiome` uses `sampleRegionalRelief` to promote/demote dry terrain.

### Tertiary root cause: duplicate quantizers

`CubeNetBiomeSource.noiseCoordinates` truncates `(int) value`, while `CubeNetSurfacePolicy.canonicalNoiseCoordinates` rounds (`Math.round`). Hash-based variants can differ by one integer even when both are intended to describe the same spherical point. This is a separate deterministic mismatch from the Earth-domain error, but it can amplify visible differences in bare terrain, patches and variants.

### What is not the root cause

- The conformal cubed-sphere mapping itself is not the first repair target; its face inverse/forward ownership and seam tests are explicit.
- The Earth projection's Mercator scale is not interchangeable with the spherical reference radius, but the radius difference alone does not explain the current bare mismatch. The mismatch occurs because an ECEF noise pair is passed into an Earth raster API.
- Apron projection is not a second biome source. If an apron differs from its canonical owner, the first suspect is a caller using proxy planar coordinates or an unresolved owner rather than the resolver's seam transform.

## 7. Repair boundary and migration sequence

### In scope for the coordinate-context repair

1. Add the typed point/context/owner contracts described above in the shared worldgen layer.
2. Add one canonical `GeoPoint -> EarthCompatibilityPoint` conversion using `EarthProjection` and one canonical `GeoPoint -> SphericalNoisePoint` conversion with a versioned quantizer.
3. Change Cube-Net biome classification calls to pass `context.earth()` to Earth classifiers/providers.
4. Change random/cave/patch decisions to pass `context.sphericalNoise()`.
5. Change relief/snow/shoreline neighborhood queries to use `context.owner()` and canonical face/planar adapters.
6. Make `SphereColumnContext` constructible from the same context so surface data and coordinates cannot drift.
7. Add assertions/tests that an Earth provider never receives spherical-noise coordinates and that an apron sample resolves to one canonical owner.

### Explicitly out of scope for the first repair

- Replacing `EarthProjection` or `GlobalMapModel` in the Earth generator.
- Rewriting `SphereWorldModel`, `ConformalCubedSphere`, face mapping coefficients or seam topology.
- Making Earth raster data cover the polar caps; `CubeNetPreloadAdapter` may continue to miss there and fall back to spherical sampling.
- Changing Minecraft's `ChunkPos`, planar world border or engine-local face coordinates.
- Making aprons persistent owners or generating independent terrain/biome data for aprons.
- Changing standalone `SphereFaceChunkGenerator` from its fixed-biome contract.
- Broadly replacing every `int blockX, int blockZ` signature in one change. Migrate domain-sensitive boundaries first, then let compiler/test failures identify remaining untyped calls.

### Suggested first implementation slice

```text
CubeNetBiomeSource.getNoiseBiome
  1. resolve planar -> canonical/apron owner
  2. build TellusCoordinateContext from canonical GeoPoint
  3. earth = context.earth()
  4. noise = context.sphericalNoise()
  5. canonicalPlanar = context.owner().canonicalPlanar()
  6. Earth classification/cover/climate -> earth
  7. noise/random/cave variants -> noise
  8. relief/snow/neighbor samples -> canonicalPlanar/face owner
```

The returned biome and surface decision should then be compared against the Earth path at the same `GeoPoint`, with expected differences limited to intentional spherical coverage/polar behavior and the documented view/materialization policy.

## 8. Invariants to encode in tests

- A `GeoPoint` converted to `EarthCompatibilityPoint` and then consumed by `TellusResolveSource` samples the same geodetic location as EarthBiomeSource.
- A `GeoPoint` converted to `SphericalNoisePoint` produces the same integer pair in Cube-Net biome, surface, cave and random-biome paths.
- Crossing ±180° changes Earth compatibility X by the projection wrap but does not cause a discontinuity in `SphericalNoisePoint`.
- A canonical Cube-Net block has exactly one canonical owner; its planar view may have zero or more derived apron projections, never another data owner.
- An apron `resolve` result, its `resolveCanonicalBlock` reverse mapping and its `ChunkProjection` all identify the same target face/chunk/cell.
- Net vertices, mixed-face chunks, apron ties and singularity-quarantined addresses remain unresolved rather than being assigned by arbitrary edge order.
- Earth projected coordinates are never passed to `SphereWorldModel.blockForGeo` as if they were face coordinates; spherical noise coordinates are never passed to Earth raster/classification APIs; planar coordinates are never passed to geodetic source APIs without owner resolution.
- `SphereColumnContext.address`, `point` and `surface` remain a single canonical column identity throughout materialization, LOD and apron synchronization.

## 9. Decision summary

`GeoPoint` is the physical/spherical truth. `EarthCompatibilityPoint` is a deliberate projected-data compatibility view. `SphericalNoisePoint` is a seam-stable deterministic-noise view. `CubeNetLayout`/`CubeNetAddressAdapter`/`CubeNetApronResolver` are ownership/view adapters, with canonical face cells owning all data and aprons only rendering or synchronizing derived copies. A typed context is needed to prevent the current `blockX/blockZ` overload from mixing these domains. The first bug to fix is the Cube-Net biome path passing ECEF noise coordinates into Earth RESOLVE classification; the second is passing the same noise pair into planar relief helpers. No mapping or source rewrite is required for that repair.
