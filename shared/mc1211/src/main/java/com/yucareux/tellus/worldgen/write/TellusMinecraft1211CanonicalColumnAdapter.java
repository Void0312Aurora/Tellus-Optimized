package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.GeneratorOwnerView;
import com.yucareux.tellus.worldgen.global.SphericalOwnerView;
import java.util.Objects;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Isolated Minecraft 1.21.1 canonical-column adapter with no lifecycle registration. */
public final class TellusMinecraft1211CanonicalColumnAdapter {
   private final TellusColumnMaterializer guard;
   private final SemanticMapper<BlockState> mapper;

   public TellusMinecraft1211CanonicalColumnAdapter() {
      this(new TellusColumnMaterializer(), new TellusMinecraft1211SemanticMapper());
   }

   public TellusMinecraft1211CanonicalColumnAdapter(
      TellusColumnMaterializer guard, SemanticMapper<BlockState> mapper
   ) {
      this.guard = Objects.requireNonNull(guard, "guard");
      this.mapper = Objects.requireNonNull(mapper, "mapper");
   }

   public WriteResult<BlockState> write(
      ChunkAccess target, WriteContext context, ColumnDecision decision
   ) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(decision, "decision");
      if (decision.status() == DecisionStatus.UNKNOWN) {
         return WriteResult.unknown("column decision is UNKNOWN; canonical adapter performs no write");
      }
      if (decision.status() == DecisionStatus.QUEUED) {
         return WriteResult.queued("column decision is QUEUED; canonical adapter performs no write");
      }
      if (context.apronMirror()) {
         return WriteResult.invalid("canonical column adapter does not write apron targets");
      }
      if (decision.status() == DecisionStatus.PARTIAL
         && !context.partialWritePolicy().allowsKnownLayers()) {
         return WriteResult.partialRejected("PARTIAL policy rejects canonical column write");
      }

      WriteResult<SemanticMaterial> guardResult = this.guard.materialize(context, decision);
      if (guardResult.status().noWrite()) return fromGuard(guardResult);
      OptionalInt terrainY = decision.terrainY();
      SemanticMaterial top = decision.surface().top();
      if (terrainY.isEmpty() || top == SemanticMaterial.UNKNOWN) {
         return fromGuard(WriteResult.<SemanticMaterial>partialRejected(
            "canonical top material or terrain height is unknown"));
      }

      Objects.requireNonNull(target, "target");
      BlockPos position = position(context, terrainY.getAsInt());
      BlockState state = this.mapper.map(top, terrainY.getAsInt(), context);
      target.setBlockState(position, state, false);
      WriteStatus status = decision.status() == DecisionStatus.PARTIAL
         ? WriteStatus.PARTIAL : WriteStatus.WRITTEN;
      return new WriteResult<>(status, java.util.List.of(state),
         java.util.List.of("canonical semantic top cell"), guardResult.persistenceIdentity());
   }

   private static BlockPos position(WriteContext context, int y) {
      GeneratorOwnerView owner = context.targetCoordinate().owner();
      long x;
      long z;
      if (owner instanceof EarthOwnerView earth) {
         x = earth.point().floorBlockX();
         z = earth.point().floorBlockZ();
      } else if (owner instanceof CubeNetCanonicalOwnerView cube) {
         x = cube.block().blockX();
         z = cube.block().blockZ();
      } else if (owner instanceof SphericalOwnerView sphere) {
         x = sphere.block().blockX();
         z = sphere.block().blockZ();
      } else {
         throw new IllegalArgumentException("canonical adapter requires a canonical owner");
      }
      if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
         || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("canonical block coordinate outside Minecraft range");
      }
      return new BlockPos((int)x, y, (int)z);
   }

   private static WriteResult<BlockState> fromGuard(WriteResult<SemanticMaterial> result) {
      return new WriteResult<>(result.status(), java.util.List.of(), result.diagnostics(),
         result.persistenceIdentity());
   }
}
