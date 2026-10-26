package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Mirrors seam-apron block-entity payloads while keeping canonical storage authoritative. */
public final class CubeNetApronBlockEntitySync {
   private static final ThreadLocal<Boolean> SYNCING = ThreadLocal.withInitial(() -> false);

   private CubeNetApronBlockEntitySync() { }

   /** Called from BlockEntity#setChanged so inventory/text/state changes cross the seam. */
   public static void onChanged(BlockEntity entity) {
      if (SYNCING.get() || CubeNetApronMutationRouter.isRouting()
         || !(entity.getLevel() instanceof ServerLevel level)) return;
      if (!(level.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)) return;

      BlockPos position = entity.getBlockPos();
      if (generator.address(new ChunkPos(position)).isPresent()) {
         CubeNetApronEditIndex.get(level).mark(position);
         BlockState canonicalState = level.getBlockState(position);
         for (CubeNetApronResolver.CanonicalProjection mapping
            : generator.apronResolver().resolveCanonicalBlock(position.getX() + 0.5, position.getZ() + 0.5)) {
            BlockPos proxy = new BlockPos(mapping.sourceBlockX(), position.getY(), mapping.sourceBlockZ());
            if (level.getChunkSource().getChunkNow(proxy.getX() >> 4, proxy.getZ() >> 4) == null) continue;
            CubeNetLayout.Seam seam = generator.adapter().layout().seam(
               mapping.sourceFace(), mapping.sourceSide());
            BlockState proxyState = canonicalState.rotate(CubeNetApronChunkSync.rotation(
               new com.yucareux.tellus.worldgen.global.CubeNetSeamTransform(
                  generator.adapter().layout(), seam).targetToSourceClockwiseQuarterTurns().orElseThrow()));
            copy(level, position, proxy, proxyState);
         }
         return;
      }

      var projection = generator.apronProjection(new ChunkPos(position)).orElse(null);
      if (projection == null) return;
      BlockPos canonical = CubeNetApronMutationRouter.canonicalTarget(level, generator, position, projection);
      if (canonical == null
         || level.getChunkSource().getChunkNow(canonical.getX() >> 4, canonical.getZ() >> 4) == null) return;
      CubeNetApronEditIndex.get(level).mark(canonical);
      copy(level, position, canonical, level.getBlockState(canonical));
   }

   static void copy(ServerLevel level, BlockPos source, BlockPos target, BlockState targetState) {
      if (source.equals(target) || SYNCING.get()) return;
      if (level.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4) == null) return;
      BlockEntity sourceEntity = level.getBlockEntity(source);
      BlockEntity targetEntity = level.getBlockEntity(target);
      if (sourceEntity == null) {
         if (targetEntity != null) level.removeBlockEntity(target);
         return;
      }

      CompoundTag desired = sourceEntity.saveWithFullMetadata();
      desired.putInt("x", target.getX());
      desired.putInt("y", target.getY());
      desired.putInt("z", target.getZ());
      BlockEntity loaded;
      try {
         loaded = BlockEntity.loadStatic(target, targetState, desired);
      } catch (RuntimeException invalidPayload) {
         TellusCommon.LOGGER.warn("Failed to mirror seam block entity {} -> {}", source, target,
            invalidPayload);
         return;
      }
      if (loaded == null) return;

      boolean previous = SYNCING.get();
      SYNCING.set(true);
      try {
         if (targetEntity != null && targetEntity.getType() == loaded.getType()) {
            CompoundTag current = targetEntity.saveWithFullMetadata();
            current.putInt("x", target.getX());
            current.putInt("y", target.getY());
            current.putInt("z", target.getZ());
            if (!current.equals(desired)) {
               targetEntity.load(desired);
               targetEntity.setChanged();
               level.sendBlockUpdated(target, targetState, targetState, 3);
            }
         } else {
            if (targetEntity != null) level.removeBlockEntity(target);
            level.setBlockEntity(loaded);
            level.sendBlockUpdated(target, targetState, targetState, 3);
         }
      } finally {
         SYNCING.set(previous);
      }
   }
}
