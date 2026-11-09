package com.yucareux.tellus.worldgen.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.write.CanonicalColumnPosition;
import com.yucareux.tellus.worldgen.write.TellusMinecraft1201SemanticMapper;
import com.yucareux.tellus.worldgen.write.WriteContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Focused 1.20.1 feature sink status, preflight, coordinates and rollback tests. */
class TellusMinecraft1201FeaturePlacementSinkTest {
   private static TellusCoordinateContext coordinate;
   private static TellusGenerationContext generation;
   private static Registry<Biome> biomes;

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      MappedRegistry<Biome> mapped = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
      mapped.register(Biomes.PLAINS,
         VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
         Lifecycle.stable());
      biomes = mapped.freeze();
      double scale = 1.0;
      SphereWorldModel model = SphereWorldModel.forWorldScale(scale);
      coordinate = TellusCoordinateContextFactory.fromCubeNetCanonical(
         model, CubeNetLayout.standard(model.config().faceBlocks()),
         new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));
      generation = new TellusGenerationContext(5L, scale, 64, -64, 320,
         new GeometryIdentity("tellus:mc1201-feature", "v1", "projection", "noise", scale));
   }

   @Test
   void unknownQueuedOwnerAndConflictReturnWithoutTarget() {
      WriteContext context = WriteContext.canonical(coordinate, generation, "feature-test");
      TellusMinecraft1201FeaturePlacementSink sink = new TellusMinecraft1201FeaturePlacementSink(null);
      assertEquals(FeaturePlacementStatus.NO_WRITE_UNKNOWN,
         sink.place(context, FeaturePlan.unknown("unknown"), new TellusMinecraft1201SemanticMapper()).status());
      assertEquals(FeaturePlacementStatus.NO_WRITE_QUEUED,
         sink.place(context, FeaturePlan.queued("queued"), new TellusMinecraft1201SemanticMapper()).status());
      FeaturePlacement conflict = new FeaturePlacement(
         FeatureKind.VEGETATION, "bad", context.targetOwner(), context.targetOwner(),
         PlacementOperation.SET, PlacementConflictPolicy.MIRROR_OWNER,
         SemanticMaterial.GRASS, 0, 80, 0, 1, false, Map.of());
      assertEquals(FeaturePlacementStatus.NO_WRITE_CONFLICT,
         sink.place(context, FeaturePlan.available(conflict), new TellusMinecraft1201SemanticMapper()).status());
   }

   @Test
   void knownCanonicalPlacementMapsOneSpan() {
      WriteContext context = WriteContext.canonical(coordinate, generation, "feature-known");
      FeaturePlacement placement = FeaturePlacement.canonical(
         FeatureKind.VEGETATION, "grass", coordinate, PlacementOperation.SET,
         PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS, 0, 80, 0, 1, Map.of());
      ProtoChunk chunk = new ProtoChunk(chunkPosition(), UpgradeData.EMPTY,
         LevelHeightAccessor.create(-64, 320), biomes, null);
      FeaturePlacementResult<BlockState> result = new TellusMinecraft1201FeaturePlacementSink(chunk)
         .place(context, FeaturePlan.available(placement), new TellusMinecraft1201SemanticMapper());
      assertEquals(FeaturePlacementStatus.WRITTEN, result.status());
      assertEquals(1, result.mappedWrites().size());
      CanonicalColumnPosition position = CanonicalColumnPosition.resolve(context);
      assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), chunk.getBlockState(
         new net.minecraft.core.BlockPos(position.x(), 80, position.z())));
      assertTrue(result.mappedWrites().get(0).is(Blocks.GRASS_BLOCK));
   }

   @Test
   void mapsEveryPlacementBeforeMutation() {
      WriteContext context = WriteContext.canonical(coordinate, generation, "feature-preflight");
      FeaturePlacement first = FeaturePlacement.canonical(FeatureKind.VEGETATION, "first", coordinate,
         PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS, 0, 80, 0, 1, Map.of());
      FeaturePlacement second = FeaturePlacement.canonical(FeatureKind.VEGETATION, "second", coordinate,
         PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.DIRT, 0, 81, 0, 1, Map.of());
      ProtoChunk chunk = new ProtoChunk(chunkPosition(), UpgradeData.EMPTY,
         LevelHeightAccessor.create(-64, 320), biomes, null);
      AtomicInteger maps = new AtomicInteger();
      FeaturePlacementResult<BlockState> result = new TellusMinecraft1201FeaturePlacementSink(chunk).place(
         context, new FeaturePlan(FeaturePlanStatus.AVAILABLE, List.of(first, second), List.of()),
         (material, y, ignored) -> {
            if (maps.incrementAndGet() == 2) throw new IllegalStateException("late mapper failure");
            return Blocks.STONE.defaultBlockState();
         });
      assertEquals(FeaturePlacementStatus.INVALID, result.status());
      assertEquals(2, maps.get());
      CanonicalColumnPosition position = CanonicalColumnPosition.resolve(context);
      assertEquals(Blocks.AIR.defaultBlockState(), chunk.getBlockState(
         new net.minecraft.core.BlockPos(position.x(), 80, position.z())));
   }

   @Test
   void failedWriteRestoresThrowingCellAndEarlierCells() {
      WriteContext context = WriteContext.canonical(coordinate, generation, "feature-rollback");
      FeaturePlacement placement = FeaturePlacement.canonical(FeatureKind.VEGETATION, "rollback", coordinate,
         PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS, 0, 80, 0, 2, Map.of());
      TrackingChunk chunk = new TrackingChunk(chunkPosition(), LevelHeightAccessor.create(-64, 320), biomes);
      CanonicalColumnPosition position = CanonicalColumnPosition.resolve(context);
      chunk.setBlockState(new net.minecraft.core.BlockPos(position.x(), 80, position.z()),
         Blocks.GOLD_BLOCK.defaultBlockState(), false);
      chunk.setBlockState(new net.minecraft.core.BlockPos(position.x(), 81, position.z()),
         Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
      chunk.failAtY = 81;
      FeaturePlacementResult<BlockState> result = new TellusMinecraft1201FeaturePlacementSink(chunk)
         .place(context, FeaturePlan.available(placement), new TellusMinecraft1201SemanticMapper());
      assertEquals(FeaturePlacementStatus.INVALID, result.status());
      assertTrue(result.diagnostics().get(0).contains("restored"));
      assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), chunk.getBlockState(
         new net.minecraft.core.BlockPos(position.x(), 80, position.z())));
      assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), chunk.getBlockState(
         new net.minecraft.core.BlockPos(position.x(), 81, position.z())));
   }

   @Test
   void crossPlacementFailureRestoresEarlierPlacementAtomically() {
      WriteContext context = WriteContext.canonical(coordinate, generation, "feature-cross-placement");
      FeaturePlacement first = FeaturePlacement.canonical(FeatureKind.VEGETATION, "first", coordinate,
         PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS, 0, 80, 0, 1, Map.of());
      FeaturePlacement second = FeaturePlacement.canonical(FeatureKind.VEGETATION, "second", coordinate,
         PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.DIRT, 0, 81, 0, 1, Map.of());
      TrackingChunk chunk = new TrackingChunk(chunkPosition(), LevelHeightAccessor.create(-64, 320), biomes);
      CanonicalColumnPosition position = CanonicalColumnPosition.resolve(context);
      chunk.setBlockState(new net.minecraft.core.BlockPos(position.x(), 80, position.z()),
         Blocks.GOLD_BLOCK.defaultBlockState(), false);
      chunk.setBlockState(new net.minecraft.core.BlockPos(position.x(), 81, position.z()),
         Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
      chunk.failAtY = 81;
      FeaturePlacementResult<BlockState> result = new TellusMinecraft1201FeaturePlacementSink(chunk).place(
         context, new FeaturePlan(FeaturePlanStatus.AVAILABLE, List.of(first, second), List.of()),
         new TellusMinecraft1201SemanticMapper());
      assertEquals(FeaturePlacementStatus.INVALID, result.status());
      assertTrue(result.diagnostics().get(0).contains("restored"));
      assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), chunk.getBlockState(
         new net.minecraft.core.BlockPos(position.x(), 80, position.z())));
      assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), chunk.getBlockState(
         new net.minecraft.core.BlockPos(position.x(), 81, position.z())));
   }

   @Test
   void rejectsWrongChunkAndHeightBeforeMutation() {
      WriteContext context = WriteContext.canonical(coordinate, generation, "feature-coordinates");
      FeaturePlacement placement = FeaturePlacement.canonical(FeatureKind.VEGETATION, "height", coordinate,
         PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS, 0, 80, 0, 1, Map.of());
      TrackingChunk wrong = new TrackingChunk(new ChunkPos(0, 0), LevelHeightAccessor.create(-64, 320), biomes);
      FeaturePlacementResult<BlockState> wrongResult = new TellusMinecraft1201FeaturePlacementSink(wrong)
         .place(context, FeaturePlan.available(placement), new TellusMinecraft1201SemanticMapper());
      assertEquals(FeaturePlacementStatus.INVALID, wrongResult.status());
      assertEquals(0, wrong.mutations);
      TrackingChunk shortTarget = new TrackingChunk(chunkPosition(), LevelHeightAccessor.create(0, 16), biomes);
      FeaturePlacementResult<BlockState> shortResult = new TellusMinecraft1201FeaturePlacementSink(shortTarget)
         .place(context, FeaturePlan.available(placement), new TellusMinecraft1201SemanticMapper());
      assertEquals(FeaturePlacementStatus.INVALID, shortResult.status());
      assertEquals(0, shortTarget.mutations);
   }

   @Test
   void oversizedFullPlanIsRejectedColdBeforeTargetOrMapperAccess() {
      FeaturePlacement huge = FeaturePlacement.canonical(FeatureKind.VEGETATION, "huge", coordinate,
         PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS,
         0, 80, 0, Integer.MAX_VALUE, Map.of());
      FeaturePlacement half = FeaturePlacement.canonical(FeatureKind.VEGETATION, "half", coordinate,
         PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS,
         0, 80, 0, 524_289, Map.of());
      for (FeaturePlan plan : List.of(FeaturePlan.available(huge),
         new FeaturePlan(FeaturePlanStatus.AVAILABLE, List.of(half, half), List.of()))) {
         AtomicInteger calls = new AtomicInteger();
         FeaturePlacementResult<BlockState> result = new TellusMinecraft1201FeaturePlacementSink(null).place(
            faultContext(), plan, (material, y, context) -> {
               calls.incrementAndGet();
               throw new AssertionError("overbudget plan reached mapper");
            });
         assertEquals(FeaturePlacementStatus.INVALID, result.status());
         assertTrue(result.diagnostics().get(0).contains("cell budget"));
         assertEquals(0, calls.get());
      }
   }

   @Test
   void errorRestoresEveryPlacementAndCleanDirtyStateThenRethrowsSameError() {
      FaultChunk chunk = seededFaultChunk();
      Error original = new AssertionError("fatal after mutation");
      chunk.writeFailure = original;
      Error thrown = assertThrows(Error.class, () -> new TellusMinecraft1201FeaturePlacementSink(chunk)
         .place(faultContext(), faultPlan(), new TellusMinecraft1201SemanticMapper()));
      assertSame(original, thrown);
      assertOriginalCells(chunk);
      assertFalse(chunk.isUnsaved());
      assertEquals(1, chunk.dirtyRestoreAttempts);
   }

   @Test
   void runtimeFailureRestoresOriginallyCleanAndOriginallyDirtyChunks() {
      for (boolean dirty : new boolean[]{false, true}) {
         FaultChunk chunk = seededFaultChunk();
         chunk.setUnsaved(dirty);
         chunk.writeFailure = new IllegalStateException("runtime after mutation");
         FeaturePlacementResult<BlockState> result = new TellusMinecraft1201FeaturePlacementSink(chunk)
            .place(faultContext(), faultPlan(), new TellusMinecraft1201SemanticMapper());
         assertEquals(FeaturePlacementStatus.INVALID, result.status());
         assertOriginalCells(chunk);
         assertEquals(dirty, chunk.isUnsaved());
      }
   }

   @Test
   void dirtyRestoreFailureReportsIncompleteRecoveryWithOriginalCause() {
      FaultChunk chunk = seededFaultChunk();
      Error original = new AssertionError("fatal after mutation");
      Error restoreFailure = new AssertionError("dirty restore failed");
      chunk.writeFailure = original;
      chunk.dirtyRestoreFailure = restoreFailure;
      IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
         new TellusMinecraft1201FeaturePlacementSink(chunk)
            .place(faultContext(), faultPlan(), new TellusMinecraft1201SemanticMapper()));
      assertTrue(thrown.getMessage().contains("rollback incomplete"));
      assertSame(original, thrown.getCause());
      assertEquals(1, thrown.getSuppressed().length);
      assertSame(restoreFailure, thrown.getSuppressed()[0]);
      assertOriginalCells(chunk);
      assertEquals(1, chunk.dirtyRestoreAttempts);
      assertTrue(chunk.isUnsaved());
   }

   @Test
   void repeatedRollbackErrorStillRestoresEarlierCellAndDirtyState() {
      FaultChunk chunk = seededFaultChunk();
      RuntimeException original = new IllegalStateException("write failure");
      Error repeated = new AssertionError("cached rollback failure");
      chunk.writeFailure = original;
      chunk.cellRestoreFailure = repeated;
      IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
         new TellusMinecraft1201FeaturePlacementSink(chunk)
            .place(faultContext(), faultPlan(), new TellusMinecraft1201SemanticMapper()));
      assertTrue(thrown.getMessage().contains("rollback incomplete"));
      assertSame(original, thrown.getCause());
      assertSame(repeated, thrown.getSuppressed()[0]);
      assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), chunk.getBlockState(faultPosition(80)));
      assertEquals(2, chunk.failedCellRestores);
      assertEquals(1, chunk.dirtyRestoreAttempts);
      assertFalse(chunk.isUnsaved());
   }

   @Test
   void ignoredCellAndDirtyRestoresAreDetectedByReadback() {
      for (boolean ignoreDirty : new boolean[]{false, true}) {
         FaultChunk chunk = seededFaultChunk();
         RuntimeException original = new IllegalStateException("write failure");
         chunk.writeFailure = original;
         chunk.ignoreDirtyRestore = ignoreDirty;
         chunk.ignoreCellRestore = !ignoreDirty;
         IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
            new TellusMinecraft1201FeaturePlacementSink(chunk)
               .place(faultContext(), faultPlan(), new TellusMinecraft1201SemanticMapper()));
         assertTrue(thrown.getMessage().contains("rollback incomplete"));
         assertSame(original, thrown.getCause());
         assertTrue(thrown.getSuppressed()[0].getMessage().contains(
            ignoreDirty ? "restored dirty state" : "restored state"));
         assertEquals(1, chunk.dirtyRestoreAttempts);
         if (ignoreDirty) assertOriginalCells(chunk);
         else assertFalse(chunk.isUnsaved());
      }
   }

   @Test
   void duplicatePlacementsPreserveMappingOrderAndRollbackOriginalState() {
      FeaturePlacement duplicate = FeaturePlacement.canonical(FeatureKind.VEGETATION, "duplicate", coordinate,
         PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS, 0, 80, 0, 1, Map.of());
      for (boolean fail : new boolean[]{false, true}) {
         FaultChunk chunk = seededFaultChunk();
         FeaturePlacement later = FeaturePlacement.canonical(FeatureKind.VEGETATION, "later", coordinate,
            PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.DIRT, 0, 82, 0, 1, Map.of());
         FeaturePlan plan = new FeaturePlan(FeaturePlanStatus.AVAILABLE,
            fail ? List.of(duplicate, duplicate, later) : List.of(duplicate, duplicate), List.of());
         AtomicInteger calls = new AtomicInteger();
         if (fail) chunk.writeFailure = new IllegalStateException("failure after duplicate writes");
         FeaturePlacementResult<BlockState> result = new TellusMinecraft1201FeaturePlacementSink(chunk).place(
            faultContext(), plan, (material, y, context) -> calls.incrementAndGet() == 1
               ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState());
         assertEquals(fail ? 3 : 2, calls.get());
         if (fail) {
            assertEquals(FeaturePlacementStatus.INVALID, result.status());
            assertOriginalCells(chunk);
            assertFalse(chunk.isUnsaved());
         } else {
            assertEquals(FeaturePlacementStatus.WRITTEN, result.status());
            assertEquals(List.of(Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState()),
               result.mappedWrites());
            assertEquals(Blocks.DIRT.defaultBlockState(), chunk.getBlockState(faultPosition(80)));
            assertTrue(chunk.isUnsaved());
         }
      }
   }

   private static WriteContext faultContext() {
      return WriteContext.canonical(coordinate, generation, "feature-fault-recovery");
   }

   private static net.minecraft.core.BlockPos faultPosition(int y) {
      CanonicalColumnPosition position = CanonicalColumnPosition.resolve(faultContext());
      return new net.minecraft.core.BlockPos(position.x(), y, position.z());
   }

   private static FeaturePlan faultPlan() {
      return new FeaturePlan(FeaturePlanStatus.AVAILABLE, List.of(
         FeaturePlacement.canonical(FeatureKind.VEGETATION, "first", coordinate, PlacementOperation.SET,
            PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS, 0, 80, 0, 1, Map.of()),
         FeaturePlacement.canonical(FeatureKind.VEGETATION, "second", coordinate, PlacementOperation.SET,
            PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.DIRT, 0, 81, 0, 2, Map.of())), List.of());
   }

   private static FaultChunk seededFaultChunk() {
      FaultChunk chunk = new FaultChunk();
      chunk.setBlockState(faultPosition(80), Blocks.GOLD_BLOCK.defaultBlockState(), false);
      chunk.setBlockState(faultPosition(81), Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
      chunk.setBlockState(faultPosition(82), Blocks.EMERALD_BLOCK.defaultBlockState(), false);
      chunk.setUnsaved(false);
      return chunk;
   }

   private static void assertOriginalCells(FaultChunk chunk) {
      assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), chunk.getBlockState(faultPosition(80)));
      assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), chunk.getBlockState(faultPosition(81)));
      assertEquals(Blocks.EMERALD_BLOCK.defaultBlockState(), chunk.getBlockState(faultPosition(82)));
   }

   private static final class FaultChunk extends ProtoChunk {
      Throwable writeFailure;
      Throwable cellRestoreFailure;
      Throwable dirtyRestoreFailure;
      boolean rollingBack;
      boolean ignoreCellRestore;
      boolean ignoreDirtyRestore;
      int failedCellRestores;
      int dirtyRestoreAttempts;

      FaultChunk() {
         super(chunkPosition(), UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 320), biomes, null);
      }

      @Override
      public BlockState setBlockState(net.minecraft.core.BlockPos position, BlockState state, boolean moved) {
         if (rollingBack && position.getY() >= 81 && cellRestoreFailure != null) {
            failedCellRestores++;
            throwUnchecked(cellRestoreFailure);
         }
         if (rollingBack && ignoreCellRestore) return getBlockState(position);
         BlockState previous = super.setBlockState(position, state, moved);
         // Model sinks which dirty the target before raising either throwable kind.
         super.setUnsaved(true);
         if (!rollingBack && position.getY() == 82 && writeFailure != null) {
            rollingBack = true;
            throwUnchecked(writeFailure);
         }
         return previous;
      }

      @Override
      public void setUnsaved(boolean unsaved) {
         if (rollingBack && !unsaved) {
            dirtyRestoreAttempts++;
            if (dirtyRestoreFailure != null) throwUnchecked(dirtyRestoreFailure);
            if (ignoreDirtyRestore) return;
         }
         super.setUnsaved(unsaved);
      }

      private static void throwUnchecked(Throwable failure) {
         if (failure instanceof Error fatal) throw fatal;
         throw (RuntimeException)failure;
      }
   }

   private static ChunkPos chunkPosition() {
      CanonicalColumnPosition position = CanonicalColumnPosition.resolve(WriteContext.canonical(coordinate, generation, "feature-chunk"));
      return new ChunkPos(Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16));
   }

   private static final class TrackingChunk extends ProtoChunk {
      int mutations;
      int failAtY = Integer.MIN_VALUE;
      TrackingChunk(ChunkPos position, LevelHeightAccessor height, Registry<Biome> biomeRegistry) {
         super(position, UpgradeData.EMPTY, height, biomeRegistry, null);
      }
      @Override
      public BlockState setBlockState(net.minecraft.core.BlockPos position, BlockState state, boolean moved) {
         mutations++;
         BlockState previous = super.setBlockState(position, state, moved);
         if (position.getY() == failAtY) {
            failAtY = Integer.MIN_VALUE;
            throw new IllegalStateException("sink throws after writing");
         }
         return previous;
      }
   }
}
