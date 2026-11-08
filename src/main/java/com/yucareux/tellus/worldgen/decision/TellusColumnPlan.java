package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.feature.FeaturePlan;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only result of the shared sample-to-decision-to-feature-plan pipeline.
 * It carries no target storage value and never represents feature writes.
 */
public record TellusColumnPlan(
    Status status,
    TellusSample sample,
    Optional<ColumnDecision> decision,
    Optional<FeaturePlan> featurePlan,
    List<String> diagnostics
) {
    public enum Status {
        AVAILABLE,
        PARTIAL,
        UNKNOWN,
        QUEUED
    }

    public TellusColumnPlan {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sample, "sample");
        decision = Objects.requireNonNull(decision, "decision");
        featurePlan = Objects.requireNonNull(featurePlan, "featurePlan");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));

        if (decision.isPresent()
            && !decision.orElseThrow().coordinate().equals(sample.request().coordinate())) {
            throw new IllegalArgumentException("decision coordinate does not match sample request");
        }
        if (status == Status.AVAILABLE && (decision.isEmpty()
            || decision.orElseThrow().status() != DecisionStatus.AVAILABLE)) {
            throw new IllegalArgumentException("available plan requires an available decision");
        }
        if (status == Status.PARTIAL && (decision.isEmpty()
            || decision.orElseThrow().status() != DecisionStatus.PARTIAL)) {
            throw new IllegalArgumentException("partial plan requires a partial decision");
        }
        if (featurePlan.isPresent()) {
            checkFeaturePlan(status, featurePlan.orElseThrow());
        }
    }

    public static TellusColumnPlan blocked(
        TellusSample sample, Status status, String diagnostic
    ) {
        if (status != Status.UNKNOWN && status != Status.QUEUED) {
            throw new IllegalArgumentException("blocked plan requires UNKNOWN or QUEUED status");
        }
        return new TellusColumnPlan(status, sample, Optional.empty(), Optional.empty(), List.of(diagnostic));
    }

    public static TellusColumnPlan decisionBlocked(
        TellusSample sample, ColumnDecision decision, String diagnostic
    ) {
        Status status = switch (decision.status()) {
            case UNKNOWN -> Status.UNKNOWN;
            case QUEUED -> Status.QUEUED;
            case PARTIAL -> Status.PARTIAL;
            case AVAILABLE -> Status.AVAILABLE;
        };
        return new TellusColumnPlan(status, sample, Optional.of(decision), Optional.empty(), List.of(diagnostic));
    }

    public static TellusColumnPlan planned(
        TellusSample sample, ColumnDecision decision, FeaturePlan featurePlan
    ) {
        Status status = decision.status() == DecisionStatus.PARTIAL ? Status.PARTIAL : Status.AVAILABLE;
        if (featurePlan.status() == com.yucareux.tellus.worldgen.feature.FeaturePlanStatus.UNKNOWN) {
            status = Status.UNKNOWN;
        } else if (featurePlan.status() == com.yucareux.tellus.worldgen.feature.FeaturePlanStatus.QUEUED) {
            status = Status.QUEUED;
        }
        return new TellusColumnPlan(status, sample, Optional.of(decision), Optional.of(featurePlan), List.of());
    }

    public TellusColumnPlan withDiagnostic(String diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        return new TellusColumnPlan(status, sample, decision, featurePlan,
            List.of(diagnostic));
    }

    private static void checkFeaturePlan(Status status, FeaturePlan plan) {
        com.yucareux.tellus.worldgen.feature.FeaturePlanStatus featureStatus = plan.status();
        if (featureStatus == com.yucareux.tellus.worldgen.feature.FeaturePlanStatus.UNKNOWN
            && status != Status.UNKNOWN) {
            throw new IllegalArgumentException("unknown feature plan requires UNKNOWN column plan");
        }
        if (featureStatus == com.yucareux.tellus.worldgen.feature.FeaturePlanStatus.QUEUED
            && status != Status.QUEUED) {
            throw new IllegalArgumentException("queued feature plan requires QUEUED column plan");
        }
    }
}
