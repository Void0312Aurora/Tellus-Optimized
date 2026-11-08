package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import java.util.Objects;

/** Immutable, Minecraft-free generation parameters for the decision kernel. */
public record TellusGenerationContext(
    long worldSeed,
    double worldScale,
    int seaLevel,
    int minY,
    int maxYExclusive,
    GeometryIdentity geometry
) {
    public TellusGenerationContext {
        if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) {
            throw new IllegalArgumentException("worldScale");
        }
        if (maxYExclusive <= minY) throw new IllegalArgumentException("vertical range");
        geometry = Objects.requireNonNull(geometry, "geometry").forRequest();
        if (Double.compare(geometry.worldScale(), worldScale) != 0) {
            throw new IllegalArgumentException("geometry worldScale does not match context");
        }
    }

    public TellusGenerationContext(long worldSeed, double worldScale, int seaLevel,
                                   int minY, int maxYExclusive) {
        this(worldSeed, worldScale, seaLevel, minY, maxYExclusive,
            GeometryIdentity.forProvider("tellus:decision", "decision-v1",
                "decision-projection-v1", "decision-noise-v1", worldScale));
    }
}
