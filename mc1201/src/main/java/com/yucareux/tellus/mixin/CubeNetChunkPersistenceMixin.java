package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.CubeNetChunkGenerator;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps native apron chunks as derived views rather than a second storage
 * authority. They are regenerated from their canonical target-face chunk on
 * demand and therefore must never be serialized by ChunkMap.
 */
@Mixin(ChunkMap.class)
public abstract class CubeNetChunkPersistenceMixin {
   @Shadow @Final private ServerLevel level;

   @Inject(method = "save", at = @At("HEAD"), cancellable = true)
   private void tellus$skipDerivedApron(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
      if (this.level.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator
         && generator.apronProjection(chunk.getPos()).isPresent()) {
         // Mark the view clean so ChunkMap does not enqueue it on every tick.
         // Player edits still need a separate write-through path to the
         // canonical target chunk; silently persisting a derived copy would be
         // worse because it would become stale authority after a reload.
         chunk.setUnsaved(false);
         cir.setReturnValue(true);
      }
   }
}
