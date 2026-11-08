# Tellus generator settings/runtime abstraction

**Status:** read-only architecture audit and interface design; no Java source was changed.

**Scope:** `EarthGeneratorSettings`/`EarthChunkGenerator`, Cube-Net (`CubeNetBiomeSource.Settings`, `CubeNetChunkGenerator`, `CubeNetRuntime`), spherical faces (`SphereFaceChunkGenerator`, `SphereFaceRuntime`), `TellusCommon`, `TellusRealtimeManager`, Distant Horizons (DH), Voxy, and Tellus-managed terrain downloads.

**Intent:** introduce a common read-only settings view and a runtime lifecycle/capability SPI without changing generator codecs, persisted world data, runtime override precedence, or the current Earth/Cube-Net/Sphere behavior.

## 1. Executive summary

The current implementation has three different configuration shapes:

- **Earth** has one large immutable `EarthGeneratorSettings` record. Its codec is the effective normalization boundary: it supplies defaults, clamps ranges, fixes DH OSM values and water ownership, normalizes random-biome selection, and makes experimental-height settings own the vertical range.
- **Cube-Net** stores a smaller spherical-view `CubeNetBiomeSource.Settings` record inside the biome source. `CubeNetChunkGenerator` copies selected values into final fields and fills missing values from Cube-Net system-property defaults or `EarthGeneratorSettings.DEFAULT`. Generator codec fields mostly describe geometry and shoreline/apron policy.
- **Sphere-Face** persists geometry only (`mapping`, face, scale, vertical range, planet and singularity policy). It has no common Tellus settings record. Its six-face coordination is performed by `SphereFaceRuntime`.

Runtime ownership is similarly split. `TellusCommon` installs global realtime, Voxy, managed-download, deferred-detail and DH lifecycle hooks; Cube-Net and Sphere-Face runtimes install their own loader lifecycle hooks and static movement entry points; DH has Earth, Cube-Net and chunk-backed branches; Voxy and managed terrain currently accept only Earth generators.

The proposed boundary is therefore deliberately small:

1. `TellusGeneratorSettingsView` is an immutable, effective-value **view**. It is not a replacement codec and never contains runtime overrides.
2. `TellusGeneratorRuntime` is a lifecycle/ownership adapter around one generator family (or a validated six-face group). It delegates to existing managers and exposes optional capabilities rather than pretending every generator supports every integration.
3. Existing concrete classes remain the behavior authority during migration. The first implementation is an adapter/fallback layer; no codec, save format, sampling path, queue policy or seam algorithm changes are required.

## 2. Read-only audit of current behavior

### 2.1 Earth settings and generator

`EarthGeneratorSettings` is a record with the following effective groups:

| Group | Current fields/behavior | Important contract |
| --- | --- | --- |
| Geometry and height | `worldScale`, terrestrial/oceanic height scales, `heightOffset`, spawn latitude/longitude, min/max altitude, shoreline blend values | Constructor clamps/normalizes values. `resolveHeightLimits` aligns the final range to 16-block sections. `experimentalIncreaseHeight` owns the full packed range and forces effective scales/offset/altitudes. |
| Terrain | cave generation, ore distribution, geological stone patches, lava pools, geodes, water, thin shell, climate-built terrain, underground depth | The constructor forces `enableWater` to `true`; the serialized field is retained for old worlds but Overture vector water is the supported source. |
| Structures | individual `add_*` switches, including newer trail ruins and abandoned camps | `TellusStructurePolicy` consumes these toggles to filter structure sets. |
| Biomes and surface | random-biome switch/density/seed/IDs, roads, buildings, custom trees, huge mushrooms | IDs are normalized against the running Minecraft version. |
| Realtime | `realtimeTime`, `realtimeWeather`, `historicalSnow`, `lakeFreezing`, `teleportProgress` | Values are read by `TellusRealtimeManager` and geodetic movement/teleport helpers. |
| DH | render mode, DEM selection, OSM detail/non-blocking values, city-light distance | DH OSM settings are currently fixed to the Tellus-supported values in the constructor. Render mode and city-light distance remain user-visible. |
| Voxy | enabled, maximum radius, chunks per tick | These are configured values only. `TellusVoxyPregenManager` applies separate runtime overrides and intersects configured radius with Voxy's live radius. |
| Managed terrain | managed-download switch and overlay switch | The download manager additionally requires DH presence and the DH generation-gate extension. |

