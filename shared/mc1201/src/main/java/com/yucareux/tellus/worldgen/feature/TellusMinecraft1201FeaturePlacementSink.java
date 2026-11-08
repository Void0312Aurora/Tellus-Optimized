package com.yucareux.tellus.worldgen.feature;

import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.write.SemanticMapper;
import com.yucareux.tellus.worldgen.write.WriteContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Opt-in 1.20.1 feature sink; no generator or lifecycle registration. */
public final class TellusMinecraft1201FeaturePlacementSink implements FeaturePlacementSink<BlockState> {
   private final ChunkAccess target;

   public TellusMinecraft1201FeaturePlacementSink(ChunkAccess target) {
      this.target = target;
   }

   @Override
   public FeaturePlacementResult<BlockState> place(
      WriteContext context, FeaturePlan plan, SemanticMapper<BlockState> mapper
   ) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(mapper, "mapper");
      FeaturePlacementResult<BlockState> terminal = FeaturePlacementSinkGuard.planResult(plan);
      if (terminal != null) return terminal;
      long[] base = FeaturePlacementSinkGuard.targetBlock(context);
      List<BlockState> mapped = new ArrayList<>();
      for (FeaturePlacement placement : plan.placements()) {
         String error = FeaturePlacementSinkGuard.validatePlacement(context, placement);
         if (error != null) return FeaturePlacementResult.noWrite(
            error.contains("conflict") ? FeaturePlacementStatus.NO_WRITE_CONFLICT : FeaturePlacementStatus.NO_WRITE_OWNER,
            error);
         if (placement.material() == SemanticMaterial.UNKNOWN) {
            return FeaturePlacementResult.noWrite(FeaturePlacementStatus.INVALID,
               "UNKNOWN feature material cannot be mapped");
         }
         Objects.requireNonNull(this.target, "target");
         for (int y = 0; y < placement.height(); y++) {
            int worldY = placement.offsetY() + y;
            BlockPos pos = new BlockPos(
               Math.toIntExact(base[0] + placement.offsetX()), worldY,
               Math.toIntExact(base[1] + placement.offsetZ()));
            BlockState state = mapper.map(placement.material(), worldY, context);
            this.target.setBlockState(pos, state, false);
            mapped.add(state);
         }
      }
      return new FeaturePlacementResult<>(FeaturePlacementStatus.WRITTEN, mapped,
         List.of("mc1201 semantic feature placement"));
   }
}
