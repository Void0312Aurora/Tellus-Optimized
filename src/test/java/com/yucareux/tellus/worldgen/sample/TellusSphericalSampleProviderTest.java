package com.yucareux.tellus.worldgen.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.TellusWorldgenSourcesProviderFactory;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Focused injected spherical-provider routing contracts. */
class TellusSphericalSampleProviderTest {
    private static final double SCALE = 1.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:spherical", "spherical-v1", "cube-net", "noise-v1", SCALE);

    @Test
    void cubeNetAndSphereFaceFactoriesForwardOwnedRequests() {
        AtomicInteger calls = new AtomicInteger();
        TellusSampleProvider delegate = provider(request -> {
            calls.incrementAndGet();
            return complete(request, "injected-spherical");
        });
        TellusSampleProvider cubeProvider = TellusSampleProviderFactory.forCubeNet(delegate);
        TellusSampleProvider sphereProvider = TellusWorldgenSourcesProviderFactory.forSphereFace(delegate);
        TellusSampleRequest cubeRequest = request(cube(), SamplingPurpose.BIOME, 11L);
        TellusSampleRequest sphereRequest = request(spherical(0, 32, 32), SamplingPurpose.SURFACE, 12L);

        TellusSample cube = cubeProvider.sample(cubeRequest);
        TellusSample sphere = sphereProvider.sample(sphereRequest);

        assertEquals(SampleStatus.AVAILABLE, cube.status());
        assertEquals(SampleStatus.AVAILABLE, sphere.status());
        assertSame(cubeRequest, cube.request());
        assertSame(sphereRequest, sphere.request());
        assertEquals("tellus:cube-net-spherical", cubeProvider.providerId());
        assertEquals("tellus:sphere-face-spherical", sphereProvider.providerId());
        assertEquals(2, calls.get());
    }

    @Test
    void ownershipMismatchReturnsUnknownWithoutCallingInjectedProvider() {
        AtomicInteger calls = new AtomicInteger();
        TellusSampleProvider delegate = provider(request -> {
            calls.incrementAndGet();
            return complete(request, "should-not-run");
        });
        TellusSampleProvider cubeProvider = TellusSampleProviderFactory.forCubeNet(delegate);
        TellusSampleProvider sphereProvider = TellusSampleProviderFactory.forSphereFace(delegate);
        TellusSampleRequest earthRequest = request(earth(), SamplingPurpose.BIOME, 13L);
        TellusSampleRequest cubeRequest = request(cube(), SamplingPurpose.BIOME, 14L);

        TellusSample cubeMismatch = cubeProvider.sample(earthRequest);
        TellusSample sphereMismatch = sphereProvider.sample(cubeRequest);

        assertEquals(SampleStatus.UNKNOWN, cubeMismatch.status());
        assertEquals(SampleStatus.UNKNOWN, sphereMismatch.status());
        assertSame(earthRequest, cubeMismatch.request());
        assertSame(cubeRequest, sphereMismatch.request());
        assertEquals(0, calls.get());
    }

    @Test
    void unknownAndQueuedDelegateResultsRemainObservableWithoutScheduling() {
        AtomicInteger calls = new AtomicInteger();
        TellusSampleProvider delegate = provider(request -> {
            calls.incrementAndGet();
            return request.nonBlocking()
                ? TellusSample.queued(request, "injected", "r1", "queued fixture")
                : TellusSample.unknown(request, "unknown fixture");
        });
        TellusSampleProvider sphereProvider = TellusSampleProviderFactory.forSphereFace(delegate);
        TellusSampleRequest exact = request(spherical(0, 32, 32), SamplingPurpose.BIOME, 15L);
        TellusSampleRequest nonBlocking = request(
            spherical(0, 32, 32), SamplingPurpose.BIOME_LOCATE, 16L, SamplingResolution.nonBlocking(SCALE));

        TellusSample unknown = sphereProvider.sample(exact);
        TellusSample queued = sphereProvider.sample(nonBlocking);

        assertEquals(SampleStatus.UNKNOWN, unknown.status());
        assertEquals(SampleStatus.QUEUED, queued.status());
        assertEquals("unknown fixture", unknown.provenance().note());
        assertEquals("injected", queued.provenance().sourceId());
        assertTrue(queued.provenance().queued());
        assertEquals(2, calls.get());
    }

