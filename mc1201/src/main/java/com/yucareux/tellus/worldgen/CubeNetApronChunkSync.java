package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetSeamTransform;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;

/** Keeps loaded and reloaded apron chunks as views of their canonical chunks. */
public final class CubeNetApronChunkSync {
   private static final int MAX_RETRY_TICKS = 1200;
   private static final TicketType<ChunkPos> OWNER_LOAD =
      TicketType.create("tellus_cube_net_apron_owner", Comparator.comparingLong(ChunkPos::toLong));
   private static final Map<ServerLevel, Map<ChunkPos, Integer>> PENDING_REFRESHES = new WeakHashMap<>();

   private CubeNetApronChunkSync() { }

   public static void register() {
      ServerChunkEvents.CHUNK_LOAD.register(CubeNetApronChunkSync::onChunkLoad);
      ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
         Map<ChunkPos, Integer> pending = PENDING_REFRESHES.get(level);
         if (pending != null && pending.remove(chunk.getPos()) != null) releaseOwnerTicket(level, chunk.getPos());
      });
      ServerTickEvents.END_WORLD_TICK.register(CubeNetApronChunkSync::retryPendingRefreshes);
   }

   static void mirrorLoadedProxy(ServerLevel level, CubeNetLayout layout,
      CubeNetApronResolver.CanonicalProjection mapping, BlockPos canonicalPos,
      BlockState canonicalState, int flags) {
      int proxyChunkX = Math.floorDiv(mapping.sourceBlockX(), 16);
      int proxyChunkZ = Math.floorDiv(mapping.sourceBlockZ(), 16);
      LevelChunk proxyChunk = level.getChunkSource().getChunkNow(proxyChunkX, proxyChunkZ);
      if (proxyChunk == null) return;
      BlockPos proxyPos = new BlockPos(mapping.sourceBlockX(), canonicalPos.getY(), mapping.sourceBlockZ());
      CubeNetLayout.Seam seam = layout.seam(mapping.sourceFace(), mapping.sourceSide());
      BlockState proxyState = canonicalState.rotate(rotation(new CubeNetSeamTransform(layout, seam)
         .targetToSourceClockwiseQuarterTurns().orElseThrow()));
      CubeNetApronMutationRouter.setDerivedBlock(level, proxyPos, proxyState, flags);
      CubeNetApronBlockEntitySync.copy(level, canonicalPos, proxyPos, proxyState);
   }

   static ChunkPos canonicalChunkPosition(CubeNetLayout layout,
      CubeNetApronResolver.ChunkProjection projection) {
      int targetLocalX = projection.targetChunkX() * 16 + 8;
      int targetLocalZ = projection.targetChunkZ() * 16 + 8;
      CubeNetLayout.PlanarPoint targetCenter = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(
         projection.targetFace(), targetLocalX + 0.5, targetLocalZ + 0.5));
      return new ChunkPos(
         Math.floorDiv((int)Math.floor(targetCenter.x()), 16),
         Math.floorDiv((int)Math.floor(targetCenter.z()), 16));
   }

   private static void onChunkLoad(ServerLevel level, LevelChunk proxyChunk) {
      if (!(level.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)) return;
      var projection = generator.apronProjection(proxyChunk.getPos()).orElse(null);
      if (projection == null) return;
      ChunkPos canonicalPos = canonicalChunkPosition(generator.adapter().layout(), projection);
      if (!CubeNetApronEditIndex.get(level).contains(canonicalPos)) return;

      // CHUNK_LOAD fires while ChunkMap is still completing this chunk's load.
      // Mutating it synchronously calls Level#getChunk and waits for the very
      // completion callback currently on the server thread. Always defer the
      // overlay until END_WORLD_TICK, when getChunkNow can safely find it.
      PENDING_REFRESHES.computeIfAbsent(level, ignored -> new HashMap<>())
         .put(proxyChunk.getPos(), MAX_RETRY_TICKS);
      // The owner can be far outside every player's ordinary chunk tickets.
      // Merely polling getChunkNow would never restore that saved edit. Keep
      // it loaded only for this overlay and request FULL off this callback.
      level.getChunkSource().addRegionTicket(OWNER_LOAD, canonicalPos, 0, proxyChunk.getPos());
      CompletableFuture.supplyAsync(() -> level.getChunkSource().getChunkFuture(
         canonicalPos.x, canonicalPos.z, ChunkStatus.FULL, true), Util.backgroundExecutor())
         .thenCompose(future -> future).whenComplete((result, error) -> {
            if (error != null) TellusCommon.LOGGER.warn("Cube-net apron owner load failed for {} -> {}",
               proxyChunk.getPos(), canonicalPos, error);
         });
      if (Boolean.getBoolean("tellus.debug.apronMutations")) {
         TellusCommon.LOGGER.info("Queued apron refresh for {} until the chunk-load callback completes",
            proxyChunk.getPos());
      }
   }

   /** Returns false only when the edited owner chunk is not ready yet. */
   private static boolean refreshProxy(ServerLevel level, LevelChunk proxyChunk) {
      if (!(level.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)) return true;
      var projection = generator.apronProjection(proxyChunk.getPos()).orElse(null);
      if (projection == null) return true;

      CubeNetLayout layout = generator.adapter().layout();
      // targetCenter is in planar block coordinates; ChunkPos stores chunk
      // coordinates. Raw block coordinates would miss every saved edit index
      // entry and restore generated terrain on apron rebuild.
      ChunkPos canonicalPos = canonicalChunkPosition(layout, projection);
      if (!CubeNetApronEditIndex.get(level).contains(canonicalPos)) return true;

      // Never synchronously wait for a canonical chunk from the server thread.
      // The world-tick retry below handles the normal case where the derived
      // view loads before its edited owner.
      LevelChunk canonicalChunk = level.getChunkSource().getChunkNow(canonicalPos.x, canonicalPos.z);
      if (canonicalChunk == null) return false;
      CubeNetLayout.Seam seam = layout.seam(projection.sourceFace(), projection.sourceSide());
      Rotation targetToSource = rotation(new CubeNetSeamTransform(layout, seam)
         .targetToSourceClockwiseQuarterTurns().orElseThrow());
      int copied = 0;
      CubeNetApronEditIndex editIndex = CubeNetApronEditIndex.get(level);
      if (editIndex.hasPreciseBlockIndex()) {
         for (BlockPos ownerPos : editIndex.blockPositions(canonicalPos)) {
            for (CubeNetApronResolver.CanonicalProjection mapping
               : generator.apronResolver().resolveCanonicalBlock(ownerPos.getX() + 0.5, ownerPos.getZ() + 0.5)) {
               if (mapping.sourceFace() != projection.sourceFace()
                  || mapping.sourceSide() != projection.sourceSide()
                  || (mapping.sourceBlockX() >> 4) != proxyChunk.getPos().x
                  || (mapping.sourceBlockZ() >> 4) != proxyChunk.getPos().z) continue;
               BlockPos proxyPos = new BlockPos(mapping.sourceBlockX(), ownerPos.getY(), mapping.sourceBlockZ());
               BlockState desired = canonicalChunk.getBlockState(ownerPos).rotate(targetToSource);
               if (!proxyChunk.getBlockState(proxyPos).equals(desired)) {
                  CubeNetApronMutationRouter.setDerivedBlock(level, proxyPos, desired, 3);
                  copied++;
               }
               if (desired.hasBlockEntity() || proxyChunk.getBlockState(proxyPos).hasBlockEntity()) {
                  CubeNetApronBlockEntitySync.copy(level, ownerPos, proxyPos, desired);
               }
            }
         }
      } else {
         // Older SavedData files only recorded edited chunks. Keep a compatibility
         // rebuild path for those worlds; newly edited worlds use the sparse index
         // above and avoid scanning a million cells synchronously on chunk load.
         for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
               int faceX = projection.targetBlockX(x, z);
               int faceZ = projection.targetBlockZ(x, z);
               CubeNetLayout.PlanarPoint planar = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(
                  projection.targetFace(), faceX + 0.5, faceZ + 0.5));
               BlockPos ownerPos = new BlockPos((int)Math.floor(planar.x()), y, (int)Math.floor(planar.z()));
               BlockPos proxyPos = new BlockPos(proxyChunk.getPos().getMinBlockX() + x, y,
                  proxyChunk.getPos().getMinBlockZ() + z);
               BlockState desired = canonicalChunk.getBlockState(ownerPos).rotate(targetToSource);
               if (!proxyChunk.getBlockState(proxyPos).equals(desired)) {
                  CubeNetApronMutationRouter.setDerivedBlock(level, proxyPos, desired, 3);
                  copied++;
               }
               if (desired.hasBlockEntity() || proxyChunk.getBlockState(proxyPos).hasBlockEntity()) {
                  CubeNetApronBlockEntitySync.copy(level, ownerPos, proxyPos, desired);
               }
            }
         }
      }
      if (copied > 0 && Boolean.getBoolean("tellus.debug.apronMutations")) {
         TellusCommon.LOGGER.info("Refreshed {} blocks in derived apron {} from edited owner chunk {}",
            copied, proxyChunk.getPos(), canonicalPos);
      }
      return true;
   }

   private static void retryPendingRefreshes(ServerLevel level) {
      Map<ChunkPos, Integer> pending = PENDING_REFRESHES.get(level);
      if (pending == null || pending.isEmpty()) return;
      retryPendingRefreshes(pending, proxyPos -> {
         LevelChunk proxyChunk = level.getChunkSource().getChunkNow(proxyPos.x, proxyPos.z);
         // The first world-tick callback can still precede promotion into
         // getChunkNow. Both this view and its owner must be available before
         // the overlay is complete; absence must keep the retry alive.
         boolean refreshed = proxyChunk != null && refreshProxy(level, proxyChunk);
         if (refreshed && !pending.containsKey(proxyPos)) releaseOwnerTicket(level, proxyPos);
         return refreshed;
      }, proxyPos -> {
         releaseOwnerTicket(level, proxyPos);
         TellusCommon.LOGGER.warn("Cube-net apron edit refresh timed out for {}", proxyPos);
      });
      if (pending.isEmpty()) PENDING_REFRESHES.remove(level);
   }

   static void retryPendingRefreshes(Map<ChunkPos, Integer> pending, Predicate<ChunkPos> refresh,
      Consumer<ChunkPos> expired) {
      // Refreshing a proxy may synchronously load neighboring chunks, whose
      // CHUNK_LOAD callbacks add more entries to this map. Process a stable
      // snapshot and remove each item before refresh so callbacks can safely
      // enqueue it again for the next tick.
      for (ChunkPos proxyPos : List.copyOf(pending.keySet())) {
         Integer remainingTicks = pending.remove(proxyPos);
         if (remainingTicks == null) continue;
         if (remainingTicks <= 0) {
            pending.put(proxyPos, 0); // failed views must not become staging-ready
            continue;
         }
         if (!refresh.test(proxyPos) && !pending.containsKey(proxyPos)) {
            if (remainingTicks > 1) pending.put(proxyPos, remainingTicks - 1);
            else {
               pending.put(proxyPos, 0);
               expired.accept(proxyPos);
            }
         }
      }
   }

   /** FULL chunk futures precede the deferred edit overlay; packet staging must wait for both. */
   static boolean readyForStaging(ServerLevel level, ChunkPos center, int radius) {
      Map<ChunkPos, Integer> pending = PENDING_REFRESHES.get(level);
      if (pending == null) return true;
      for (ChunkPos proxyPos : pending.keySet()) {
         if (Math.abs((long)proxyPos.x - center.x) <= radius
            && Math.abs((long)proxyPos.z - center.z) <= radius) return false;
      }
      return true;
   }

   private static void releaseOwnerTicket(ServerLevel level, ChunkPos proxyPos) {
      if (!(level.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)) return;
      var projection = generator.apronProjection(proxyPos).orElse(null);
      if (projection == null) return;
      level.getChunkSource().removeRegionTicket(OWNER_LOAD,
         canonicalChunkPosition(generator.adapter().layout(), projection), 0, proxyPos);
   }

   static Rotation rotation(int clockwiseQuarterTurns) {
      return switch (Math.floorMod(clockwiseQuarterTurns, 4)) {
         case 0 -> Rotation.NONE;
         case 1 -> Rotation.CLOCKWISE_90;
         case 2 -> Rotation.CLOCKWISE_180;
         default -> Rotation.COUNTERCLOCKWISE_90;
      };
   }

}