`EarthChunkGenerator` stores this record once in its constructor (`settings()`), derives the map model, sea level, height limits, water resolver, preloaded terrain view, treeline and landmarks from it, and uses it throughout chunk generation, prefetch, structures, LOD sampling and geodetic surface lookup. The record is immutable; rebuilding a generator is the normal way to change world-generation settings.

### 2.2 Cube-Net settings and generator

`CubeNetBiomeSource.Settings` owns spherical-view settings:

- random biomes, density, seed and IDs;
- cave/deep-dark/underground-depth, ore veins, geological stone patches and caves-reaching-surface;
- roads/buildings and all structure toggles;
- lake freezing, lava pools, geodes, water and teleport progress;
- height offset, spawn latitude/longitude and terrestrial/oceanic height scales.

Its codec is nested in the biome-source codec. Defaults use Earth defaults plus the existing Cube-Net system properties for caves, ore veins, geological patches, roads/buildings and caves-reaching-surface. The constructor validates density, coordinates, depth and positive scales.

`CubeNetChunkGenerator` does not have an `EarthGeneratorSettings` field. It resolves values from `CubeNetBiomeSource.settings()` where available, otherwise from existing static defaults, then caches effective values in final fields. It creates six `SphereFaceChunkGenerator` instances with common scale/sea/vertical geometry and the face biome source. Its codec persists geometry, singularity policy, biome source, scale, sea level, vertical range, apron width and shoreline blends; it does not persist an Earth settings record.

The Cube-Net runtime is a same-Level seam adapter. `CubeNetRuntime` owns boundary interception, apron staging, chunk packet handoff, correction gates, motion validation, entity/vehicle rebase and status commands. It does not mutate generator settings.

### 2.3 Sphere-Face settings and generator

`SphereFaceChunkGenerator` persists:

- geometry version and mapping ID;
- singularity exclusion/transition widths;
- fixed biome source;
- face index;
- world scale, sea level, min Y and height;
- optional planet ID.

It samples canonical spherical terrain and owns a bounded terrain-grid cache. It does not implement `TellusGeodeticPosition`, so the current realtime manager does not run for a standalone sphere face. `SphereFaceRuntime` validates a complete six-face planet group with matching scale, mapping, singularity policy, sea level and vertical geometry, then routes cross-face player transfers after destination chunks are ready. Incomplete or inconsistent groups leave routing disabled.

This absence of feature toggles is intentional current behavior: Sphere-Face is a terrain/routing entry point, not an Earth feature-policy adapter.

### 2.4 TellusCommon and loader runtime management

`TellusCommon.initializeRuntime` installs:

- commands and server lifecycle callbacks;
- `TellusRealtimeManager.onServerTick`;
- `TellusVoxyPregenManager.onServerTick`;
- managed terrain scheduling/status broadcast;
- Earth deferred-detail processing and prepared-state cleanup;
- player join/disconnect callbacks;
- DH bootstrap when DH is loaded.

Several branches are Earth-specific `instanceof EarthChunkGenerator` checks. Examples include world-border/dynamic-dimension setup, benchmark prefetch, weather-report settings, Voxy commands, managed downloads, DH height guards and DH Earth LOD registration. These checks are not merely type checks: they select the settings source and the feature-policy authority.

`TellusRuntimePlatform` already supplies the cross-loader lifecycle shape (`onServerStarted`, `onServerStopping`, `onServerTick`, chunk unload, player join/disconnect). The new runtime SPI should sit above this platform interface, not replace it.

### 2.5 TellusRealtimeManager

The manager first requires `TellusGeodeticPosition`. It then obtains an `EarthGeneratorSettings` only for an `EarthChunkGenerator`:

