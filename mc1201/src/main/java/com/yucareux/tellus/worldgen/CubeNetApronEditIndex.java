package com.yucareux.tellus.worldgen;

import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent index of canonical chunks whose seam-adjacent blocks have been
 * edited. Derived apron chunks consult it before loading an owner chunk, so
 * untouched apron terrain does not force a second chunk-generation pass.
 */
public final class CubeNetApronEditIndex extends SavedData {
   private static final String DATA_ID = "tellus_cube_net_apron_edits";
   private static final String CHUNKS_KEY = "canonical_chunks";
   private static final String BLOCKS_KEY = "canonical_blocks";
   private final Set<Long> canonicalChunks = new HashSet<>();
   private final Map<Long, Set<Long>> canonicalBlocks = new HashMap<>();
   private boolean preciseBlockIndex = true;

   public static CubeNetApronEditIndex get(ServerLevel level) {
      return level.getDataStorage().computeIfAbsent(CubeNetApronEditIndex::load,
         CubeNetApronEditIndex::new, DATA_ID);
   }

   public boolean contains(ChunkPos pos) {
      return this.canonicalChunks.contains(pos.toLong());
   }

   public void mark(ChunkPos pos) {
      if (this.canonicalChunks.add(pos.toLong())) this.setDirty();
   }

   /** Records an edited canonical seam cell so apron reloads can restore sparse edits cheaply. */
   public void mark(BlockPos pos) {
      ChunkPos chunk = new ChunkPos(pos);
      long chunkKey = chunk.toLong();
      long blockKey = pos.asLong();
      boolean changed = this.canonicalChunks.add(chunkKey);
      changed |= this.canonicalBlocks.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(blockKey);
      if (changed) this.setDirty();
   }

   public boolean hasPreciseBlockIndex() {
      return this.preciseBlockIndex;
   }

   public List<BlockPos> blockPositions(ChunkPos chunk) {
      Set<Long> positions = this.canonicalBlocks.get(chunk.toLong());
      if (positions == null || positions.isEmpty()) return List.of();
      List<BlockPos> result = new ArrayList<>(positions.size());
      for (long position : positions) result.add(BlockPos.of(position));
      return result;
   }

   @Override
   public CompoundTag save(CompoundTag tag) {
      long[] keys = this.canonicalChunks.stream().mapToLong(Long::longValue).toArray();
      tag.putLongArray(CHUNKS_KEY, keys);
      long[] blocks = this.canonicalBlocks.values().stream()
         .flatMapToLong(values -> values.stream().mapToLong(Long::longValue)).toArray();
      tag.putLongArray(BLOCKS_KEY, blocks);
      tag.putBoolean("precise_block_index", this.preciseBlockIndex);
      return tag;
   }

   private static CubeNetApronEditIndex load(CompoundTag tag) {
      CubeNetApronEditIndex index = new CubeNetApronEditIndex();
      for (long key : tag.getLongArray(CHUNKS_KEY)) index.canonicalChunks.add(key);
      index.preciseBlockIndex = tag.getBoolean("precise_block_index");
      for (long block : tag.getLongArray(BLOCKS_KEY)) {
         BlockPos pos = BlockPos.of(block);
         index.canonicalBlocks.computeIfAbsent(new ChunkPos(pos).toLong(), ignored -> new HashSet<>())
            .add(block);
      }
      return index;
   }
}
