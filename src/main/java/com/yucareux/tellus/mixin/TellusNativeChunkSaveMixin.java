package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.persistence.TellusNativeChunkMapAccess;
import com.yucareux.tellus.worldgen.persistence.TellusNativeSaveFence;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class TellusNativeChunkSaveMixin implements TellusNativeChunkMapAccess {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow protected abstract ChunkHolder getUpdatingChunkIfPresent(long key);
    @Shadow protected abstract ChunkHolder getVisibleChunkIfPresent(long key);

    @Override public boolean tellus$hasNativeHolder(long key) {
        return getUpdatingChunkIfPresent(key) != null || getVisibleChunkIfPresent(key) != null || pendingUnloads.containsKey(key);
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void tellus$deferDuringRecovery(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        // Do not mark clean: after successful recovery the engine may retry this native save.
        if (TellusNativeSaveFence.deferEngineSave(level)) cir.setReturnValue(false);
    }

    @Inject(method = "readChunk", at = @At("HEAD"))
    private void tellus$rejectQuarantinedLoads(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (TellusNativeSaveFence.blocksChunkLoads(level))
            throw new IllegalStateException("native chunk recovery is quarantined; normal loading is stopped");
    }
}