```java
EarthGeneratorSettings settings = geodetic instanceof EarthChunkGenerator earth
   ? earth.settings() : null;
boolean enableTime = settings != null
   ? isRealtimeTimeEnabled(settings) : CUBE_NET_REALTIME_TIME;
boolean enableWeather = settings != null
   ? isRealtimeWeatherEnabled(settings) : CUBE_NET_REALTIME_WEATHER;
boolean enableSnow = settings != null
   ? settings.historicalSnow() : CUBE_NET_HISTORICAL_SNOW;
```

The fallback booleans are the existing `tellus.cubeNet.realtimeTime`, `tellus.cubeNet.realtimeWeather` and `tellus.cubeNet.historicalSnow` system properties. Runtime command overrides have higher priority than settings and are cleared on server stop. Weather sampling, timezone resolution and snow application use the generator's geodetic methods; no generation settings are mutated.

The adapter must preserve this precedence exactly:

```text
runtime override (if present)
  > configured view value (Earth settings)
  > legacy Cube-Net system-property fallback
  > disabled/default
```

### 2.6 DH, Voxy and managed terrain generator branches

#### Distant Horizons

`DistantHorizonsIntegration` currently classifies a loaded level as:

1. `EarthChunkGenerator`: uses Earth settings; skips DH override when Voxy pregen is enabled; chooses chunk-backed or direct LOD based on render mode and DH generator mode; direct LOD reads Earth settings for OSM, city lights, trees, water and managed downloads.
2. `CubeNetChunkGenerator`: chooses a direct seam-aware `CubeNetLodGenerator` only when `tellus.dhCubeNetSeamLod` and the DH generation gate are available and the generator implements `CubeNetLodSurface`; otherwise uses `TellusChunkLodGenerator`.
3. Other generators (including Sphere-Face): no Tellus DH generator override.

`TellusLodGenerator` uses `tellusManagedTerrainDownloads` only for Earth. `TellusChunkLodGenerator` uses cache-only network policy when the Earth managed-download switch and generation gate are active. The direct path temporarily overrides selected DH API config entries and restores them on level unload; those are runtime-only values and must remain outside persisted generator settings.

#### Voxy

`TellusVoxyPregenManager` runs only when the overworld generator is Earth. It requires Voxy to be loaded, intersects Voxy's live configured radius (read from `voxy-config.json`) with the effective Tellus maximum radius, and launches full-chunk requests under an adaptive per-tick budget. Three nullable volatile runtime overrides (enabled, radius and chunks/tick) take precedence over Earth settings and are cleared on stop. `VoxyBridge` is an optional integration: mod detection, config reading and reflective ingest are all best-effort.

#### Managed terrain downloads

`ManagedTerrainDownloadManager` accepts only Earth generators. Its configured switch and overlay flag come from Earth settings. The active path additionally requires DH presence and the DH generation-gate extension. It tracks per-player render radii, per-generator coverage keys, progressive batches, package publication and retry state. `ManagedTerrainAvailability` returns READY/SPLIT/WAIT; failures are retried without changing generator settings. If the switch is off, DH is absent, or the gate is unavailable, the manager deliberately falls back to legacy behavior.

## 3. Proposed `TellusGeneratorSettingsView`

### 3.1 Java-style interface

The following is an API sketch, not a source change in this task. Names may be adjusted to the project's package conventions, but the contracts should remain stable.

