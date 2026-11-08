package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Immutable semantic output for one pure terrain-column decision. */
public record ColumnDecision(
    TellusCoordinateContext coordinate,
    DecisionStatus status,
    OptionalInt terrainY,
    SurfaceDecision surface,
    WaterDecision water,
    BiomeDecision biome,
    StrataDecision strata,
    List<String> diagnostics
) {
    public ColumnDecision {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(terrainY, "terrainY");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(water, "water");
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(strata, "strata");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (status == DecisionStatus.AVAILABLE && terrainY.isEmpty()) {
            throw new IllegalArgumentException("AVAILABLE decision requires terrainY");
        }
    }

    public static ColumnDecision unknown(TellusCoordinateContext coordinate, String diagnostic) {
        return unavailable(coordinate, DecisionStatus.UNKNOWN, diagnostic);
    }

    public static ColumnDecision unavailable(
        TellusCoordinateContext coordinate, DecisionStatus status, String diagnostic
    ) {
        return new ColumnDecision(coordinate, status, OptionalInt.empty(),
            SurfaceDecision.unknown(), WaterDecision.unknown(), BiomeDecision.unknown(),
            StrataDecision.unknown(), List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }
}
