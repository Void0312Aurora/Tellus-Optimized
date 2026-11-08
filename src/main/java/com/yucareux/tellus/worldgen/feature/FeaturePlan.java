package com.yucareux.tellus.worldgen.feature;

import java.util.List;
import java.util.Objects;

/** Immutable result of one pure feature planning operation. */
public record FeaturePlan(
    FeaturePlanStatus status,
    List<FeaturePlacement> placements,
    List<String> diagnostics
) {
    public FeaturePlan {
        Objects.requireNonNull(status, "status");
        placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if ((status == FeaturePlanStatus.UNKNOWN || status == FeaturePlanStatus.QUEUED)
            && !placements.isEmpty()) {
            throw new IllegalArgumentException("unknown/queued plan cannot have placements");
        }
        if (status == FeaturePlanStatus.EMPTY && !placements.isEmpty()) {
            throw new IllegalArgumentException("empty plan cannot have placements");
        }
        if (status == FeaturePlanStatus.AVAILABLE && placements.isEmpty()) {
            throw new IllegalArgumentException("available plan requires placements");
        }
    }

    public static FeaturePlan empty(String diagnostic) {
        return new FeaturePlan(FeaturePlanStatus.EMPTY, List.of(), List.of(diagnostic));
    }

    public static FeaturePlan unknown(String diagnostic) {
        return new FeaturePlan(FeaturePlanStatus.UNKNOWN, List.of(), List.of(diagnostic));
    }

    public static FeaturePlan queued(String diagnostic) {
        return new FeaturePlan(FeaturePlanStatus.QUEUED, List.of(), List.of(diagnostic));
    }

    public static FeaturePlan available(FeaturePlacement placement) {
        return new FeaturePlan(FeaturePlanStatus.AVAILABLE, List.of(placement), List.of());
    }
}
