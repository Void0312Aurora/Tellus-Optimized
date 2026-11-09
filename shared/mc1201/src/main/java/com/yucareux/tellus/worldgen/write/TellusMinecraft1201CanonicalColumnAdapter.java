package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Opt-in 1.20.1 column sink. Every value is prepared before the first mutation. */
public final class TellusMinecraft1201CanonicalColumnAdapter {
   private final TellusColumnMaterializer materializer;
   private final SemanticMapper<BlockState> mapper;
   private final SphereWorldModel model;

   public TellusMinecraft1201CanonicalColumnAdapter() {
      this(new TellusColumnMaterializer(), new TellusMinecraft1201SemanticMapper());
   }

   public TellusMinecraft1201CanonicalColumnAdapter(
      TellusColumnMaterializer materializer, SemanticMapper<BlockState> mapper
   ) {
      this(materializer, mapper, null);
   }

   /** Explicit model is required for custom face sizes; null selects native world-scale defaults. */
   public TellusMinecraft1201CanonicalColumnAdapter(
      TellusColumnMaterializer materializer, SemanticMapper<BlockState> mapper, SphereWorldModel model
   ) {
      this.materializer = Objects.requireNonNull(materializer, "materializer");
      this.mapper = Objects.requireNonNull(mapper, "mapper");
      this.model = model;
   }

   public WriteResult<BlockState> write(ChunkAccess target, WriteContext context, ColumnDecision decision) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(decision, "decision");
      if (decision.status() == DecisionStatus.UNKNOWN) {
         return WriteResult.unknown("column decision is UNKNOWN; canonical adapter performs no write");
      }
      if (decision.status() == DecisionStatus.QUEUED) {
         return WriteResult.queued("column decision is QUEUED; canonical adapter performs no write");
      }
      if (context.apronMirror()) return WriteResult.invalid("canonical column adapter does not write apron targets");
      WriteResult<SemanticColumnCell> plan = this.materializer.materializeCells(context, decision);
      if (plan.status().noWrite()) {
         return new WriteResult<>(plan.status(), List.of(), plan.diagnostics(), plan.persistenceIdentity());
      }
      Objects.requireNonNull(target, "target");
      var acquired = TellusTargetWriteGuard.tryAcquire(target);
      if (acquired.isEmpty()) return new WriteResult<>(WriteStatus.NO_WRITE_QUEUED, List.of(),
         List.of("target chunk already has a participating writer"), plan.persistenceIdentity());
      try (var lease = acquired.orElseThrow()) {
         return writeExclusive(target, context, plan);
      }
   }

   private WriteResult<BlockState> writeExclusive(ChunkAccess target, WriteContext context,
                                                  WriteResult<SemanticColumnCell> plan) {
      CanonicalColumnPosition horizontal;
      List<BlockPos> positions = new ArrayList<>(plan.mappedWrites().size());
      List<BlockState> mapped = new ArrayList<>(plan.mappedWrites().size());
      List<BlockState> previous = new ArrayList<>(plan.mappedWrites().size());
      try {
         horizontal = this.model == null ? CanonicalColumnPosition.resolve(context)
            : CanonicalColumnPosition.resolve(context, this.model);
         if (Math.floorDiv(horizontal.x(), 16) != target.getPos().x
            || Math.floorDiv(horizontal.z(), 16) != target.getPos().z) {
            return invalid(plan, "target chunk does not own the resolved Minecraft column");
         }
         for (SemanticColumnCell cell : plan.mappedWrites()) {
            if (cell.y() < target.getMinBuildHeight() || cell.y() >= target.getMaxBuildHeight()) {
               return invalid(plan, "expanded cell is outside target build range");
            }
            positions.add(new BlockPos(horizontal.x(), cell.y(), horizontal.z()));
            mapped.add(Objects.requireNonNull(this.mapper.map(cell.material(), cell.y(), context), "mapped state"));
         }
         for (BlockPos position : positions) previous.add(target.getBlockState(position));
      } catch (RuntimeException error) {
         return invalid(plan, "column preparation failed: " + error.getMessage());
      }
      int index = 0;
      try {
         for (; index < positions.size(); index++) {
            target.setBlockState(positions.get(index), mapped.get(index), false);
            if (!target.getBlockState(positions.get(index)).equals(mapped.get(index))) {
               throw new IllegalStateException("target did not retain mapped state");
            }
         }
         target.setUnsaved(true);
      } catch (RuntimeException error) {
         // Include the throwing operation: a sink may throw after changing its cell.
         RuntimeException rollbackFailure = null;
         for (int rollback = Math.min(index, positions.size() - 1); rollback >= 0; rollback--) {
            try {
               target.setBlockState(positions.get(rollback), previous.get(rollback), false);
               if (!target.getBlockState(positions.get(rollback)).equals(previous.get(rollback))) {
                  throw new IllegalStateException("target did not retain restored state");
               }
            } catch (RuntimeException failed) {
               if (rollbackFailure == null) rollbackFailure = failed;
               else if (failed != rollbackFailure) rollbackFailure.addSuppressed(failed);
            }
         }
         if (rollbackFailure != null) {
            IllegalStateException failure = new IllegalStateException("column write failed; rollback incomplete", error);
            failure.addSuppressed(rollbackFailure);
            throw failure;
         }
         return invalid(plan, "column write failed and original states were restored: " + error.getMessage());
      }
      return new WriteResult<>(plan.status(), mapped,
         List.of("canonical column expanded, mapped and written"), plan.persistenceIdentity());
   }

   private static WriteResult<BlockState> invalid(WriteResult<SemanticColumnCell> plan, String diagnostic) {
      return new WriteResult<>(WriteStatus.NO_WRITE_INVALID, List.of(), List.of(diagnostic), plan.persistenceIdentity());
   }
}
