package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.persistence.TellusNativeSaveFence;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class TellusNativeRecoveryStartupMixin {
    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void tellus$recoverBeforeChunkLoading(ChunkProgressListener progress, CallbackInfo ci) {
        TellusNativeSaveFence.recoverBeforeLevels((MinecraftServer)(Object)this);
    }
}
