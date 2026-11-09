package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Pure planner boundary for detached Cube-Net underground stage deltas. */
@FunctionalInterface
public interface UndergroundStagePlanner {
   UndergroundStagePlan plan(UndergroundStagePlan.Request request);

   @FunctionalInterface
   interface Resolver {
      List<UndergroundStagePlan.Delta> resolve(UndergroundStagePlan.Request request);
   }

   /**
    * Constructs one resolver per validated request. NON_BLOCKING returns QUEUED
    * before factory or resolver work; null resolver/results mean UNKNOWN.
    * Complete lists retain recorder order, with no sorting or partial prefix.
    */
   static UndergroundStagePlanner fromResolver(Function<UndergroundStagePlan.Request, Resolver> factory) {
      Objects.requireNonNull(factory, "factory");
      return request -> {
         validateRequest(request);
         if (request.resolution().kind() == SamplingResolution.Kind.NON_BLOCKING) {
            return UndergroundStagePlan.blocked(request, DecisionStatus.QUEUED, "non-blocking underground source is cold");
         }
         Resolver resolver = factory.apply(request);
         if (resolver == null) {
            return UndergroundStagePlan.blocked(request, DecisionStatus.UNKNOWN, "underground resolver is unavailable");
         }
         List<UndergroundStagePlan.Delta> deltas = resolver.resolve(request);
         if (deltas == null) {
            return UndergroundStagePlan.blocked(request, DecisionStatus.UNKNOWN, "underground resolver returned no complete snapshot");
         }
         return UndergroundStagePlan.available(request, deltas, List.of());
      };
   }

   /** Canonical geometry, snapshot and bounds checks precede all source callbacks. */
   static void validateRequest(UndergroundStagePlan.Request request) {
      UndergroundStagePlan.validateRequest(request);
   }
}
