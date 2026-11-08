package com.yucareux.tellus.worldgen.feature;

import com.yucareux.tellus.worldgen.write.SemanticMapper;
import com.yucareux.tellus.worldgen.write.WriteContext;

/** Pure, opt-in feature placement boundary; implementations own concrete sinks. */
@FunctionalInterface
public interface FeaturePlacementSink<M> {
   FeaturePlacementResult<M> place(
      WriteContext context, FeaturePlan plan, SemanticMapper<M> mapper
   );
}
