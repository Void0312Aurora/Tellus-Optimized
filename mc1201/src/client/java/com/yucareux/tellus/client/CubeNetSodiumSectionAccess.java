package com.yucareux.tellus.client;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

/** Version-pinned access to Sodium's native section build queue. */
public interface CubeNetSodiumSectionAccess {
   Long2ReferenceMap<RenderSection> tellus$sections();

   Map<ChunkUpdateType, ArrayDeque<RenderSection>> tellus$rebuildLists();

   default void tellus$queueInitialBuilds(int centerChunkX, int centerChunkZ) {
      var queue = this.tellus$rebuildLists().get(ChunkUpdateType.INITIAL_BUILD);
      // This off-screen manager has no visibility collector to populate its
      // queues. Recreate the queue each tick, including sections which Sodium
      // marked INITIAL_BUILD in onSectionAdded but has never submitted.
      queue.clear();
      var pending = new ArrayList<RenderSection>();
      for (RenderSection section : this.tellus$sections().values()) {
         if (section.isDisposed() || section.isBuilt() || section.getBuildCancellationToken() != null) continue;
         section.setPendingUpdate(ChunkUpdateType.INITIAL_BUILD);
         pending.add(section);
      }
      // The first renderable area must be around the destination pose. Sodium's
      // normal visibility collector is unavailable while this manager is
      // staged, so insertion order would otherwise be an arbitrary hash-map
      // order and could spend the whole handoff building distant sections.
      pending.sort(Comparator.comparingInt(section ->
         Math.max(Math.abs(section.getChunkX() - centerChunkX),
            Math.abs(section.getChunkZ() - centerChunkZ))));
      queue.addAll(pending);
   }

   default boolean tellus$coreSectionsBuilt(int centerChunkX, int centerChunkZ, int radius) {
      for (RenderSection section : this.tellus$sections().values()) {
         if (Math.max(Math.abs(section.getChunkX() - centerChunkX),
               Math.abs(section.getChunkZ() - centerChunkZ)) > radius) continue;
         // A cached renderer may still carry a normal rebuild request from
         // the chart that was active before the seam. Its previous mesh is
         // still drawable, so only an absent mesh blocks promotion;
         // the rebuild can continue after the cached manager becomes active.
         if (section.isDisposed() || !section.isBuilt()) return false;
      }
      return true;
   }

   default boolean tellus$allSectionsBuilt() {
      for (RenderSection section : this.tellus$sections().values()) {
         if (section.isDisposed() || !section.isBuilt() || section.getBuildCancellationToken() != null
            || section.getPendingUpdate() != null) return false;
      }
      return true;
   }

   default int tellus$rebindBlockEntities(ClientLevel world) {
      int count = 0;
      for (RenderSection section : this.tellus$sections().values()) {
         BlockEntity[] culled = rebind(section.getCulledBlockEntities(), world);
         BlockEntity[] global = rebind(section.getGlobalBlockEntities(), world);
         CubeNetSodiumEntityAccess access = (CubeNetSodiumEntityAccess)(Object)section;
         access.tellus$setCulledBlockEntities(culled);
         access.tellus$setGlobalBlockEntities(global);
         count += (culled == null ? 0 : culled.length) + (global == null ? 0 : global.length);
      }
      return count;
   }

   private static BlockEntity[] rebind(BlockEntity[] staged, ClientLevel world) {
      if (staged == null) return null;
      var live = new ArrayList<BlockEntity>(staged.length);
      for (BlockEntity entity : staged) {
         BlockEntity promoted = world.getBlockEntity(entity.getBlockPos());
         if (promoted != null && promoted.getType() == entity.getType()) live.add(promoted);
      }
      return live.isEmpty() ? null : live.toArray(BlockEntity[]::new);
   }
}
