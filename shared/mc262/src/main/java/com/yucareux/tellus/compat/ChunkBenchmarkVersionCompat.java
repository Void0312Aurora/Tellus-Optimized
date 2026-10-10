package com.yucareux.tellus.compat;

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
}
