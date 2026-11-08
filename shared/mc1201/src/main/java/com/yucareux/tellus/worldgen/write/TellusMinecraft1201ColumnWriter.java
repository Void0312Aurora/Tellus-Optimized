package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Isolated 1.20.1 sink-backed writer. It is not registered with any generator
 * or lifecycle. The adapter's terminal guards run before the sink is touched.
 */
public final class TellusMinecraft1201ColumnWriter implements TellusColumnWriter<BlockState> {
   private final ChunkAccess target;
   private final TellusColumnMaterializer guard;

   public TellusMinecraft1201ColumnWriter(ChunkAccess target) {
      this(target, new TellusColumnMaterializer());
   }

   public TellusMinecraft1201ColumnWriter(ChunkAccess target, TellusColumnMaterializer guard) {
      this.target = target;
      this.guard = java.util.Objects.requireNonNull(guard, "guard");
   }

   @Override
   public WriteResult<BlockState> write(
      WriteContext context, ColumnDecision decision, SemanticMapper<BlockState> mapper
   ) {
      return new TellusMinecraft1201CanonicalColumnAdapter(this.guard, mapper)
         .write(this.target, context, decision);
   }
}