```java
public interface TellusGeneratorSettingsView {
   enum Kind { EARTH, CUBE_NET, SPHERE_FACE }

   enum Capability {
      GEODETIC_POSITION,
      REALTIME_TIME,
      REALTIME_WEATHER,
      HISTORICAL_SNOW,
      DISTANT_HORIZONS_LOD,
      VOXY_PREGEN,
      MANAGED_TERRAIN_DOWNLOADS,
      SEAM_NAVIGATION,
      FACE_ROUTING,
      EXPERIMENTAL_HEIGHT
   }

   record VerticalRange(int minY, int height, int logicalHeight) {
      public VerticalRange {
         if (height <= 0 || logicalHeight <= 0) throw new IllegalArgumentException("height");
      }
      public int maxYExclusive() { return minY + height; }
   }

   record GeoPoint(double latitude, double longitude) { }

   record TerrainOptions(
      boolean caveGeneration,
      boolean oreGeneration,
      boolean geologicalStonePatches,
      boolean lavaPools,
      boolean geodes,
      boolean deepDark,
      boolean water,
      boolean roads,
      boolean buildings,
      boolean cavesReachSurface,
      int undergroundDepth,
      boolean thinShellTerrain,
      boolean climateBasedBuiltUpTerrain,
      boolean lakeFreezing,
      boolean customTrees,
      boolean hugeRedMushrooms
   ) { }

   /** Structure IDs are stable resource IDs, not Java enum ordinals. */
   record StructureOptions(java.util.Set<String> enabledIds) {
      public StructureOptions {
         enabledIds = java.util.Set.copyOf(enabledIds);
      }
      public boolean enabled(String id) { return enabledIds.contains(id); }
   }

   record RandomBiomeOptions(boolean enabled, double density, long seed,
                             java.util.List<String> biomeIds) {
      public RandomBiomeOptions {
         biomeIds = java.util.List.copyOf(biomeIds);
      }
   }

   record RealtimeOptions(boolean time, boolean weather, boolean historicalSnow) { }

   record DistantHorizonsOptions(
      String renderMode,
      int cityLightsDistanceChunks,
      boolean osmFeatures,
      int osmRoadMaxDetail,
      int osmBuildingMaxDetail,
      boolean osmNonBlockingFetch
   ) { }

   record VoxyOptions(boolean enabled, int maxRadiusChunks, int chunksPerTick) { }

   record ManagedTerrainOptions(boolean enabled, boolean showOverlay) { }

   record ExperimentalHeightOptions(boolean enabled, boolean automaticScaling) { }

   /** Stable generator/resource ID, e.g. tellus:earth or tellus:cube_net. */
   String generatorId();

   Kind kind();

   /** Mapping/geometry identity persisted by the generator codec. */
   String geometryId();

   /** Real-world horizontal metres represented by one block. */
   double worldScale();

   /** Effective generator vertical range after codec normalization. */
   VerticalRange verticalRange();

   /** Effective sea level used by materialization and height queries. */
   int seaLevel();

   /** Configured vertical origin before a runtime-specific sea-level policy. */
   int heightOffset();

   /** Empty when the generator has no configured spawn coordinate (Sphere-Face). */
   java.util.Optional<GeoPoint> spawn();

   /** Empty only when the generator has no Tellus terrain policy (current Sphere-Face). */
   java.util.Optional<TerrainOptions> terrain();

   java.util.Optional<StructureOptions> structures();

   java.util.Optional<RandomBiomeOptions> randomBiomes();

   /** Configured values only; runtime command/API overrides are not included. */
   java.util.Optional<RealtimeOptions> realtime();

   java.util.Optional<DistantHorizonsOptions> distantHorizons();

   java.util.Optional<VoxyOptions> voxy();

   java.util.Optional<ManagedTerrainOptions> managedTerrain();

   java.util.Optional<ExperimentalHeightOptions> experimentalHeight();

   java.util.Set<Capability> capabilities();

   default boolean supports(Capability capability) {
      return capabilities().contains(java.util.Objects.requireNonNull(capability, "capability"));
   }
}
```

### 3.2 Method contracts and normalization rules

