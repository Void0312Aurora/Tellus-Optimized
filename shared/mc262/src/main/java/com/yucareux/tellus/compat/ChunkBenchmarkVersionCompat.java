package com.yucareux.tellus.compat;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Minecraft 26.2 bridge for the server-side benchmark command. */
public final class ChunkBenchmarkVersionCompat {
   private ChunkBenchmarkVersionCompat() {
   }

   public static ChunkAccess requestFullChunk(ServerChunkCache source, int chunkX, int chunkZ) {
      return source.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
   }

   public static int chunkMinY(ChunkAccess chunk) {
      return chunk.getMinY();
   }

   public static CompletableFuture<ChunkAccess> requestFullChunkAsync(
      ServerChunkCache source, int chunkX, int chunkZ
   ) {
      CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
      source.getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true).whenComplete((result, error) -> {
         if (error != null) {
            future.completeExceptionally(error);
         } else if (result == null || !result.isSuccess()) {
            future.completeExceptionally(new IllegalStateException("Chunk source returned a failed result"));
         } else {
            result.ifSuccess(future::complete);
         }
      });
      return future;
   }
}
