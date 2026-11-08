package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Isolated 1.21.1 sink-backed writer; no generator or lifecycle registration. */
public final class TellusMinecraft1211ColumnWriter implements TellusColumnWriter<BlockState> {
   private final ChunkAccess target;
   private final TellusColumnMaterializer guard;

   public TellusMinecraft1211ColumnWriter(ChunkAccess target) {
      this(target, new TellusColumnMaterializer());
   }

   public TellusMinecraft1211ColumnWriter(ChunkAccess target, TellusColumnMaterializer guard) {
      this.target = target;
      this.guard = java.util.Objects.requireNonNull(guard, "guard");
   }

   @Override
   public WriteResult<BlockState> write(
      WriteContext context, ColumnDecision decision, SemanticMapper<BlockState> mapper
   ) {
      return new TellusMinecraft1211CanonicalColumnAdapter(this.guard, mapper)
         .write(this.target, context, decision);
   }
}
