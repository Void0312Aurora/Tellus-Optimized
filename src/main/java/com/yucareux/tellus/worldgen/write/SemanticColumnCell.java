package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import java.util.Objects;

/** One explicit, version-neutral block write at an absolute vertical coordinate. */
public record SemanticColumnCell(int y, SemanticMaterial material) {
   public SemanticColumnCell {
      Objects.requireNonNull(material, "material");
      if (material == SemanticMaterial.UNKNOWN) {
         throw new IllegalArgumentException("UNKNOWN is not a block write");
      }
   }
}
