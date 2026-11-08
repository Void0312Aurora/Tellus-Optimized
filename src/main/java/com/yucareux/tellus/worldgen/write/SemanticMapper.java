package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.SemanticMaterial;

/**
 * Version/loader adapter contract. Implementations map semantic materials to
 * an opaque target value; this API intentionally mentions no BlockState or
 * ChunkAccess type.
 */
@FunctionalInterface
public interface SemanticMapper<M> {
   M map(SemanticMaterial material, int y, WriteContext context);
}
