package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.feature.FeaturePlan;
import com.yucareux.tellus.worldgen.feature.FeaturePlanStatus;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvider;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.Objects;

/**
 * Shared read-only column orchestration. It produces a semantic plan only;
 * target storage, mappings, feature placement writes and lifecycle are outside
 * this contract.
 */
@FunctionalInterface
public interface TellusColumnPipeline {
    TellusColumnPlan plan(
        TellusGenerationContext generation,
        TellusCoordinateContext coordinate,
        TellusSampleRequest request
    );

    static TellusColumnPipeline compose(
        TellusSampleProvider sampleProvider,
        TellusColumnDecisionEngine decisionEngine,
        TellusFeaturePlanner featurePlanner
    ) {
        return compose(sampleProvider, decisionEngine, featurePlanner, sample -> null);
    }

    static TellusColumnPipeline composeWithSampleClimate(
        TellusSampleProvider sampleProvider,
        TellusColumnDecisionEngine decisionEngine,
        TellusFeaturePlanner featurePlanner
    ) {
        return compose(sampleProvider, decisionEngine, featurePlanner, sample ->
            sample.climate()
                .filter(climate -> climate.complete())
                .map(climate -> climate.code())
                .orElse(null));
    }
    static TellusColumnPipeline compose(
        TellusSampleProvider sampleProvider,
        TellusColumnDecisionEngine decisionEngine,
        TellusFeaturePlanner featurePlanner,
        java.util.function.Function<TellusSample, String> koppenResolver
    ) {
        Objects.requireNonNull(sampleProvider, "sampleProvider");
        Objects.requireNonNull(decisionEngine, "decisionEngine");
        Objects.requireNonNull(featurePlanner, "featurePlanner");
        Objects.requireNonNull(koppenResolver, "koppenResolver");
        return (generation, coordinate, request) -> {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(request, "request");
            if (!request.coordinate().equals(coordinate)) {
                throw new IllegalArgumentException("pipeline coordinate does not match request");
            }

            TellusSample sample = Objects.requireNonNull(sampleProvider.sample(request), "sample");
            if (sample.status() == SampleStatus.UNKNOWN) {
                return TellusColumnPlan.blocked(sample, TellusColumnPlan.Status.UNKNOWN,
                    "sample is UNKNOWN; target remains untouched");
            }
            if (sample.status() == SampleStatus.QUEUED) {
                return TellusColumnPlan.blocked(sample, TellusColumnPlan.Status.QUEUED,
                    "sample is QUEUED; target remains untouched");
            }

            ColumnDecision decision = decisionEngine.decide(
                generation, coordinate, sample, koppenResolver.apply(sample));
            if (decision.status() == DecisionStatus.UNKNOWN) {
                return TellusColumnPlan.decisionBlocked(sample, decision,
                    "decision is UNKNOWN; target remains untouched");
            }
            if (decision.status() == DecisionStatus.QUEUED) {
                return TellusColumnPlan.decisionBlocked(sample, decision,
                    "decision is QUEUED; target remains untouched");
            }

            FeaturePlan featurePlan = Objects.requireNonNull(
                featurePlanner.plan(generation, coordinate, decision, sample), "featurePlan");
            if (featurePlan.status() == FeaturePlanStatus.UNKNOWN) {
                return TellusColumnPlan.planned(sample, decision, featurePlan)
                    .withDiagnostic("feature plan is UNKNOWN; target remains untouched");
            }
            if (featurePlan.status() == FeaturePlanStatus.QUEUED) {
                return TellusColumnPlan.planned(sample, decision, featurePlan)
                    .withDiagnostic("feature plan is QUEUED; target remains untouched");
            }
            return TellusColumnPlan.planned(sample, decision, featurePlan);
        };
    }
}
