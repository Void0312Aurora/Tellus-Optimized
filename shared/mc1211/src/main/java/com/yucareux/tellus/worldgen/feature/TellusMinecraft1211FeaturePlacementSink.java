package com.yucareux.tellus.worldgen.feature;

import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.write.CanonicalColumnPosition;
import com.yucareux.tellus.worldgen.write.SemanticMapper;
import com.yucareux.tellus.worldgen.write.WriteContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Opt-in 1.21.1 feature sink; no generator or lifecycle registration. */
public final class TellusMinecraft1211FeaturePlacementSink implements FeaturePlacementSink<BlockState> {
   private final ChunkAccess target;

   public TellusMinecraft1211FeaturePlacementSink(ChunkAccess target) {
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
      if (this.target == null) return placeUnleased(context, plan, mapper);
      var acquired = com.yucareux.tellus.worldgen.write.TellusTargetWriteGuard.tryAcquire(this.target);
      if (acquired.isEmpty()) return FeaturePlacementResult.noWrite(FeaturePlacementStatus.NO_WRITE_QUEUED,
         "target chunk already has a participating writer");
      try (var lease = acquired.orElseThrow()) {
         return placeUnleased(context, plan, mapper);
      }
   }

   private FeaturePlacementResult<BlockState> placeUnleased(
      WriteContext context, FeaturePlan plan, SemanticMapper<BlockState> mapper
   ) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(mapper, "mapper");
      FeaturePlacementResult<BlockState> terminal = FeaturePlacementSinkGuard.planResult(plan);
      if (terminal != null) return terminal;
      final long[] base;
      try {
         // Apron writes retain their explicit seam projection. Canonical writes
         // resolve the Cube-Net cell center rather than its face-point origin.
         base = context.apronMirror()
            ? FeaturePlacementSinkGuard.targetBlock(context)
            : canonicalBase(context);
      } catch (RuntimeException error) {
         return invalid("feature placement preparation failed: " + error.getMessage());
      }

      List<BlockPos> positions = new ArrayList<>();
      try {
         // Validate every placement and every destination before mapping or mutation.
         for (FeaturePlacement placement : plan.placements()) {
            String error = FeaturePlacementSinkGuard.validatePlacement(context, placement);
            if (error != null) return ownerResult(error);
            if (placement.material() == SemanticMaterial.UNKNOWN) {
               return invalid("UNKNOWN feature material cannot be mapped");
            }
            long x = Math.addExact(base[0], placement.offsetX());
            long z = Math.addExact(base[1], placement.offsetZ());
            for (int y = 0; y < placement.height(); y++) {
               int worldY = Math.addExact(placement.offsetY(), y);
               if (worldY < this.target.getMinBuildHeight()
                  || worldY >= this.target.getMaxBuildHeight()) {
                  return invalid("feature placement is outside target build range");
               }
               BlockPos position = new BlockPos(Math.toIntExact(x), worldY, Math.toIntExact(z));
               if (Math.floorDiv((long)position.getX(), 16L) != this.target.getPos().x
                  || Math.floorDiv((long)position.getZ(), 16L) != this.target.getPos().z) {
                  return invalid("target chunk does not own feature placement");
               }
               positions.add(position);
            }
         }
      } catch (RuntimeException error) {
         return invalid("feature placement preparation failed: " + error.getMessage());
      }

      List<BlockState> mapped = new ArrayList<>(positions.size());
      try {
         // Mapping is a separate complete preflight pass: no target mutation can
         // occur until every semantic cell has a concrete state.
         for (FeaturePlacement placement : plan.placements()) {
            for (int y = 0; y < placement.height(); y++) {
               int worldY = Math.addExact(placement.offsetY(), y);
               mapped.add(Objects.requireNonNull(
                  mapper.map(placement.material(), worldY, context), "mapped state"));
            }
         }
      } catch (RuntimeException error) {
         return invalid("feature placement mapping failed: " + error.getMessage());
      }

      List<BlockState> previous = new ArrayList<>(positions.size());
      try {
         for (BlockPos position : positions) previous.add(this.target.getBlockState(position));
      } catch (RuntimeException error) {
         return invalid("feature placement state capture failed: " + error.getMessage());
      }

      int index = 0;
      try {
         for (; index < positions.size(); index++) {
            BlockPos position = positions.get(index);
            BlockState state = mapped.get(index);
            this.target.setBlockState(position, state, false);
            if (!this.target.getBlockState(position).equals(state)) {
               throw new IllegalStateException("target did not retain mapped state");
            }
         }
         this.target.setUnsaved(true);
      } catch (RuntimeException error) {
         // A throwing setBlockState may already have changed its cell. Include
         // the current index and continue attempting every prior cell.
         RuntimeException rollbackFailure = null;
         for (int rollback = Math.min(index, positions.size() - 1); rollback >= 0; rollback--) {
            try {
               BlockPos position = positions.get(rollback);
               this.target.setBlockState(position, previous.get(rollback), false);
               if (!this.target.getBlockState(position).equals(previous.get(rollback))) {
                  throw new IllegalStateException("target did not retain restored state");
               }
            } catch (RuntimeException failed) {
               if (rollbackFailure == null) rollbackFailure = failed;
               else if (failed != rollbackFailure) rollbackFailure.addSuppressed(failed);
            }
         }
         if (rollbackFailure != null) {
            IllegalStateException failure = new IllegalStateException(
               "feature placement write failed; rollback incomplete", error);
            failure.addSuppressed(rollbackFailure);
            throw failure;
         }
         return invalid("feature placement write failed and original states were restored: "
            + error.getMessage());
      }
      return new FeaturePlacementResult<>(FeaturePlacementStatus.WRITTEN, mapped,
         List.of("mc1211 semantic feature placement"));
   }

   private static long[] canonicalBase(WriteContext context) {
      CanonicalColumnPosition position = CanonicalColumnPosition.resolve(context);
      return new long[]{position.x(), position.z()};
   }

   private static FeaturePlacementResult<BlockState> ownerResult(String error) {
      return FeaturePlacementResult.noWrite(
         error.contains("conflict") ? FeaturePlacementStatus.NO_WRITE_CONFLICT
            : FeaturePlacementStatus.NO_WRITE_OWNER, error);
   }

   private static FeaturePlacementResult<BlockState> invalid(String diagnostic) {
      return FeaturePlacementResult.noWrite(FeaturePlacementStatus.INVALID, diagnostic);
   }
}