- Every accessor is read-only, deterministic for the generator instance and non-null except the explicit `Optional` wrappers. The view must be safe to retain for a tick or a background request.
- Values are **effective values after the existing codec/record constructor normalization**, not raw JSON. In particular, Earth's `water()` is `true` because the current constructor forces it; experimental-height views expose the resolved full range and `ExperimentalHeightOptions` separately.
- `heightOffset()` and `seaLevel()` are deliberately separate. Earth's sea level is derived from effective height settings; Cube-Net applies its view-specific height offset to the serialized sea level; callers must not recompute one from the other.
- `worldScale()` uses the existing unit (real-world metres per horizontal block). It must retain current positive/clamped validation.
- `geometryId()` is an identity, not a display label. Earth can use its projection identity; Cube-Net and Sphere-Face use `SphereWorldModel.mappingId()` and should reject mismatches exactly as their codecs currently do.
- Structure IDs are resource IDs (for example `minecraft:stronghold`), so a future structure can be added without changing the interface binary shape. The adapter maps existing `add_*` fields through the existing `TellusStructurePolicy` names.
- Optional groups mean “this generator does not own or expose this policy,” not “the feature is currently disabled.” Enabled/disabled is represented inside the group. A missing DH/Voxy/managed group must keep the current legacy branch rather than being interpreted as `false` and disabling a third-party integration.
- `capabilities()` reports implemented semantics, not current mod availability. DH/Voxy/gate readiness belongs to the runtime capability described below.
- The view must not expose mutable override setters. Voxy command overrides, realtime command overrides and DH API overrides remain runtime state owned by their current managers.

### 3.3 Adapters

| Generator | View source | Common mapping | Optional groups/capabilities |
| --- | --- | --- | --- |
| Earth | Wrap `EarthChunkGenerator.settings()` plus generator geometry/height accessors | All common groups map directly. `oreDistribution` maps to `oreGeneration`; Earth structure fields map to resource IDs; effective height limits come from `EarthGeneratorSettings.resolveHeightLimits`. | Geodetic, realtime, historical snow, DH LOD, Voxy, managed terrain, experimental height. |
| Cube-Net | Wrap `CubeNetBiomeSource.Settings` plus `CubeNetChunkGenerator` geometry/height/apron accessors | `oreVeins` maps to `oreGeneration`; all spherical structure/world toggles map to the common groups; default/fallback values must use the current Cube-Net resolver and system properties. `spawn` and height scales come from `Settings`. | Geodetic and seam navigation. Realtime options initially read the three existing Cube-Net system properties to preserve `TellusRealtimeManager` behavior. DH LOD is available only when the Cube-Net DH surface/gate branch is available. No Voxy or managed-download group. |
| Sphere-Face | Wrap `SphereFaceChunkGenerator` codec-backed geometry | `worldScale`, vertical range, sea level, height offset (same as sea level for the current radial materializer), geometry ID, face/planet metadata belong to the view. No Earth feature toggles are invented. | Face routing only when a validated six-face `planet` group exists. No current geodetic/realtime/DH/Voxy/managed capability. |

For Cube-Net, the adapter must preserve the current “settings in biome source, geometry in generator” split. It must not create an `EarthGeneratorSettings` copy and accidentally enable Earth-only features. For Sphere-Face, the adapter must not manufacture spawn, OSM, structures or realtime values from `EarthGeneratorSettings.DEFAULT`.

## 4. Proposed `TellusGeneratorRuntime`

### 4.1 Java-style interface

