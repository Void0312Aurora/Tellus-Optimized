package com.yucareux.tellus.worldgen.sample;

import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.Objects;

/** Immutable cache identity derived from validated request dimensions. */
public record CacheKey(
    String namespace,
    CoordinateToken coordinate,
    GeometryIdentity geometry,
    SamplingPurpose purpose,
    SamplingResolution resolution,
    long worldSeed
) {
    public CacheKey {
        namespace = requireText(namespace, "namespace");
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(resolution, "resolution");
    }

    public static CacheKey fromContext(
        TellusCoordinateContext context,
        GeometryIdentity geometry,
        SamplingPurpose purpose,
        SamplingResolution resolution,
        long worldSeed
    ) {
        Objects.requireNonNull(context, "context");
        GeometryIdentity concrete = Objects.requireNonNull(geometry, "geometry").forRequest();
        return new CacheKey("tellus", CoordinateToken.fromContext(context), concrete,
            purpose, resolution, worldSeed);
    }

    public boolean matches(GeometryIdentity geometry, SamplingPurpose purpose,
                           SamplingResolution resolution) {
        return this.geometry.equals(geometry)
            && this.purpose == purpose
            && this.resolution.equals(resolution);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
