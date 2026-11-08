package com.yucareux.tellus.worldgen.sample;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.TellusWorldgenSourcesProviderFactory;
import com.yucareux.tellus.worldgen.TellusWorldgenSourcesSampleBackend;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;

/** Contract tests for the pure Tellus sample request/result value objects. */
class TellusSampleContractTest {
    private static final double WORLD_SCALE = 1_000.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(WORLD_SCALE);

    @Test
    void coordinateTokenIsStructuredAndDerivedFromOwnerView() {
        CoordinateToken spherical = CoordinateToken.fromContext(sphericalContext(32L, 32L));
        assertEquals(GeneratorOwner.SPHERE_FACE, spherical.owner());
        assertEquals(0, spherical.face());
        assertEquals(32L, spherical.blockX());
        assertEquals(32L, spherical.blockZ());
        assertEquals(2L, spherical.chunkX());
        assertEquals(2L, spherical.chunkZ());
        assertEquals(0L, spherical.viewXBits());
        assertEquals(0L, spherical.viewZBits());

        TellusCoordinateContext earthContext = TellusCoordinateContextFactory.fromGeo(
            MODEL,
            new SphereWorldModel.GeoPoint(20.0, 30.0, 10.0),
            new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(
                new SphereWorldModel.GeoPoint(20.0, 30.0, 10.0), WORLD_SCALE)));
        CoordinateToken earth = CoordinateToken.fromContext(earthContext);
        assertEquals(GeneratorOwner.EARTH_PROJECTED, earth.owner());
        assertEquals(-1, earth.face());
        assertEquals(Long.MIN_VALUE, earth.chunkX());
        assertEquals(Long.MIN_VALUE, earth.chunkZ());
        assertEquals(earth.earthX(), earth.blockX());
        assertEquals(earth.earthZ(), earth.blockZ());

        CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
        TellusCoordinateContext cube = TellusCoordinateContextFactory.fromCubeNetCanonical(
            MODEL, layout, new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 0.5, 0.5));
        CoordinateToken canonical = CoordinateToken.fromContext(cube);
        assertEquals(GeneratorOwner.CUBE_NET_CANONICAL, canonical.owner());
        assertEquals(0, canonical.face());
        assertEquals(Double.doubleToLongBits(cube.planarPoint().orElseThrow().x()), canonical.viewXBits());
        assertEquals(Double.doubleToLongBits(cube.planarPoint().orElseThrow().z()), canonical.viewZBits());
    }

    @Test
    void injectedBackendsUseDeterministicFirstMatchPrecedence() {
        TellusSampleRequest request = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 30L);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        SnapshotSampleBackend first = new SnapshotSampleBackend(
            "first", ignored -> true, ignored -> {
                firstCalls.incrementAndGet();
                return availableSample(request);
            });
        SnapshotSampleBackend second = new SnapshotSampleBackend(
            "second", ignored -> true, ignored -> {
                secondCalls.incrementAndGet();
                return availableSample(request);
            });
        TellusLegacySampleProviderAdapter adapter = new TellusLegacySampleProviderAdapter(
            new OrderedSampleBackendSelector(List.of(first, second)));

        TellusSample result = adapter.sample(request);

        assertEquals(SampleStatus.AVAILABLE, result.status());
        assertEquals(1, firstCalls.get());
        assertEquals(0, secondCalls.get());
        assertEquals("tellus:legacy", adapter.providerId());
    }

    @Test
    void injectedBackendReceivesExactRequestIdentityAndMismatchedResultIsRejected() {
        TellusSampleRequest request = request(
            sphericalContext(32L, 32L), SamplingPurpose.PRELOAD,
            SamplingResolution.exact(WORLD_SCALE), 31L);
        AtomicReference<TellusSampleRequest> seen = new AtomicReference<>();
        SnapshotSampleBackend backend = new SnapshotSampleBackend(
            "identity", ignored -> true, received -> {
                seen.set(received);
                return availableSample(received);
            });
        TellusLegacySampleProviderAdapter adapter = new TellusLegacySampleProviderAdapter(
            new OrderedSampleBackendSelector(List.of(backend)));

        TellusSample result = adapter.sample(request);

        assertSame(request, seen.get());
        assertSame(request, result.request());

        TellusSampleRequest other = request(
            sphericalContext(32L, 33L), SamplingPurpose.PRELOAD,
            SamplingResolution.exact(WORLD_SCALE), 31L);
        SnapshotSampleBackend mismatched = SnapshotSampleBackend.fixed(
            "mismatch", ignored -> true, TellusSample.unknown(other, "wrong request"));
        TellusLegacySampleProviderAdapter rejecting = new TellusLegacySampleProviderAdapter(
            new OrderedSampleBackendSelector(List.of(mismatched)));
        assertThrows(IllegalArgumentException.class, () -> rejecting.sample(request));
    }

    @Test
    void adapterPreservesUnknownPartialQueuedStatusesAndProvenance() {
        TellusSampleRequest exact = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 32L);
        TellusSampleRequest nonBlocking = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME_LOCATE,
            SamplingResolution.nonBlocking(WORLD_SCALE), 32L);
        TellusSample unknown = TellusSample.unknown(exact, "synthetic unknown");
        TellusSample partial = TellusSample.partial(exact, "synthetic partial");
        TellusSample queued = TellusSample.queued(
            nonBlocking, "synthetic", "r1", "synthetic queued");

        assertSame(unknown, new TellusLegacySampleProviderAdapter(
            new OrderedSampleBackendSelector(List.of(SnapshotSampleBackend.fixed(
                "unknown", ignored -> true, unknown)))).sample(exact));
        assertSame(partial, new TellusLegacySampleProviderAdapter(
            new OrderedSampleBackendSelector(List.of(SnapshotSampleBackend.fixed(
                "partial", ignored -> true, partial)))).sample(exact));
        assertSame(queued, new TellusLegacySampleProviderAdapter(
            new OrderedSampleBackendSelector(List.of(SnapshotSampleBackend.fixed(
                "queued", ignored -> true, queued)))).sample(nonBlocking));
        assertEquals("synthetic unknown", unknown.provenance().note());
        assertEquals("synthetic partial", partial.provenance().note());
        assertTrue(queued.provenance().queued());
    }

    @Test
    void nonBlockingBackendReadDoesNotScheduleOrFallback() {
        TellusSampleRequest request = request(
            sphericalContext(32L, 32L), SamplingPurpose.PRELOAD,
            SamplingResolution.nonBlocking(WORLD_SCALE), 33L);
        AtomicInteger backendCalls = new AtomicInteger();
        AtomicInteger scheduleCalls = new AtomicInteger();
        SnapshotSampleBackend backend = new SnapshotSampleBackend(
            "nonblocking", TellusSampleRequest::nonBlocking, received -> {
                backendCalls.incrementAndGet();
                if (!received.nonBlocking()) scheduleCalls.incrementAndGet();
                return TellusSample.queued(received, "nonblocking", "r1", "snapshot pending");
            });
        TellusLegacySampleProviderAdapter adapter = new TellusLegacySampleProviderAdapter(
            new OrderedSampleBackendSelector(List.of(backend)));

        TellusSample result = adapter.sample(request);

        assertEquals(SampleStatus.QUEUED, result.status());
        assertEquals(1, backendCalls.get());
        assertEquals(0, scheduleCalls.get());
        assertTrue(result.provenance().queued());
    }


    @Test
    void worldgenSourcesBackendRoutesOwnersAndGuardsEarthRequests() {
        TellusWorldgenSourcesSampleBackend backend = new TellusWorldgenSourcesSampleBackend();
        double earthScale = EarthGeneratorSettings.DEFAULT.worldScale();
        TellusSampleRequest earth = requestWithGeometry(
            earthContext(earthScale, 0.0, 0.0), SamplingPurpose.BIOME_LOCATE,
            SamplingResolution.exact(earthScale),
            new GeometryIdentity("tellus:earth", "source-v1", "mercator", "noise-v1", earthScale),
            35L);
        TellusSampleRequest sphere = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 35L);
        CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
        TellusSampleRequest cube = requestWithGeometry(
            TellusCoordinateContextFactory.fromCubeNetCanonical(
                MODEL, layout, new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 0.5, 0.5)),
            SamplingPurpose.BIOME_LOCATE, SamplingResolution.exact(WORLD_SCALE),
            new GeometryIdentity("tellus:cube", "source-v1", "cubed-sphere", "noise-v1", WORLD_SCALE),
            35L);
        TellusSampleRequest wrongScale = requestWithGeometry(
            earthContext(WORLD_SCALE, 0.0, 0.0), SamplingPurpose.PRELOAD,
            SamplingResolution.exact(WORLD_SCALE),
            new GeometryIdentity("tellus:earth", "source-v1", "mercator", "noise-v1", WORLD_SCALE),
            35L);
        TellusSampleRequest nonBlockingWrongScale = requestWithGeometry(
            earthContext(WORLD_SCALE, 0.0, 0.0), SamplingPurpose.PRELOAD,
            SamplingResolution.nonBlocking(WORLD_SCALE),
            new GeometryIdentity("tellus:earth", "source-v1", "mercator", "noise-v1", WORLD_SCALE),
            35L);

        assertEquals(TellusWorldgenSourcesSampleBackend.PROVIDER_ID, backend.backendId());
        assertEquals(TellusWorldgenSourcesSampleBackend.PROVIDER_ID, backend.providerId());
        assertTrue(backend.supports(earth));
        assertFalse(backend.supports(sphere));
        assertFalse(backend.supports(cube));

        TellusSample scaleResult = backend.sample(wrongScale);
        assertEquals(SampleStatus.UNKNOWN, scaleResult.status());
        assertSame(wrongScale, scaleResult.request());
        assertFalse(scaleResult.provenance().snapshot());
        assertFalse(scaleResult.provenance().queued());
        assertTrue(scaleResult.provenance().note().contains("scale does not match"));
        assertEquals(SamplingPurpose.PRELOAD, scaleResult.request().purpose());
        assertEquals(SamplingResolution.exact(WORLD_SCALE), scaleResult.request().resolution());

        TellusSample nonBlockingResult = backend.sample(nonBlockingWrongScale);
        assertTrue(nonBlockingResult.status() == SampleStatus.UNKNOWN
            || nonBlockingResult.status() == SampleStatus.QUEUED);
        assertSame(nonBlockingWrongScale, nonBlockingResult.request());
        assertEquals(SamplingResolution.nonBlocking(WORLD_SCALE), nonBlockingResult.request().resolution());
        if (nonBlockingResult.status() == SampleStatus.UNKNOWN) {
            assertFalse(nonBlockingResult.provenance().queued());
        } else {
            assertTrue(nonBlockingResult.provenance().queued());
        }
    }


    @Test
    void providerFactoryUsesSyntheticPrecedenceAndExplicitFallbackRoutes() {
        double earthScale = EarthGeneratorSettings.DEFAULT.worldScale();
        GeometryIdentity earthGeometry = new GeometryIdentity(
            "tellus:earth", "factory-v1", "mercator", "noise-v1", earthScale);
        TellusSampleRequest earth = requestWithGeometry(
            earthContext(earthScale, 0.0, 0.0), SamplingPurpose.PRELOAD,
            SamplingResolution.exact(earthScale), earthGeometry, 40L);
        TellusSampleRequest nonBlockingEarth = requestWithGeometry(
            earthContext(earthScale, 0.0, 0.0), SamplingPurpose.PRELOAD,
            SamplingResolution.nonBlocking(earthScale), earthGeometry, 40L);
        TellusSampleRequest wrongScale = requestWithGeometry(
            earthContext(WORLD_SCALE, 0.0, 0.0), SamplingPurpose.PRELOAD,
            SamplingResolution.exact(WORLD_SCALE),
            new GeometryIdentity("tellus:earth", "factory-v1", "mercator", "noise-v1", WORLD_SCALE),
            40L);
        TellusSampleRequest sphere = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 40L);
        CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
        TellusSampleRequest cube = requestWithGeometry(
            TellusCoordinateContextFactory.fromCubeNetCanonical(
                MODEL, layout, new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 0.5, 0.5)),
            SamplingPurpose.BIOME_LOCATE, SamplingResolution.exact(WORLD_SCALE),
            new GeometryIdentity("tellus:cube", "factory-v1", "cubed-sphere", "noise-v1", WORLD_SCALE),
            40L);
        SnapshotSampleBackend synthetic = new SnapshotSampleBackend(
            "synthetic-preload", ignored -> true, received -> received.nonBlocking()
                ? TellusSample.queued(received, "synthetic-preload", "snapshot-v1", "synthetic queued")
                : TellusSample.partial(received, "synthetic partial"));

        System.setProperty("tellus.gameDir", "build/tmp/tellus-sample-factory");
        System.setProperty("tellus.configDir", "build/tmp/tellus-sample-factory/config");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TellusSampleProvider composed = TellusWorldgenSourcesProviderFactory.forEarth(
            EarthGeneratorSettings.DEFAULT, List.of(synthetic));
        TellusSample prioritized = composed.sample(earth);
        TellusSample queued = composed.sample(nonBlockingEarth);
        TellusSample wrongScaleResult = TellusWorldgenSourcesProviderFactory
            .forEarth(EarthGeneratorSettings.DEFAULT).sample(wrongScale);
        TellusSample sphereResult = TellusWorldgenSourcesProviderFactory
            .forEarth(EarthGeneratorSettings.DEFAULT).sample(sphere);
        TellusSample cubeResult = TellusWorldgenSourcesProviderFactory
            .forEarth(EarthGeneratorSettings.DEFAULT).sample(cube);

        assertEquals(SampleStatus.PARTIAL, prioritized.status());
        assertSame(earth, prioritized.request());
        assertEquals("synthetic partial", prioritized.provenance().note());
        assertTrue(prioritized.provenance().snapshot());
        assertFalse(prioritized.provenance().queued());
        assertEquals(SampleStatus.QUEUED, queued.status());
        assertSame(nonBlockingEarth, queued.request());
        assertEquals("synthetic-preload", queued.provenance().sourceId());
        assertTrue(queued.provenance().queued());
        assertFalse(queued.provenance().snapshot());
        assertEquals(SampleStatus.UNKNOWN, wrongScaleResult.status());
        assertSame(wrongScale, wrongScaleResult.request());
        assertEquals(SampleStatus.UNKNOWN, sphereResult.status());
        assertEquals(SampleStatus.UNKNOWN, cubeResult.status());
        assertSame(sphere, sphereResult.request());
        assertSame(cube, cubeResult.request());
        assertFalse(sphereResult.provenance().queued());
        assertFalse(cubeResult.provenance().queued());
    }


    @Test
    void requestCacheKeyContainsEveryCacheRelevantDimension() {
        TellusSampleRequest first = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 11L);
        TellusSampleRequest same = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 11L);

        assertEquals(first, same);
        assertEquals(first.cacheKey(), same.cacheKey());
        assertTrue(first.cacheKey().matches(first.geometry(), first.purpose(), first.resolution()));
        assertEquals(first.cacheKey().coordinate(), CoordinateToken.fromContext(first.coordinate()));

        assertNotEquals(first.cacheKey(), request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME_LOCATE,
            SamplingResolution.exact(WORLD_SCALE), 11L).cacheKey());
        assertNotEquals(first.cacheKey(), request(
            sphericalContext(32L, 32L), SamplingPurpose.PRELOAD,
            SamplingResolution.exact(WORLD_SCALE), 11L).cacheKey());
        assertNotEquals(first.cacheKey(), request(
            sphericalContext(32L, 32L), SamplingPurpose.SURFACE,
            SamplingResolution.preview(WORLD_SCALE * 2.0), 11L).cacheKey());
        assertNotEquals(first.cacheKey(), request(
            sphericalContext(32L, 33L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 11L).cacheKey());
        assertNotEquals(first.cacheKey(), request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 12L).cacheKey());

        GeometryIdentity differentGeometry = new GeometryIdentity("tellus:test", "revision-2", WORLD_SCALE);
        CacheKey wrongCoordinate = CacheKey.fromContext(
            sphericalContext(32L, 33L), differentGeometry, first.purpose(), first.resolution(), 11L);
        assertThrows(IllegalArgumentException.class, () -> new TellusSampleRequest(
            first.coordinate(), first.purpose(), first.resolution(), first.geometry(), wrongCoordinate));
    }

    @Test
    void samplingResolutionQuantizesUpwardToStablePowerOfTwoBuckets() {
        assertEquals(1, SamplingResolution.exact(0.1).metres());
        assertEquals(1024, SamplingResolution.of(
            SamplingResolution.Kind.PREVIEW, 1_000.0).metres());
        assertEquals(2048, SamplingResolution.preview(1_025.0).metres());
        assertEquals("PREVIEW:1024", SamplingResolution.preview(1_000.0).quantizedKey());
        assertEquals(SamplingResolution.exact(1_000.0), SamplingResolution.exact(1_024.0));
        assertThrows(IllegalArgumentException.class,
            () -> SamplingResolution.of(SamplingResolution.Kind.EXACT, 0.0));
        assertThrows(IllegalArgumentException.class,
            () -> SamplingResolution.of(SamplingResolution.Kind.EXACT, Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> new SamplingResolution(SamplingResolution.Kind.EXACT, 3));
    }


    @Test
    void geometryIdentityRevisionAndStableKeysIncludeProjectionAndNoiseVersions() {
        GeometryIdentity identity = new GeometryIdentity(
            "tellus:earth", "geometry-v3", "mercator", "noise-v2-round", WORLD_SCALE);

        assertEquals("geometry-v3|projection=mercator|noise=noise-v2-round", identity.revisionKey());
        assertEquals(
            "tellus:earth@geometry-v3|projection=mercator|noise=noise-v2-round|scale=1000.0",
            identity.stableKey());
        assertEquals(
            new GeometryIdentity("tellus:earth", "geometry-v3", WORLD_SCALE).revisionKey(),
            "geometry-v3|projection=unknown-projection|noise=unknown-noise");

        assertNotEquals(identity, new GeometryIdentity(
            "tellus:earth", "geometry-v4", "mercator", "noise-v2-round", WORLD_SCALE));
        assertNotEquals(identity, new GeometryIdentity(
            "tellus:earth", "geometry-v3", "legacy", "noise-v2-round", WORLD_SCALE));
        assertNotEquals(identity, new GeometryIdentity(
            "tellus:earth", "geometry-v3", "mercator", "noise-v3-round", WORLD_SCALE));
        assertNotEquals(identity, new GeometryIdentity(
            "tellus:earth", "geometry-v3", "mercator", "noise-v2-round", 2_000.0));

        assertThrows(IllegalArgumentException.class,
            () -> new GeometryIdentity("tellus|earth", "geometry-v3", "mercator", "noise", WORLD_SCALE));
        assertThrows(IllegalArgumentException.class,
            () -> new GeometryIdentity("tellus:earth", "geometry=v3", "mercator", "noise", WORLD_SCALE));
        assertThrows(IllegalArgumentException.class,
            () -> new GeometryIdentity("tellus:earth", "geometry-v3", "projection|v1", "noise", WORLD_SCALE));
        assertThrows(IllegalArgumentException.class,
            () -> new GeometryIdentity("tellus:earth", "geometry-v3", "mercator", "noise=v1", WORLD_SCALE));
    }

    @Test
    void partialLayerFactoriesExposeExplicitSentinels() {
        TellusElevationSample missingElevation = TellusElevationSample.partial();
        assertEquals(SampleStatus.PARTIAL, missingElevation.status());
        assertTrue(Double.isNaN(missingElevation.metres()));
        TellusElevationSample measuredElevation = TellusElevationSample.partial(12.5);
        assertEquals(SampleStatus.PARTIAL, measuredElevation.status());
        assertEquals(12.5, measuredElevation.metres());

        TellusCoverSample missingCover = TellusCoverSample.partial();
        assertEquals(SampleStatus.PARTIAL, missingCover.status());
        assertEquals(Integer.MIN_VALUE, missingCover.rawClass());
        assertEquals(Integer.MIN_VALUE, missingCover.visualClass());
        TellusCoverSample classifiedCover = TellusCoverSample.partial(10, 30);
        assertEquals(10, classifiedCover.rawClass());
        assertEquals(30, classifiedCover.visualClass());

        TellusWaterSample missingWater = TellusWaterSample.partial();
        assertEquals(SampleStatus.PARTIAL, missingWater.status());
        assertFalse(missingWater.hasWater());
        assertTrue(Double.isNaN(missingWater.surfaceMetres()));
        TellusWaterSample measuredWater = TellusWaterSample.partial(true, true, 64.0);
        assertEquals(SampleStatus.PARTIAL, measuredWater.status());
        assertTrue(measuredWater.hasWater());
        assertTrue(measuredWater.ocean());
        assertEquals(64.0, measuredWater.surfaceMetres());

        TellusSample aggregate = TellusSample.partial(
            request(sphericalContext(32L, 32L), SamplingPurpose.BIOME,
                SamplingResolution.exact(WORLD_SCALE), 22L),
            "partial sentinel");
        assertEquals(SampleStatus.PARTIAL, aggregate.status());
        assertEquals(SampleStatus.PARTIAL, aggregate.elevation().status());
        assertEquals(SampleStatus.PARTIAL, aggregate.cover().status());
        assertEquals(SampleStatus.PARTIAL, aggregate.water().status());
        assertTrue(Double.isNaN(aggregate.elevation().metres()));
        assertEquals(Integer.MIN_VALUE, aggregate.cover().rawClass());
        assertTrue(Double.isNaN(aggregate.water().surfaceMetres()));
    }

    @Test
    void geometryIdentityConcreteProviderGuardAndLegacyNormalization() {
        GeometryIdentity legacy = new GeometryIdentity("tellus:earth", "geometry-v3", WORLD_SCALE);
        assertFalse(legacy.concrete());
        assertThrows(IllegalArgumentException.class, legacy::requireConcrete);
        GeometryIdentity normalized = legacy.forRequest();
        assertTrue(normalized.concrete());
        assertEquals("legacy-projection-v1", normalized.projectionId());
        assertEquals("legacy-noise-v1", normalized.noiseAlgorithmId());
        assertSame(normalized, normalized.forRequest());

        GeometryIdentity concrete = GeometryIdentity.forProvider(
            "tellus:earth", "geometry-v4", "mercator", "noise-v2-round", WORLD_SCALE);
        assertTrue(concrete.concrete());
        assertSame(concrete, concrete.requireConcrete());
        assertThrows(IllegalArgumentException.class, () -> GeometryIdentity.forProvider(
            "tellus:earth", "geometry-v4", "unknown-projection", "noise-v2-round", WORLD_SCALE));
        assertThrows(IllegalArgumentException.class, () -> GeometryIdentity.forProvider(
            "tellus:earth", "geometry-v4", "mercator", "UNKNOWN", WORLD_SCALE));

        TellusSampleRequest request = TellusSampleRequest.fromContext(
            sphericalContext(32L, 32L), SamplingPurpose.PRELOAD,
            SamplingResolution.exact(WORLD_SCALE), legacy, 22L);
        assertEquals(normalized, request.geometry());
        assertEquals(normalized, request.cacheKey().geometry());
        assertEquals(normalized.stableKey(), request.cacheKey().geometry().stableKey());
    }


    @Test
    void geometryRevisionChangesCacheIdentityAndRequestConsistency() {
        TellusCoordinateContext coordinate = sphericalContext(32L, 32L);
        SamplingResolution resolution = SamplingResolution.exact(WORLD_SCALE);
        GeometryIdentity firstGeometry = new GeometryIdentity(
            "tellus:earth", "geometry-v3", "mercator", "noise-v2-round", WORLD_SCALE);
        GeometryIdentity secondGeometry = new GeometryIdentity(
            "tellus:earth", "geometry-v4", "mercator", "noise-v2-round", WORLD_SCALE);
        CacheKey firstKey = CacheKey.fromContext(
            coordinate, firstGeometry, SamplingPurpose.BIOME, resolution, 20L);
        CacheKey secondKey = CacheKey.fromContext(
            coordinate, secondGeometry, SamplingPurpose.BIOME, resolution, 20L);

        assertNotEquals(firstKey, secondKey);
        assertNotEquals(firstKey.geometry(), secondKey.geometry());
        TellusSampleRequest first = TellusSampleRequest.fromContext(
            coordinate, SamplingPurpose.BIOME, resolution, firstGeometry, 20L);
        TellusSampleRequest second = TellusSampleRequest.fromContext(
            coordinate, SamplingPurpose.BIOME, resolution, secondGeometry, 20L);
        assertNotEquals(first.cacheKey(), second.cacheKey());
        assertEquals(firstGeometry.revisionKey(), first.cacheKey().geometry().revisionKey());
        assertEquals(secondGeometry.stableKey(), second.cacheKey().geometry().stableKey());
    }

    @Test
    void consistencyValidatorAcceptsValidStatesAndRejectsMismatchedLayersOrProvenance() {
        TellusSampleRequest exact = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 21L);
        TellusSampleRequest nonBlocking = request(
            sphericalContext(32L, 32L), SamplingPurpose.PRELOAD,
            SamplingResolution.nonBlocking(WORLD_SCALE), 21L);
        TellusSample validAvailable = availableSample(exact);
        TellusSample validPartial = TellusSample.partial(exact, "one layer pending");
        TellusSample validUnknown = TellusSample.unknown(exact, "no snapshot");
        TellusSample validQueued = TellusSample.queued(
            nonBlocking, "fixture", "r1", "queued");

        assertDoesNotThrow(() -> TellusSampleConsistencyValidator.validate(validAvailable));
        assertDoesNotThrow(() -> TellusSampleConsistencyValidator.validate(validPartial));
        assertDoesNotThrow(() -> TellusSampleConsistencyValidator.validate(validUnknown));
        assertDoesNotThrow(() -> TellusSampleConsistencyValidator.validate(validQueued));

        TellusSampleProvenance snapshot = TellusSampleProvenance.snapshot(
            "fixture", "r1", false, "snapshot");
        assertThrows(IllegalArgumentException.class, () -> new TellusSample(
            exact, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.PARTIAL, 1.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 10, 10),
            new TellusWaterSample(SampleStatus.AVAILABLE, false, false, Double.NaN),
            snapshot));
        assertThrows(IllegalArgumentException.class, () -> new TellusSample(
            exact, SampleStatus.PARTIAL,
            TellusElevationSample.unknown(), TellusCoverSample.unknown(),
            TellusWaterSample.unknown(), snapshot));
        assertThrows(IllegalArgumentException.class, () -> new TellusSample(
            exact, SampleStatus.UNKNOWN,
            TellusElevationSample.unknown(), TellusCoverSample.unknown(),
            TellusWaterSample.unknown(), TellusSampleProvenance.queued(
                "fixture", "r1", "queued provenance")));
        assertThrows(IllegalArgumentException.class, () -> new TellusSample(
            exact, SampleStatus.QUEUED,
            TellusElevationSample.queued(), TellusCoverSample.queued(),
            TellusWaterSample.queued(), TellusSampleProvenance.queued(
                "fixture", "r1", "blocking request")));
        assertThrows(IllegalArgumentException.class, () -> new TellusSample(
            nonBlocking, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 1.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 10, 10),
            TellusWaterSample.dry(),
            new TellusSampleProvenance("fixture", "r1", false, false, false, "live result")));
    }


    @Test
    void unknownSampleUsesExplicitLayerSentinelsAndProvenance() {
        TellusSampleRequest request = request(
            sphericalContext(32L, 32L), SamplingPurpose.TERRAIN_COLUMN,
            SamplingResolution.nonBlocking(WORLD_SCALE), 12L);

        TellusSample unknown = TellusSample.unknown(request, "source cache miss");

        assertEquals(SampleStatus.UNKNOWN, unknown.availability());
        assertFalse(unknown.known());
        assertFalse(unknown.complete());
        assertFalse(unknown.partial());
        assertFalse(unknown.queued());
        assertTrue(unknown.availability().unknown());
        assertEquals(SampleStatus.UNKNOWN, unknown.elevation().status());
        assertEquals(SampleStatus.UNKNOWN, unknown.cover().status());
        assertEquals(SampleStatus.UNKNOWN, unknown.water().status());
        assertTrue(Double.isNaN(unknown.elevation().metres()));
        assertEquals(Integer.MIN_VALUE, unknown.cover().rawClass());
        assertEquals(Integer.MIN_VALUE, unknown.cover().visualClass());
        assertTrue(Double.isNaN(unknown.water().surfaceMetres()));
        assertEquals("unknown", unknown.provenance().sourceId());
        assertEquals("unknown", unknown.provenance().sourceRevision());
        assertFalse(unknown.provenance().cacheHit());
        assertFalse(unknown.provenance().snapshot());
        assertFalse(unknown.provenance().queued());
        assertEquals("source cache miss", unknown.provenance().note());
    }

    @Test
    void partialAndAvailableSamplesPreserveLayerStatus() {
        TellusSampleRequest request = request(
            sphericalContext(32L, 32L), SamplingPurpose.SURFACE,
            SamplingResolution.preview(2_000.0), 13L);
        TellusSampleProvenance provenance = TellusSampleProvenance.snapshot(
            "fixture", "r1", true, "cover complete; water pending");
        TellusSample partial = new TellusSample(
            request,
            SampleStatus.PARTIAL,
            new TellusElevationSample(SampleStatus.PARTIAL, 123.5),
            new TellusCoverSample(SampleStatus.AVAILABLE, 10, 10),
            TellusWaterSample.unknown(),
            provenance);

        assertEquals(SampleStatus.PARTIAL, partial.availability());
        assertFalse(partial.known());
        assertTrue(partial.availability().available());
        assertTrue(partial.partial());
        assertFalse(partial.complete());
        assertTrue(partial.elevation().status().available());
        assertFalse(partial.elevation().known());
        assertTrue(partial.cover().known());
        assertFalse(partial.water().known());
        assertEquals(provenance, partial.provenance());

        TellusSample available = new TellusSample(
            request,
            SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 64.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30),
            new TellusWaterSample(SampleStatus.AVAILABLE, true, true, 65.0),
            TellusSampleProvenance.snapshot("fixture", "r1", false, "complete"));
        assertEquals(SampleStatus.AVAILABLE, available.availability());
        assertTrue(available.availability().available());
        assertTrue(available.availability().known());
        assertTrue(available.availability().complete());
        assertTrue(available.complete());
        assertSame(available, available.sampleRequired());
        assertTrue(available.water().known());
        assertTrue(available.water().hasWater());
        assertTrue(available.water().ocean());
    }

    @Test
    void queuedNonBlockingSamplesRemainExplicitAndRequiredAccessRejectsThem() {
        TellusSampleRequest request = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME_LOCATE,
            SamplingResolution.nonBlocking(WORLD_SCALE), 14L);
        TellusSample queued = TellusSample.queued(
            request, "fixture", "r2", "download queued");

        assertEquals(SampleStatus.QUEUED, queued.availability());
        assertFalse(queued.availability().available());
        assertFalse(queued.known());
        assertFalse(queued.complete());
        assertFalse(queued.partial());
        assertTrue(queued.queued());
        assertTrue(queued.availability().queued());
        assertEquals(SampleStatus.QUEUED, queued.elevation().status());
        assertEquals(SampleStatus.QUEUED, queued.cover().status());
        assertEquals(SampleStatus.QUEUED, queued.water().status());
        assertTrue(queued.provenance().queued());
        assertFalse(queued.provenance().snapshot());
        assertThrows(IllegalStateException.class, queued::sampleRequired);

        TellusSample unknown = TellusSample.unknown(request, "missing");
        assertThrows(IllegalStateException.class, unknown::sampleRequired);
        TellusSample partial = TellusSample.partial(request, "partial");
        assertThrows(IllegalStateException.class, partial::sampleRequired);
    }

    @Test
    void providerSampleRequiredRejectsNonCompleteResults() {
        TellusSampleRequest request = request(
            sphericalContext(32L, 32L), SamplingPurpose.BIOME,
            SamplingResolution.exact(WORLD_SCALE), 15L);
        TellusSample unknown = TellusSample.unknown(request, "missing");
        TellusSampleProvider provider = new TellusSampleProvider() {
            @Override
            public String providerId() {
                return "fixture";
            }

            @Override
            public TellusSample sample(TellusSampleRequest ignored) {
                return unknown;
            }
        };

        assertThrows(IllegalStateException.class, () -> provider.sampleRequired(request));
        assertThrows(NullPointerException.class, () -> provider.sampleRequired(null));
    }

    @Test
    void layerConstructorsRejectInvalidKnownValues() {
        assertThrows(IllegalArgumentException.class,
            () -> new TellusElevationSample(SampleStatus.AVAILABLE, Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> new TellusWaterSample(SampleStatus.AVAILABLE, true, false, Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> new TellusWaterSample(SampleStatus.AVAILABLE, false, true, 0.0));
        assertEquals(TellusWaterSample.dry(), new TellusWaterSample(
            SampleStatus.AVAILABLE, false, false, Double.NaN));
    }

    @Test
    void sampleContractsAreImmutableValueObjects() throws NoSuchMethodException {
        Class<?>[] records = {
            GeometryIdentity.class,
            SamplingResolution.class,
            CoordinateToken.class,
            CacheKey.class,
            TellusSampleRequest.class,
            TellusSampleProvenance.class,
            TellusElevationSample.class,
            TellusCoverSample.class,
            TellusWaterSample.class,
            TellusSample.class
        };
        for (Class<?> type : records) {
            assertTrue(type.isRecord(), type.getName());
            assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
            assertTrue(type.getRecordComponents().length > 0, type.getName());
            assertFalse(java.util.Arrays.stream(type.getMethods())
                .anyMatch(method -> method.getName().startsWith("set")), type.getName());
        }
        assertTrue(SampleStatus.class.isEnum());
        assertTrue(SamplingPurpose.class.isEnum());
        assertTrue(SamplingResolution.Kind.class.isEnum());
        assertTrue(TellusSampleProvider.class.isInterface());
        assertNotNull(TellusSampleProvider.class.getDeclaredMethod("sample", TellusSampleRequest.class));
    }

    private static TellusSample availableSample(TellusSampleRequest request) {
        return new TellusSample(
            request,
            SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 64.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30),
            TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("fixture", "r1", false, "complete"));
    }

    private static TellusSampleRequest request(
        TellusCoordinateContext coordinate,
        SamplingPurpose purpose,
        SamplingResolution resolution,
        long worldSeed
    ) {
        GeometryIdentity geometry = new GeometryIdentity("tellus:test", "revision-1", WORLD_SCALE);
        return TellusSampleRequest.fromContext(
            coordinate, purpose, resolution, geometry, worldSeed);
    }

    private static TellusSampleRequest requestWithGeometry(
        TellusCoordinateContext coordinate,
        SamplingPurpose purpose,
        SamplingResolution resolution,
        GeometryIdentity geometry,
        long worldSeed
    ) {
        return TellusSampleRequest.fromContext(
            coordinate, purpose, resolution, geometry, worldSeed);
    }

    private static TellusCoordinateContext earthContext(
        double scale,
        double latitude,
        double longitude
    ) {
        SphereWorldModel.GeoPoint geo = new SphereWorldModel.GeoPoint(latitude, longitude, 0.0);
        SphereWorldModel model = SphereWorldModel.forWorldScale(scale);
        EarthCompatibilityPoint earth = EarthCompatibilityPoint.fromGeoPoint(geo, scale);
        return TellusCoordinateContextFactory.fromGeo(
            model, geo, new EarthOwnerView(earth));
    }

    private static TellusCoordinateContext sphericalContext(long blockX, long blockZ) {
        return TellusCoordinateContextFactory.fromSphericalBlock(
            MODEL, new SphereWorldModel.SphereBlockAddress(0, blockX, blockZ));
    }
}
