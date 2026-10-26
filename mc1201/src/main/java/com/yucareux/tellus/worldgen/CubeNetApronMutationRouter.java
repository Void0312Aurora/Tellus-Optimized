package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetSeamTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Routes block-state writes made in a native seam apron to the canonical
 * target-face chunk and refreshes the loaded apron copy as a cache. The
 * derived-write guard suppresses routing only for explicit proxy cache writes;
 * canonical neighbor changes can still mirror back into loaded apron views.
 */
public final class CubeNetApronMutationRouter {
   private static final ThreadLocal<Boolean> DERIVED_WRITE = ThreadLocal.withInitial(() -> false);

   private CubeNetApronMutationRouter() { }

   static boolean isRouting() { return DERIVED_WRITE.get(); }

   public static boolean route(Level level, BlockPos source, BlockState state, int flags) {
      boolean debug = Boolean.getBoolean("tellus.debug.apronMutations")
         && source.getY() >= 99 && source.getY() <= 101
         && source.getX() >= 15344 && source.getX() <= 15376
         && source.getZ() >= -4 && source.getZ() <= 32;
      if (DERIVED_WRITE.get() || !(level instanceof ServerLevel server)) return false;
      if (!(server.getChunkSource().getGenerator() instanceof CubeNetChunkGenerator generator)) {
         if (debug) TellusCommon.LOGGER.info("Apron mutation skipped at {}: generator={}", source,
            server.getChunkSource().getGenerator().getClass().getName());
         return false;
      }
      // Canonical owner edits remain ordinary Level#setBlock mutations, but
      // loaded derived views must change with the owner and remember which
      // chunks need an edit overlay when the view is regenerated.
      if (generator.address(new ChunkPos(source)).isPresent()) {
         var mappings = generator.apronResolver().resolveCanonicalBlock(source.getX() + 0.5, source.getZ() + 0.5);
         if (mappings.isEmpty()) return false;
         CubeNetApronEditIndex.get(server).mark(source);
         for (CubeNetApronResolver.CanonicalProjection mapping : mappings) {
            CubeNetApronChunkSync.mirrorLoadedProxy(server, generator.adapter().layout(), mapping,
               source, state, flags);
         }
         return false;
      }

      BlockPos target;
      CubeNetSeamTransform transform;
      try {
         var projection = generator.apronProjection(new ChunkPos(source));
         if (projection.isEmpty()) {
            if (debug) TellusCommon.LOGGER.info("Apron mutation skipped at {}: no projection; owner={}",
               source, generator.address(new ChunkPos(source)));
            return false;
         }
         var p = projection.get();
         int targetLocalX = p.targetBlockX(source.getX() & 15, source.getZ() & 15);
         int targetLocalZ = p.targetBlockZ(source.getX() & 15, source.getZ() & 15);
         // ChunkProjection stores face-local cells. Convert the local target
         // back into the global planar chart before calling Level#setBlock;
         // using the local pair directly writes into an unrelated net face.
         var targetPlanar = generator.adapter().layout().toPlanarUnbounded(
            new CubeNetLayout.FacePoint(p.targetFace(), targetLocalX + 0.5, targetLocalZ + 0.5));
         target = new BlockPos((int)Math.floor(targetPlanar.x()), source.getY(),
            (int)Math.floor(targetPlanar.z()));
         if (debug) TellusCommon.LOGGER.info("Apron mutation at {} -> {} state={} projection={}",
            source, target, state, p);
         CubeNetLayout.Seam seam = generator.adapter().layout().seam(
            p.sourceFace(), p.sourceSide());
         transform = new CubeNetSeamTransform(generator.adapter().layout(), seam);
      } catch (RuntimeException invalidProjection) {
         if (debug) TellusCommon.LOGGER.warn("Apron mutation mapping failed at {} state={}", source,
            state, invalidProjection);
         return false;
      }
      Rotation sourceToTarget = inverseRotation(transform.targetToSourceGridTransform());
      CubeNetApronEditIndex.get(server).mark(target);
      // Let canonical neighbor updates pass through the same write-through
      // path. Each changed owner block mirrors its new state to the loaded
      // apron. Only the explicit cache write below is suppressed from routing.
      boolean canonicalChanged = server.setBlock(target, state.rotate(sourceToTarget), flags);
      // A loaded apron is a normal LevelChunk from vanilla's point of view.
      // Keep that cache visually and interactively current until it unloads;
      // persistence is blocked by CubeNetChunkPersistenceMixin.
      boolean cacheChanged = setDerivedBlock(server, source, state, flags);
      CubeNetApronBlockEntitySync.copy(server, source, target, server.getBlockState(target));
      if (debug) TellusCommon.LOGGER.info("Apron mutation applied at {} canonicalChanged={} cacheChanged={}",
         source, canonicalChanged, cacheChanged);
      return canonicalChanged || cacheChanged;
   }

   static BlockPos canonicalTarget(ServerLevel level, CubeNetChunkGenerator generator, BlockPos source,
      CubeNetApronResolver.ChunkProjection projection) {
      int targetLocalX = projection.targetBlockX(source.getX() & 15, source.getZ() & 15);
      int targetLocalZ = projection.targetBlockZ(source.getX() & 15, source.getZ() & 15);
      var targetPlanar = generator.adapter().layout().toPlanarUnbounded(
         new CubeNetLayout.FacePoint(projection.targetFace(), targetLocalX + 0.5, targetLocalZ + 0.5));
      return new BlockPos((int)Math.floor(targetPlanar.x()), source.getY(),
         (int)Math.floor(targetPlanar.z()));
   }

   static boolean setDerivedBlock(Level level, BlockPos position, BlockState state, int flags) {
      boolean previous = DERIVED_WRITE.get();
      DERIVED_WRITE.set(true);
      try {
         return level.setBlock(position, state, flags);
      } finally {
         DERIVED_WRITE.set(previous);
      }
   }

   private static Rotation inverseRotation(CubeNetSeamTransform.GridTransform targetToSource) {
      int clockwiseTargetToSource;
      if (targetToSource.m00() == 1 && targetToSource.m10() == 0) clockwiseTargetToSource = 0;
      else if (targetToSource.m00() == 0 && targetToSource.m10() == 1) clockwiseTargetToSource = 1;
      else if (targetToSource.m00() == -1 && targetToSource.m10() == 0) clockwiseTargetToSource = 2;
      else clockwiseTargetToSource = 3;
      return switch ((4 - clockwiseTargetToSource) & 3) {
         case 0 -> Rotation.NONE;
         case 1 -> Rotation.CLOCKWISE_90;
         case 2 -> Rotation.CLOCKWISE_180;
         default -> Rotation.COUNTERCLOCKWISE_90;
      };
   }
}
