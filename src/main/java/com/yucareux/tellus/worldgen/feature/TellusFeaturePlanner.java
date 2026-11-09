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

    /** Pure ordered stages; blocked stages discard earlier placements before any target write. */
    static TellusFeaturePlanner sequence(TellusFeaturePlanner... planners) {
        var stages = java.util.List.copyOf(java.util.Arrays.asList(planners.clone()));
        return (generation, coordinate, column, sample) -> {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(sample, "sample");
            if (column.status() == com.yucareux.tellus.worldgen.decision.DecisionStatus.UNKNOWN
                || sample.status() == com.yucareux.tellus.worldgen.sample.SampleStatus.UNKNOWN) {
                return FeaturePlan.unknown("ordered stages require known column/sample");
            }
            if (column.status() == com.yucareux.tellus.worldgen.decision.DecisionStatus.QUEUED
                || sample.status() == com.yucareux.tellus.worldgen.sample.SampleStatus.QUEUED
                || sample.request().nonBlocking()) return FeaturePlan.queued("ordered stages queued");
            var placements = new java.util.ArrayList<FeaturePlacement>();
            var diagnostics = new java.util.ArrayList<String>();
            for (var stage : stages) {
                var plan = Objects.requireNonNull(stage.plan(generation, coordinate, column, sample), "stage plan");
                if (plan.status() == FeaturePlanStatus.UNKNOWN || plan.status() == FeaturePlanStatus.QUEUED) return plan;
                placements.addAll(plan.placements()); diagnostics.addAll(plan.diagnostics());
            }
            return new FeaturePlan(placements.isEmpty() ? FeaturePlanStatus.EMPTY : FeaturePlanStatus.AVAILABLE,
                placements, diagnostics);
        };
    }

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
