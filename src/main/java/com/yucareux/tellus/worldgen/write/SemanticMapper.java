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

   /** Default-state implementations must not erase exact properties on explicit flow placements. */
   default M mapFeature(com.yucareux.tellus.worldgen.feature.FeaturePlacement placement, int y, WriteContext context) {
      if (placement.flowState().isPresent()) throw new IllegalArgumentException("mapper has no exact flow-state support");
      return map(placement.material(), y, context);
   }
}
