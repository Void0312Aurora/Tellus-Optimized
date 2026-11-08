package com.yucareux.tellus.worldgen.feature;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import java.util.Objects;

/** Pure owner-aware feature planning boundary. */
@FunctionalInterface
public interface TellusFeaturePlanner {
    FeaturePlan plan(
        TellusGenerationContext generation,
        TellusCoordinateContext coordinate,
        ColumnDecision column,
        TellusSample sample
    );

    static TellusFeaturePlanner empty() {
        return (generation, coordinate, column, sample) -> {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(sample, "sample");
            return switch (column.status()) {
                case UNKNOWN -> FeaturePlan.unknown("column decision unknown");
                case QUEUED -> FeaturePlan.queued("column decision queued");
                default -> FeaturePlan.empty("no default feature planner");
            };
        };
    }
}
