package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;

/**
 * Semantic column writer boundary. Implementations may map and submit semantic
 * output, but the shared contract has no engine storage dependency.
 */
@FunctionalInterface
public interface TellusColumnWriter<M> {
   WriteResult<M> write(WriteContext context, ColumnDecision decision, SemanticMapper<M> mapper);
}
