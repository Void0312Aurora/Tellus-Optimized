package com.yucareux.tellus.worldgen.feature;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import java.util.Map;
import java.util.Objects;

/** Deterministic default planner illustrating canonical-owner/apron-mirror rules. */
public final class DefaultTellusFeaturePlanner implements TellusFeaturePlanner {
    private final FeatureKind kind;
    private final String featureId;
    private final SemanticMaterial material;

    public DefaultTellusFeaturePlanner(FeatureKind kind, String featureId, SemanticMaterial material) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.featureId = Objects.requireNonNull(featureId, "featureId");
        this.material = Objects.requireNonNull(material, "material");
    }

    @Override
    public FeaturePlan plan(
        TellusGenerationContext generation,
        TellusCoordinateContext coordinate,
        ColumnDecision column,
        TellusSample sample
    ) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(sample, "sample");
        if (column.status() == DecisionStatus.UNKNOWN || sample.status().unknown()) {
            return FeaturePlan.unknown("feature input unknown");
        }
        if (column.status() == DecisionStatus.QUEUED || sample.status().queued()) {
            return FeaturePlan.queued("feature input queued");
        }
        if (column.terrainY().isEmpty()) return FeaturePlan.empty("no terrain anchor");
        if (coordinate.owner() instanceof CubeNetApronOwnerView) {
            return FeaturePlan.available(FeaturePlacement.apronMirror(
                kind, featureId, coordinate, PlacementConflictPolicy.MIRROR_OWNER,
                material, 0, column.terrainY().getAsInt(), 0, 1, Map.of("source", "canonical-owner")));
        }
        return FeaturePlan.available(FeaturePlacement.canonical(
            kind, featureId, coordinate, PlacementOperation.SET,
            PlacementConflictPolicy.OWNER_ONLY, material, 0,
            column.terrainY().getAsInt(), 0, 1, Map.of()));
    }
}