```java
public interface TellusGeneratorRuntime {
   enum AttachResult { ATTACHED, ALREADY_ATTACHED, NOT_OWNED, INCOMPATIBLE }

   TellusGeneratorSettingsView settings();

   /** Runtime family represented by this adapter; stable for diagnostics. */
   TellusGeneratorSettingsView.Kind kind();

   /** Identity/type check; must not perform chunk loads or network I/O. */
   boolean owns(net.minecraft.world.level.chunk.ChunkGenerator generator);

   /**
    * Attaches this runtime to a matching level. Must be idempotent. It may
    * validate a six-face group, but must not synchronously generate terrain.
    */
   AttachResult attach(net.minecraft.server.MinecraftServer server,
                       net.minecraft.server.level.ServerLevel level);

   /** Idempotent lifecycle cleanup. Must release tickets, listeners and local state. */
   void detach(net.minecraft.server.MinecraftServer server,
               net.minecraft.server.level.ServerLevel level);

   /** Main-thread tick; must not block on remote terrain/weather data. */
   void onServerTick(net.minecraft.server.MinecraftServer server);

   default void onChunkUnload(net.minecraft.server.level.ServerLevel level,
                              net.minecraft.world.level.ChunkPos pos) { }

   default void onPlayerJoin(net.minecraft.server.MinecraftServer server,
                             net.minecraft.server.level.ServerPlayer player) { }

   default void onPlayerDisconnect(net.minecraft.server.level.ServerPlayer player) { }

   /** Return true only when the runtime consumed/held the boundary movement. */
   default boolean interceptBoundaryMove(net.minecraft.server.level.ServerPlayer player,
                                         net.minecraft.world.phys.Vec3 movement) {
      return false;
   }

   /** Vanilla-compatible default for generators without a boundary policy. */
   default boolean admitsPlayerDestination(net.minecraft.server.level.ServerLevel level,
                                           double x, double z) {
      return true;
   }

   default java.util.Optional<RealtimeRuntime> realtime() {
      return java.util.Optional.empty();
   }

   default java.util.Optional<DistantHorizonsRuntime> distantHorizons() {
      return java.util.Optional.empty();
   }

   default java.util.Optional<VoxyRuntime> voxy() {
      return java.util.Optional.empty();
   }

   default java.util.Optional<ManagedTerrainRuntime> managedTerrain() {
      return java.util.Optional.empty();
   }

   default java.util.Optional<SeamNavigationRuntime> seamNavigation() {
      return java.util.Optional.empty();
   }

   default java.util.Optional<FaceRoutingRuntime> faceRouting() {
      return java.util.Optional.empty();
   }

   interface RealtimeRuntime {
      boolean timeEnabled();
      boolean weatherEnabled();
      boolean historicalSnowEnabled();
      /** Values include the current command override precedence. */
      java.time.Instant currentInstant();
   }

   interface DistantHorizonsRuntime {
      boolean present();
      boolean generationGateAvailable();
      boolean generationReady();
      /** Whether this generator can register the Tellus direct/chunk path. */
      boolean supportsLodGeneration();
   }

   interface VoxyRuntime {
      boolean loaded();
      boolean effectiveEnabled();
      int configuredRadiusChunks();
      int effectiveRadiusChunks();
      int effectiveChunksPerTick();
      int queuedChunkCount();
      int inFlightChunkCount();
   }

   interface ManagedTerrainRuntime {
      boolean configured();
      boolean active();
      byte availability(int minChunkX, int minChunkZ, int widthChunks);
   }

   interface SeamNavigationRuntime {
      boolean interceptBoundaryMove(net.minecraft.server.level.ServerPlayer player,
                                   net.minecraft.world.phys.Vec3 movement);
      boolean admitsDestination(net.minecraft.server.level.ServerLevel level,
                                double x, double z);
   }

   interface FaceRoutingRuntime {
      boolean hasCompleteGroup();
      int faceCount();
   }
}
```

### 4.2 Runtime method contracts

- `settings()` returns the immutable configured view associated with the owned generator. It must not include mutable runtime overrides. A six-face adapter returns the representative view only after validating that all six views have matching geometry and vertical settings; otherwise `attach` returns `INCOMPATIBLE` and routing stays disabled.
- `owns` is a pure identity/classification operation. It must not query DH, Voxy, filesystem state, network sources or chunk state.
- `attach`/`detach` are lifecycle boundaries. They are idempotent and release every ticket, callback, generation task, correction gate and cache owned by the adapter. `detach` is called during server stop and level unload even after a partial attach.
- `onServerTick` runs on the server thread. It may enqueue work, advance queues and publish status, but must retain the existing non-blocking weather/prefetch/LOD behavior. It must not join a remote source future on the tick thread.
- The default boundary hooks preserve vanilla behavior. A runtime must return `true` only after it has actually held/consumed movement; a failed or incompatible route must follow the current rollback/hold behavior.
- Optional runtime objects report **effective runtime state**, including mod presence, DH gate readiness, Voxy live radius and command overrides. Their absence means the capability is not owned by that generator; callers must use the existing legacy path.
- Runtime implementations must not mutate a `TellusGeneratorSettingsView`. A command/API override is a separate state layer and is cleared using the same lifecycle as the current manager.

## 5. Earth/Cube/Sphere runtime adaptation

### 5.1 Earth adapter

