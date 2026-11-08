package com.yucareux.tellus.worldgen.sample;

import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.Objects;

/** Pure sampling input; cache identity is derived from the coordinate context. */
public record TellusSampleRequest(
    TellusCoordinateContext coordinate,
    SamplingPurpose purpose,
    SamplingResolution resolution,
    GeometryIdentity geometry,
    CacheKey cacheKey
) {
    public TellusSampleRequest {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(geometry, "geometry");
        geometry.requireConcrete();
        Objects.requireNonNull(cacheKey, "cacheKey");
        if (!cacheKey.matches(geometry, purpose, resolution)) {
            throw new IllegalArgumentException("cache key does not match sampling request");
        }
        if (!cacheKey.coordinate().equals(CoordinateToken.fromContext(coordinate))) {
            throw new IllegalArgumentException("cache key coordinate does not match context");
        }
    }

    public static TellusSampleRequest fromContext(
        TellusCoordinateContext coordinate,
        SamplingPurpose purpose,
        SamplingResolution resolution,
        GeometryIdentity geometry,
        long worldSeed
    ) {
        GeometryIdentity concrete = Objects.requireNonNull(geometry, "geometry").forRequest();
        return new TellusSampleRequest(
            coordinate, purpose, resolution, concrete,
            CacheKey.fromContext(coordinate, concrete, purpose, resolution, worldSeed)
        );
    }

    public boolean nonBlocking() {
        return resolution.kind() == SamplingResolution.Kind.NON_BLOCKING;
    }
}
