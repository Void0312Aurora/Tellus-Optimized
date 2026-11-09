package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.feature.FeaturePlan;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Version-local block access for the shared terrain+feature transaction. */
public final class TellusMinecraft1211CompositeColumnWriter {
   private final TellusCompositeColumnWriter<BlockState> writer;

   public TellusMinecraft1211CompositeColumnWriter(TellusColumnMaterializer materializer) {
      this(materializer, null);
   }

   public TellusMinecraft1211CompositeColumnWriter(TellusColumnMaterializer materializer, SphereWorldModel model) {
      this.writer = new TellusCompositeColumnWriter<>(materializer, model);
   }

   public WriteResult<BlockState> write(ChunkAccess chunk, WriteContext context, ColumnDecision decision,
                                         FeaturePlan features, SemanticMapper<BlockState> mapper) {
      if (chunk == null
         || decision != null && (decision.status() == com.yucareux.tellus.worldgen.decision.DecisionStatus.UNKNOWN
            || decision.status() == com.yucareux.tellus.worldgen.decision.DecisionStatus.QUEUED)
         || features != null && (features.status() == com.yucareux.tellus.worldgen.feature.FeaturePlanStatus.UNKNOWN
            || features.status() == com.yucareux.tellus.worldgen.feature.FeaturePlanStatus.QUEUED)) {
         return writeUnleased(chunk, context, decision, features, mapper);
      }
      var acquired = TellusTargetWriteGuard.tryAcquire(chunk);
      if (acquired.isEmpty()) return WriteResult.queued("target chunk already has a participating writer");
      try (var lease = acquired.orElseThrow()) {
         return writeOwned(chunk, context, decision, features, mapper, lease);
      }
   }

   WriteResult<BlockState> writeOwned(ChunkAccess chunk, WriteContext context, ColumnDecision decision,
                                     FeaturePlan features, SemanticMapper<BlockState> mapper,
                                     TellusTargetWriteGuard.Lease lease) {
      java.util.Objects.requireNonNull(lease, "lease").requireOwned(chunk);
      return writeUnleased(chunk, context, decision, features, mapper);
   }

   private WriteResult<BlockState> writeUnleased(ChunkAccess chunk, WriteContext context, ColumnDecision decision,
                                         FeaturePlan features, SemanticMapper<BlockState> mapper) {
      // Creating the adapter performs no chunk access; terminal plans remain no-touch.
      return this.writer.write(new TellusCompositeColumnWriter.Target<BlockState>() {
         public int chunkX() { return chunk.getPos().x; }
         public int chunkZ() { return chunk.getPos().z; }
         public int minY() { return chunk.getMinBuildHeight(); }
         public int maxYExclusive() { return chunk.getMaxBuildHeight(); }
         public BlockState read(TellusCompositeColumnWriter.Cell cell) {
            return chunk.getBlockState(position(cell));
         }
         public void write(TellusCompositeColumnWriter.Cell cell, BlockState state) {
            chunk.setBlockState(position(cell), state, false);
         }
         public boolean isAir(BlockState state) { return state.isAir(); }
         public void markDirty() { chunk.setUnsaved(true); }
         public TellusCompositeColumnWriter.PostProcessing postProcessing() {
            return chunk instanceof net.minecraft.world.level.chunk.ProtoChunk proto ? new TellusNativePostProcessing(proto) : null;
         }
      }, context, decision, features, mapper);
   }

   private static BlockPos position(TellusCompositeColumnWriter.Cell cell) {
      return new BlockPos(cell.x(), cell.y(), cell.z());
   }
}