`EarthGeneratorRuntimeAdapter` should wrap an `EarthChunkGenerator` and delegate rather than reimplement:

- `settings()` -> `EarthGeneratorSettingsView.from(earthGenerator.settings(), earthGenerator)`;
- `onServerTick` -> existing realtime, Voxy, managed terrain and deferred-detail managers in their current `TellusCommon` registration order;
- `realtime()` -> `TellusRealtimeManager` effective state;
- `voxy()` -> `TellusVoxyPregenManager` effective values and queue counters;
- `managedTerrain()` -> `ManagedTerrainDownloadManager` active/availability state;
- `distantHorizons()` -> DH integration state for the level, without moving DH API override ownership into the adapter.

The first migration can leave `TellusCommon`'s current tick registrations intact and use this adapter only for new consumers. Once parity tests exist, the adapter may become the single dispatch point, but it must call the same managers once and in the same order.

Earth-specific commands remain Earth-specific. In particular, `resolveEarthGenerator`, dynamic dimension pack generation, extended-height world border setup and benchmark area prefetch should not be generalized solely because a view exists.

### 5.2 Cube-Net adapter

`CubeNetGeneratorRuntimeAdapter` should wrap the current `CubeNetRuntime` boundary and movement hooks. The settings adapter combines `CubeNetBiomeSource.Settings` with `CubeNetChunkGenerator` geometry, apron and effective vertical data. It must preserve:

- system-property defaults when a biome source is not `CubeNetBiomeSource`;
- final cached fields in `CubeNetChunkGenerator`;
- native apron ownership, seam transforms, correction gates and entity/vehicle handling;
- the current same-Level runtime registration and payload handshakes.

The Cube-Net realtime capability is initially a compatibility projection of the existing `TellusRealtimeManager` fallback properties. It should not add new serialized Cube-Net fields in the first migration. Voxy and managed terrain are absent; their Earth-only managers continue to clear their state when the current Earth type check fails.

DH classification can later call `owns`/`kind` instead of comparing the class name, but the initial fallback must retain `DistantHorizonsGeneratorSupport.CUBE_NET_GENERATOR_CLASS` so old DH builds behave identically.

### 5.3 Sphere-Face adapter

`SphereFaceGeneratorRuntimeAdapter` wraps the current six-face coordinator. It should:

- use one face's geometry as the representative settings view only after `SphereFaceRuntime.sameGeometry`-equivalent validation;
- return `FACE_ROUTING` only for a complete, distinct face 0--5 group with one matching planet ID;
- delegate boundary routing, asynchronous destination loading, rollback and frame synchronization to `SphereFaceRuntime`;
- leave realtime, DH, Voxy and managed terrain capabilities absent until the generator implements those semantics explicitly.

An ungrouped Sphere-Face remains a valid standalone generator and receives no runtime routing capability, matching the current empty `planet` behavior.

## 6. Optional capability matrix

| Capability | Earth | Cube-Net | Sphere-Face | Current source of truth |
| --- | --- | --- | --- | --- |
| Geodetic/surface lookup | Yes | Yes | No common interface | `TellusGeodeticPosition`, Earth/Cube implementations |
| Realtime time/weather | Earth settings + command override | Legacy system properties + command override | No | `TellusRealtimeManager` |
| Historical snow | Earth setting | Legacy system property | No | `TellusRealtimeManager` |
| Seam navigation | No | Yes | No (uses face routing instead) | `CubeNetRuntime` |
| Face routing | No | No | Grouped faces only | `SphereFaceRuntime` |
| DH LOD | Earth direct/chunk branches | Cube-Net direct/chunk branches | No current branch | `DistantHorizonsIntegration` |
| Managed terrain downloads | Earth + DH gate | No | No | `ManagedTerrainDownloadManager` and DH availability |
| Voxy pregen | Earth + Voxy mod | No | No | `TellusVoxyPregenManager`/`VoxyBridge` |
| Experimental height | Earth | No current setting | Geometry only | `EarthGeneratorSettings`/DH height guard |
| Structures/terrain toggles | Full Earth policy | Cube-Net subset | No current policy | Earth settings/Cube-Net settings |

