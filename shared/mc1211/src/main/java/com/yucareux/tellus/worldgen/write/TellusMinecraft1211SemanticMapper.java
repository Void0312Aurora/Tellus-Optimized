package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft 1.21.1 semantic-to-state mapper; no chunk or world access. */
public final class TellusMinecraft1211SemanticMapper implements SemanticMapper<BlockState> {
   @Override
   public BlockState mapFeature(com.yucareux.tellus.worldgen.feature.FeaturePlacement placement, int y, WriteContext context) {
      BlockState state = map(placement.material(), y, context);
      return placement.flowState().map(flow -> state.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, flow.level())).orElse(state);
   }

   @Override
   public BlockState map(SemanticMaterial material, int y, WriteContext context) {
      return TellusNativeSemanticMaterials.state(material);
   }
}