    @Test
    void injectedSphereChunkSamplerMapsDeterministicSurfaceAndWaterData() {
        AtomicInteger calls = new AtomicInteger();
        SphereChunkSampler sampler = new SphereChunkSampler(MODEL, (point, resolution) -> {
            calls.incrementAndGet();
            return new SphereChunkSampler.SurfaceData(
                12.5, 30, 18.0, SphereChunkSampler.SurfaceData.WaterKind.OCEAN);
        });
        TellusSampleProvider provider = TellusSampleProviderFactory.forSphereFace(sampler);
        TellusSampleRequest request = request(spherical(0, 32, 32), SamplingPurpose.SURFACE, 18L);

        TellusSample first = provider.sample(request);
        TellusSample second = provider.sample(request);

        assertEquals(first, second);
        assertEquals(SampleStatus.AVAILABLE, first.status());
        assertEquals(12.5, first.elevation().metres());
        assertEquals(30, first.cover().rawClass());
        assertEquals(30, first.cover().visualClass());
        assertTrue(first.water().hasWater());
        assertTrue(first.water().ocean());
        assertEquals(18.0, first.water().surfaceMetres());
        assertEquals(2, calls.get());
        assertEquals("tellus:sphere-face-spherical", provider.providerId());
    }

    @Test
    void injectedSphereChunkSamplerQueuesNonBlockingWithoutCallingSampler() {
        AtomicInteger calls = new AtomicInteger();
        SphereChunkSampler sampler = new SphereChunkSampler(MODEL, (point, resolution) -> {
            calls.incrementAndGet();
            return new SphereChunkSampler.SurfaceData(8.0, 30);
        });
        TellusSampleProvider provider = TellusWorldgenSourcesProviderFactory.forCubeNet(sampler);
        TellusSampleRequest request = request(
            cube(), SamplingPurpose.BIOME_LOCATE, 19L, SamplingResolution.nonBlocking(SCALE));

        TellusSample result = provider.sample(request);

        assertEquals(SampleStatus.QUEUED, result.status());
        assertTrue(result.provenance().queued());
        assertEquals("tellus:cube-net-spherical", result.provenance().sourceId());
        assertEquals(0, calls.get());
    }
    @Test
    void delegateRequestIdentityIsChecked() {
        TellusSampleProvider delegate = provider(request -> TellusSample.unknown(
            request(spherical(0, 33, 32), SamplingPurpose.BIOME, 99L), "wrong request"));
        TellusSampleProvider sphereProvider = TellusSampleProviderFactory.forSphereFace(delegate);
        TellusSampleRequest request = request(spherical(0, 32, 32), SamplingPurpose.BIOME, 17L);

        assertThrows(IllegalArgumentException.class, () -> sphereProvider.sample(request));
    }

    private static TellusSampleProvider provider(java.util.function.Function<TellusSampleRequest, TellusSample> function) {
        return new TellusSampleProvider() {
            @Override
            public String providerId() {
                return "fixture:injected-spherical";
            }

            @Override
            public TellusSample sample(TellusSampleRequest request) {
                return function.apply(request);
            }
        };
    }

    private static TellusSample complete(TellusSampleRequest request, String source) {
        return new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 64.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30), TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot(source, "r1", false, "complete"));
    }

    private static TellusSampleRequest request(
        TellusCoordinateContext coordinate, SamplingPurpose purpose, long seed
    ) {
        return request(coordinate, purpose, seed, SamplingResolution.exact(SCALE));
    }

    private static TellusSampleRequest request(
        TellusCoordinateContext coordinate, SamplingPurpose purpose, long seed,
        SamplingResolution resolution
    ) {
        return TellusSampleRequest.fromContext(coordinate, purpose, resolution, GEOMETRY, seed);
    }

    private static TellusCoordinateContext spherical(int face, long blockX, long blockZ) {
        return TellusCoordinateContextFactory.fromSphericalBlock(
            MODEL, new SphereWorldModel.SphereBlockAddress(face, blockX, blockZ));
    }

    private static TellusCoordinateContext cube() {
        return TellusCoordinateContextFactory.fromCubeNetCanonical(
            MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
            new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 32.5));
    }

    private static TellusCoordinateContext earth() {
        SphereWorldModel.GeoPoint geo = new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0);
        return TellusCoordinateContextFactory.fromGeo(
            MODEL, geo, new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo, SCALE)));
    }
}
