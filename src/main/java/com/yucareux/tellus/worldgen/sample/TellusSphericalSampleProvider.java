package com.yucareux.tellus.worldgen.sample;

import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import java.util.Objects;

/**
 * Explicit route around an injected spherical sample provider.
 *
 * <p>The route only checks coordinate ownership and forwards the immutable
 * request/result. It does not schedule network work, refresh a cache, or
 * install a generator hook. Unknown and queued results from the injected
 * provider remain observable to the caller.</p>
 */
public final class TellusSphericalSampleProvider implements TellusSampleProvider {
    public enum Route { CUBE_NET, SPHERE_FACE }

    private final String providerId;
    private final Route route;
    private final TellusSampleProvider delegate;
    private final SphereChunkSampler sampler;
    private final boolean resolveProductionSurface;

    public TellusSphericalSampleProvider(
        String providerId, Route route, TellusSampleProvider delegate
    ) {
        this.providerId = requireText(providerId, "providerId");
        this.route = Objects.requireNonNull(route, "route");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sampler = null;
        this.resolveProductionSurface = false;
    }

    public TellusSphericalSampleProvider(
        String providerId, Route route, SphereChunkSampler sampler
    ) {
        this(providerId, route, sampler, false);
    }

    private TellusSphericalSampleProvider(
        String providerId, Route route, SphereChunkSampler sampler, boolean resolveProductionSurface
    ) {
        this.providerId = requireText(providerId, "providerId");
        this.route = Objects.requireNonNull(route, "route");
        this.delegate = null;
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.resolveProductionSurface = resolveProductionSurface;
    }

    public static TellusSphericalSampleProvider forCubeNetProduction(SphereChunkSampler sampler) {
        return new TellusSphericalSampleProvider("tellus:cube-net-production", Route.CUBE_NET, sampler, true);
    }

    public static TellusSphericalSampleProvider forSphereFaceProduction(SphereChunkSampler sampler) {
        return new TellusSphericalSampleProvider("tellus:sphere-face-production", Route.SPHERE_FACE, sampler, true);
    }

    /** Explicit native exact-column route; the resolver owns source/model/cache validation. */
    public static TellusSphericalSampleProvider forSphereFaceNativeColumns(
        java.util.function.Function<TellusSampleRequest, SphereChunkSampler.Column> columns
    ) {
        Objects.requireNonNull(columns, "columns");
        return new TellusSphericalSampleProvider("tellus:sphere-face-native", Route.SPHERE_FACE, new TellusSampleProvider() {
            @Override public String providerId() { return "tellus:sphere-face-native"; }
            @Override public TellusSample sample(TellusSampleRequest request) {
                if (request.nonBlocking()) return TellusSample.queued(request, providerId(), "native-exact-column-v1",
                    "non-blocking native spherical request does not read or schedule a terrain grid");
                if (!request.resolution().equals(SamplingResolution.exact(request.geometry().worldScale()))) {
                    return TellusSample.unknown(request, "native spherical column requires exact native-scale resolution");
                }
                var column = Objects.requireNonNull(columns.apply(request), "native column");
                if (!(request.coordinate().owner() instanceof com.yucareux.tellus.worldgen.global.SphericalOwnerView owner)
                    || !column.address().equals(owner.block())) {
                    throw new IllegalArgumentException("native spherical column does not match request owner");
                }
                var data = column.data();
                if (!data.elevationAvailable()) return TellusSample.unknown(request, "native spherical column has no elevation");
                var decision = com.yucareux.tellus.worldgen.CubeNetSurfacePolicy.resolve(column.point(), data,
                    request.geometry().worldScale(), request.cacheKey().worldSeed());
                var snapshot = TellusSphericalSurfaceSnapshot.resolvedNativeColumn(column, decision);
                var water = data.waterAvailable()
                    ? new TellusWaterSample(SampleStatus.AVAILABLE, true, data.oceanWater(), data.waterSurfaceMeters())
                    : TellusWaterSample.dry();
                return new TellusSample(request, SampleStatus.AVAILABLE,
                    new TellusElevationSample(SampleStatus.AVAILABLE, data.elevationMeters()),
                    new TellusCoverSample(SampleStatus.AVAILABLE, data.coverClass(), data.coverClass()), water,
                    TellusSampleProvenance.snapshot(providerId(), "native-exact-column-v1", false,
                        "native generator column; source point retained; cache hit not asserted"),
                    java.util.Optional.empty(), java.util.Optional.of(snapshot));
            }
        });
    }