The matrix is a capability statement, not a feature enablement statement. For example, an Earth runtime can expose `DISTANT_HORIZONS_LOD` while DH is absent; the runtime capability then reports `present() == false`, and the existing non-DH path remains selected.

## 7. Preserving current behavior during migration

1. **Do not change codecs first.** Keep `EarthGeneratorSettings.CODEC`, `CubeNetBiomeSource.Settings.CODEC`, `CubeNetChunkGenerator.CODEC` and `SphereFaceChunkGenerator.CODEC` byte/field-compatible. Build views around decoded objects.
2. **Keep views immutable.** A view is a read-only projection, not a live settings editor and not a command override store.
3. **Preserve override precedence.** Realtime command overrides, Voxy nullable overrides and DH API overrides remain in their existing managers. The view reports configured values; runtime capability reports effective values.
4. **Preserve legacy fallbacks.** Missing Cube-Net realtime fields continue to read `tellus.cubeNet.*` properties. Missing DH/Voxy/managed capability continues to select the current legacy branch, never an invented disabled setting.
5. **Preserve generator ownership.** Earth remains the owner of Earth-only OSM/preload/managed/Voxy policy; Cube-Net remains the owner of seam/apron policy; Sphere-Face remains the owner of radial terrain and grouped face routing.
6. **Preserve runtime registration order.** Replacing `TellusCommon` checks must not double-tick a manager or install duplicate Cube-Net/Sphere-Face lifecycle hooks. Adapters should be introduced behind a registry with an identity guard.
7. **Preserve thread contracts.** Runtime ticks stay on the server thread; source downloads, weather and LOD sampling remain asynchronous; no adapter may synchronously wait on remote data from a lifecycle/tick callback.
8. **Preserve failure behavior.** Incomplete sphere groups remain unroutable; unavailable DH gates remain legacy downloads; managed preload failures remain retryable; missing elevation remains an explicit generation failure rather than a synthetic sea level.
9. **Preserve version compatibility.** The view and runtime interfaces belong in the common source layer. Minecraft-version-specific method signatures stay in version bridges/adapters, as with `TellusChunkGeneratorBridge` and `TellusRuntimePlatform`.
10. **Validate parity before replacing branches.** For each generator family compare settings fingerprints, height limits, sea level, capability presence, realtime decisions, DH path selection, Voxy effective values and managed availability against the current class-based path.

## 8. Suggested migration order

1. Add pure view implementations and unit tests for Earth, Cube-Net and Sphere-Face codec fixtures. Assert fingerprints and effective values against existing getters/defaults.
2. Add a pure runtime registry with `owns` classification and no lifecycle takeover. Verify that every current generator selects exactly one adapter and unrelated vanilla generators select none.
3. Add Earth optional capability adapters around existing managers. Keep `TellusCommon` registrations authoritative and compare snapshots in tests.
4. Add Cube-Net seam-navigation adapter and Sphere-Face face-routing adapter as delegating wrappers. Keep their existing static hooks/payloads and test idempotent attach/detach.
5. Update `TellusRealtimeManager` to consume a settings view/capability resolver while retaining the exact Earth/null/system-property precedence.
6. Update DH/Voxy/managed classification one branch at a time, retaining the old `instanceof` fallback for unsupported loader/version modules.
7. Only after parity evidence, move manager dispatch into `TellusGeneratorRuntime.onServerTick`; never merge runtime overrides into generator settings or alter persisted codecs as part of this abstraction.

## 9. Non-goals and open decisions

- This design does not make Sphere-Face an Earth feature-policy generator.
- This design does not add live settings editing or world-safe hot reload. Generator settings remain effectively immutable for a loaded level.
- This design does not merge DH configuration overrides, Voxy config-file values or managed coverage into serialized world settings.
- A future client-side runtime interface may be useful for Cube-Net/Sphere-Face rendering and frame payloads, but this document covers the server/generator boundary only.
- The exact package and factory names are intentionally left to the implementation patch. The behavioral contracts, optional-capability semantics and compatibility rules above are the required design.
