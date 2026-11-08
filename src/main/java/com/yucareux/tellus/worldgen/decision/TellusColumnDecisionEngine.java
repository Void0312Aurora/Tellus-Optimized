package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Pure deterministic column kernel. Earth biome classification is opt-in and
 * requires an explicit raw Köppen input; the legacy constructor remains the
 * default stone/plains behavior.
 */
public final class TellusColumnDecisionEngine {
    private final Optional<TellusEarthBiomeDecisionKernel> earthBiomeKernel;
    private final Optional<TellusSphericalDecisionKernel> sphericalKernel;

    public TellusColumnDecisionEngine() {
        this(Optional.empty());
    }

    /** Explicit opt-in constructor for Earth biome classification. */
    public TellusColumnDecisionEngine(TellusEarthBiomeDecisionKernel earthBiomeKernel) {
        this(Optional.of(Objects.requireNonNull(earthBiomeKernel, "earthBiomeKernel")));
    }

    private TellusColumnDecisionEngine(Optional<TellusEarthBiomeDecisionKernel> earthBiomeKernel) {
        this(earthBiomeKernel, Optional.empty());
    }

    private TellusColumnDecisionEngine(Optional<TellusEarthBiomeDecisionKernel> earthBiomeKernel,
                                      Optional<TellusSphericalDecisionKernel> sphericalKernel) {
        this.earthBiomeKernel = Objects.requireNonNull(earthBiomeKernel, "earthBiomeKernel");
        this.sphericalKernel = Objects.requireNonNull(sphericalKernel, "sphericalKernel");
    }

    public static TellusColumnDecisionEngine withSphericalProductionRules(
        TellusSphericalDecisionKernel.Settings settings
    ) {
        return withSphericalProductionRules(new TellusSphericalDecisionKernel(settings));
    }

    public static TellusColumnDecisionEngine withSphericalProductionRules(TellusSphericalDecisionKernel kernel) {
        return new TellusColumnDecisionEngine(Optional.empty(), Optional.of(Objects.requireNonNull(kernel, "kernel")));
    }

    /** Explicit factory; default pipeline construction does not use this path. */
    public static TellusColumnDecisionEngine withEarthBiomeClassifier() {
        return new TellusColumnDecisionEngine(
            new TellusEarthBiomeDecisionKernel(TellusEarthBiomeClassifierAdapter.resolver()));
    }

    public ColumnDecision decide(
        TellusGenerationContext context,
        TellusCoordinateContext coordinate,
        TellusSample sample
    ) {
        return this.decide(context, coordinate, sample, null);
    }

    /**
     * Opt-in Earth overload. The climate text is caller-provided and remains
     * raw; absent climate keeps the legacy default biome behavior.
     */
    public ColumnDecision decide(
        TellusGenerationContext context,
        TellusCoordinateContext coordinate,
        TellusSample sample,
        String koppenCode
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(sample, "sample");
        if (!coordinate.equals(sample.request().coordinate())) {
            throw new IllegalArgumentException("sample coordinate does not match decision coordinate");
        }
        if (!context.geometry().equals(sample.request().geometry())) {
            throw new IllegalArgumentException("sample geometry does not match generation context");
        }
        if (this.sphericalKernel.isPresent()) {
            return this.sphericalKernel.orElseThrow().decide(context, coordinate, sample);
        }
        if (sample.status() == SampleStatus.QUEUED) {
            return ColumnDecision.unavailable(coordinate, DecisionStatus.QUEUED, "sample queued");
        }
        if (sample.status() == SampleStatus.UNKNOWN || !Double.isFinite(sample.elevation().metres())) {
            return ColumnDecision.unknown(coordinate, "terrain elevation unavailable");
        }

        int terrainY = height(context, sample.elevation().metres());
        WaterDecision water = sample.water().known()
            ? sample.water().hasWater()
                ? new WaterDecision(sample.water().ocean() ? WaterDecision.Kind.OCEAN : WaterDecision.Kind.INLAND,
                    OptionalInt.of(height(context, sample.water().surfaceMetres())))
                : WaterDecision.dry()
            : WaterDecision.unknown();
        DecisionStatus status = sample.complete() ? DecisionStatus.AVAILABLE : DecisionStatus.PARTIAL;
        BiomeDecision biome = this.earthBiomeKernel.isPresent()
            && sample.complete()
            && koppenCode != null
            && !koppenCode.isBlank()
            ? this.earthBiomeKernel.orElseThrow().decide(sample, coordinate, koppenCode)
            : BiomeDecision.defaultBiome();
        return new ColumnDecision(coordinate, status, OptionalInt.of(terrainY),
            SurfaceDecision.defaultStone(), water, biome,
            StrataDecision.stone(context.minY(), terrainY),
            List.of(this.earthBiomeKernel.isPresent() && biome.known()
                ? "deterministic default decision-v1; earth biome opt-in"
                : "deterministic default decision-v1"));
    }

    private static int height(TellusGenerationContext context, double metres) {
        long rounded = Math.round(metres / context.worldScale()) + (long)context.seaLevel();
        return (int)Math.max((long)context.minY(), Math.min((long)context.maxYExclusive() - 1L, rounded));
    }
}
