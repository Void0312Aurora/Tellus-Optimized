package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import java.util.Objects;

/**
 * Pure Earth biome bridge. It forwards already-resolved Earth inputs to an
 * injected classifier boundary and only adapts the returned id to BiomeDecision.
 */
public final class TellusEarthBiomeDecisionKernel {
    @FunctionalInterface
    public interface Resolver {
        String resolve(int visualCoverClass, String koppenCode,
            long floorBlockX, long floorBlockZ, double worldScale);
    }

    private final Resolver resolver;

    public TellusEarthBiomeDecisionKernel(Resolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public BiomeDecision decide(
        TellusSample sample,
        TellusCoordinateContext coordinate,
        String koppenCode
    ) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(coordinate, "coordinate");
        if (!coordinate.equals(sample.request().coordinate())) {
            throw new IllegalArgumentException("sample coordinate does not match biome coordinate");
        }
        if (!(coordinate.owner() instanceof EarthOwnerView earth)
            || !sample.complete()
            || !sample.cover().complete()
            || koppenCode == null
            || koppenCode.isBlank()) {
            return BiomeDecision.unknown();
        }

        String id = this.resolver.resolve(
            sample.cover().visualClass(),
            koppenCode,
            earth.point().floorBlockX(),
            earth.point().floorBlockZ(),
            earth.point().worldScale());
        return TellusBiomeDecisionAdapter.fromResolvedId(id, id != null);
    }
}