    public static TellusSphericalSampleProvider forCubeNet(TellusSampleProvider delegate) {
        return new TellusSphericalSampleProvider("tellus:cube-net-spherical", Route.CUBE_NET, delegate);
    }

    public static TellusSphericalSampleProvider forSphereFace(TellusSampleProvider delegate) {
        return new TellusSphericalSampleProvider("tellus:sphere-face-spherical", Route.SPHERE_FACE, delegate);
    }

    public static TellusSphericalSampleProvider forCubeNet(SphereChunkSampler sampler) {
        return new TellusSphericalSampleProvider("tellus:cube-net-spherical", Route.CUBE_NET, sampler);
    }

    public static TellusSphericalSampleProvider forSphereFace(SphereChunkSampler sampler) {
        return new TellusSphericalSampleProvider("tellus:sphere-face-spherical", Route.SPHERE_FACE, sampler);
    }

    @Override
    public String providerId() {
        return this.providerId;
    }

    public Route route() {
        return this.route;
    }

    @Override
    public TellusSample sample(TellusSampleRequest request) {
        Objects.requireNonNull(request, "request");
        GeneratorOwner owner = request.coordinate().owner().kind();
        if (!supports(owner)) {
            return TellusSample.unknown(request,
                "spherical provider route does not own " + owner);
        }
        if (this.sampler != null) {
            if (request.nonBlocking()) {
                return TellusSample.queued(request, this.providerId, "injected-spherical-v1",
                    "non-blocking spherical sample requires an existing snapshot");
            }
            SphereChunkSampler.SurfaceData data = Objects.requireNonNull(
                this.sampler.samplePoint(request.coordinate().geo(), request.resolution().metres()),
                "surfaceData");
            return map(request, data);
        }
        TellusSample result = Objects.requireNonNull(this.delegate.sample(request), "delegate sample");
        if (!request.equals(result.request())) {
            throw new IllegalArgumentException("spherical delegate returned a different request");
        }
        return result;
    }

    private TellusSample map(
        TellusSampleRequest request, SphereChunkSampler.SurfaceData data
    ) {
        if (!data.elevationAvailable()) {
            return TellusSample.unknown(request, "injected spherical surface has no elevation");
        }
        TellusWaterSample water = data.waterAvailable()
            ? new TellusWaterSample(SampleStatus.AVAILABLE, true, data.oceanWater(), data.waterSurfaceMeters())
            : TellusWaterSample.dry();
        TellusSphericalSurfaceSnapshot snapshot = this.resolveProductionSurface
            ? TellusSphericalSurfaceSnapshot.resolved(data,
                com.yucareux.tellus.worldgen.CubeNetSurfacePolicy.resolve(request.coordinate().geo(), data,
                    request.geometry().worldScale(), request.cacheKey().worldSeed()))
            : TellusSphericalSurfaceSnapshot.raw(data);
        return new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, data.elevationMeters()),
            new TellusCoverSample(SampleStatus.AVAILABLE, data.coverClass(), data.coverClass()),
            water,
            TellusSampleProvenance.snapshot(this.providerId, "injected-spherical-v1", false,
                "injected SphereChunkSampler SurfaceData"), java.util.Optional.empty(), java.util.Optional.of(snapshot));
    }

    private boolean supports(GeneratorOwner owner) {
        return switch (this.route) {
            case CUBE_NET -> owner == GeneratorOwner.CUBE_NET_CANONICAL
                || owner == GeneratorOwner.CUBE_NET_APRON;
            case SPHERE_FACE -> owner == GeneratorOwner.SPHERE_FACE;
        };
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
