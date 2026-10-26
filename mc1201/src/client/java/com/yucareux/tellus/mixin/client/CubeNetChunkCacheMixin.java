package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Consumer;

/** Keeps native source reception active alongside a bounded remote cache. */
@Mixin(ClientChunkCache.class)
public abstract class CubeNetChunkCacheMixin {
   @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
      at = @At("HEAD"), cancellable = true)
   private void tellus$readHeldSource(int x, int z, ChunkStatus status, boolean create,
      CallbackInfoReturnable<LevelChunk> cir) {
      LevelChunk staged = CubeNetClientTransition.stagedChunk((ClientChunkCache)(Object)this, x, z);
      if (staged != null) cir.setReturnValue(staged);
   }

   @Inject(method = "updateViewCenter", at = @At("HEAD"), cancellable = true)
   private void tellus$deferStagedCenter(int x, int z, CallbackInfo ci) {
      if (CubeNetClientTransition.stageCenter((ClientChunkCache)(Object)this, x, z)) ci.cancel();
   }

   @Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
   private void tellus$receiveRemoteChunk(int x, int z, FriendlyByteBuf data, CompoundTag heights,
      Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities, CallbackInfoReturnable<LevelChunk> cir) {
      LevelChunk chunk = CubeNetClientTransition.stageChunk((ClientChunkCache)(Object)this,
         x, z, data, heights, blockEntities);
      if (chunk != null) cir.setReturnValue(chunk);
   }
}
