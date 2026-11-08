package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Isolated 1.21.1 sink-backed writer; no generator or lifecycle registration. */
public final class TellusMinecraft1211ColumnWriter implements TellusColumnWriter<BlockState> {
   private final ChunkAccess target;
   private final TellusColumnMaterializer materializer;
   private final SphereWorldModel model;

   public TellusMinecraft1211ColumnWriter(ChunkAccess target) {
      this(target, new TellusColumnMaterializer());
   }

   public TellusMinecraft1211ColumnWriter(ChunkAccess target, TellusColumnMaterializer materializer) {
      this(target, materializer, null);
   }

   /** Uses an explicitly supplied model for custom face sizes. */
   public TellusMinecraft1211ColumnWriter(
      ChunkAccess target, TellusColumnMaterializer materializer, SphereWorldModel model
   ) {
      this.target = target;
      this.materializer = java.util.Objects.requireNonNull(materializer, "materializer");
      this.model = model;
   }

   @Override
   public WriteResult<BlockState> write(
      WriteContext context, ColumnDecision decision, SemanticMapper<BlockState> mapper
   ) {
      return new TellusMinecraft1211CanonicalColumnAdapter(this.materializer, mapper, this.model)
         .write(this.target, context, decision);
   }
}
