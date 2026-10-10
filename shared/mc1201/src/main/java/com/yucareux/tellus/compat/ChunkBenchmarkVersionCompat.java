package com.yucareux.tellus.compat;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

/** Minecraft 1.20.1 bridge for the server-side benchmark command. */
public final class ChunkBenchmarkVersionCompat {
   private ChunkBenchmarkVersionCompat() {
   }

   public static ChunkAccess requestFullChunk(ServerChunkCache source, int chunkX, int chunkZ) {
      return source.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
   }
}
