package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
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

class TellusMinecraft1201ColumnExpansionTest {
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1000.0);
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(1, 1000, 0, -16, 32);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromCubeNetCanonical(
      MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
      new CubeNetLayout.FacePoint(CubeNetLayout.Face.NEG_X, 16.5, 32.5));
   private static final WriteContext CONTEXT = WriteContext.canonical(COORDINATE, GENERATION, "full-column");
   private static final CanonicalColumnPosition POSITION = CanonicalColumnPosition.resolve(CONTEXT);
   private static Registry<Biome> biomes;

   @BeforeAll static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      MappedRegistry<Biome> mapped = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
      mapped.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
         .getOrThrow(Biomes.PLAINS).value(), Lifecycle.stable());
      biomes = mapped.freeze();
   }

   @Test void writesEveryKnownLayerAtActualNegativePlanarColumn() {
      TrackingChunk target = chunk();
      target.setBlockState(position(8), Blocks.GOLD_BLOCK.defaultBlockState(), false);
      BlockPos neighbor = position(0).offset(1, 0, 0);
      target.setBlockState(neighbor, Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
      var result = new TellusMinecraft1201ColumnWriter(target).write(CONTEXT, decision(COORDINATE),
         new TellusMinecraft1201SemanticMapper());
      assertEquals(WriteStatus.WRITTEN, result.status());
      assertEquals(24, result.mappedWrites().size());
      assertEquals(Blocks.BEDROCK.defaultBlockState(), target.getBlockState(position(-16)));
      assertEquals(Blocks.DEEPSLATE.defaultBlockState(), target.getBlockState(position(0)));
      assertEquals(Blocks.STONE.defaultBlockState(), target.getBlockState(position(1)));
      assertEquals(Blocks.DIRT.defaultBlockState(), target.getBlockState(position(2)));
      assertEquals(Blocks.DIRT.defaultBlockState(), target.getBlockState(position(3)));
      assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), target.getBlockState(position(4)));
      for (int y = 5; y <= 7; y++) assertEquals(Blocks.WATER.defaultBlockState(), target.getBlockState(position(y)));
      assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), target.getBlockState(position(8)));
      assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), target.getBlockState(neighbor));
   }

   @Test void mapsAllValuesBeforeMutationAndRejectsLateMapperFailure() {
      TrackingChunk target = chunk();
      AtomicInteger maps = new AtomicInteger();
      var result = new TellusMinecraft1201CanonicalColumnAdapter(new TellusColumnMaterializer(),
         (material, y, context) -> {
            if (maps.incrementAndGet() == 10) throw new IllegalStateException("late mapper failure");
            return Blocks.STONE.defaultBlockState();
         }).write(target, CONTEXT, decision(COORDINATE));
      assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
      assertEquals(10, maps.get());
      assertEquals(0, target.mutations);
      assertTrue(result.mappedWrites().isEmpty());
   }

   @Test void failedSinkRestoresEvenTheCellChangedBeforeTheThrow() {
      TrackingChunk target = chunk();
      target.setBlockState(position(-16), Blocks.GOLD_BLOCK.defaultBlockState(), false);
      target.setBlockState(position(0), Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
      target.failAtZero = true;
      var result = new TellusMinecraft1201CanonicalColumnAdapter().write(target, CONTEXT, decision(COORDINATE));
      assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
      assertTrue(result.diagnostics().get(0).contains("restored"));
      assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), target.getBlockState(position(-16)));
      assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), target.getBlockState(position(0)));
      for (int y = -15; y <= 7; y++) {
         if (y != 0) assertEquals(Blocks.AIR.defaultBlockState(), target.getBlockState(position(y)));
      }
   }

   @Test void repeatedRollbackExceptionStillRestoresEveryEarlierRestorableCell() {
      TrackingChunk target = chunk();
      target.setBlockState(position(-16), Blocks.GOLD_BLOCK.defaultBlockState(), false);
      target.setBlockState(position(0), Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
      target.failAtZero = true;
      target.repeatRollbackFailure = true;
      IllegalStateException error = assertThrows(IllegalStateException.class, () ->
         new TellusMinecraft1201CanonicalColumnAdapter().write(target, CONTEXT, decision(COORDINATE)));
      assertTrue(error.getMessage().contains("rollback incomplete"));
      assertEquals(1, error.getSuppressed().length);
      assertSame(target.cachedRollbackFailure, error.getSuppressed()[0]);
      assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), target.getBlockState(position(-16)));
      assertEquals(Blocks.AIR.defaultBlockState(), target.getBlockState(position(-2)));
   }

   @Test void rejectsWrongChunkAndOutOfHeightBeforeAnyMutation() {
      TrackingChunk wrong = new TrackingChunk(new ChunkPos(0, 0), LevelHeightAccessor.create(-16, 48));
      assertEquals(WriteStatus.NO_WRITE_INVALID, new TellusMinecraft1201CanonicalColumnAdapter()
         .write(wrong, CONTEXT, decision(COORDINATE)).status());
      assertEquals(0, wrong.mutations);
      TrackingChunk shortTarget = new TrackingChunk(chunkPosition(), LevelHeightAccessor.create(0, 16));
      assertEquals(WriteStatus.NO_WRITE_INVALID, new TellusMinecraft1201CanonicalColumnAdapter()
         .write(shortTarget, CONTEXT, decision(COORDINATE)).status());
      assertEquals(0, shortTarget.mutations);
   }

   @Test void sphericalWriterUsesCenteredMinecraftChunk() {
      long offset = MODEL.chunksPerFace() / 2L * 16L;
      var sphere = TellusCoordinateContextFactory.fromSphericalBlock(MODEL,
         new SphereWorldModel.SphereBlockAddress(0, offset, offset));
      var context = WriteContext.canonical(sphere, GENERATION, "sphere-column");
      TrackingChunk target = new TrackingChunk(new ChunkPos(0, 0), LevelHeightAccessor.create(-16, 48));
      var result = new TellusMinecraft1201CanonicalColumnAdapter().write(target, context, decision(sphere));
      assertEquals(WriteStatus.WRITTEN, result.status());
      assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), target.getBlockState(new BlockPos(0, 4, 0)));
   }

   private static ColumnDecision decision(TellusCoordinateContext coordinate) {
      return new ColumnDecision(coordinate, DecisionStatus.AVAILABLE, OptionalInt.of(4),
         new SurfaceDecision(SemanticMaterial.GRASS, SemanticMaterial.DIRT, 2),
         new WaterDecision(WaterDecision.Kind.INLAND, OptionalInt.of(7)), BiomeDecision.defaultBiome(),
         new StrataDecision(List.of(new StrataDecision.Layer(-16, -16, SemanticMaterial.BEDROCK),
            new StrataDecision.Layer(-15, 0, SemanticMaterial.DEEPSLATE),
            new StrataDecision.Layer(1, 4, SemanticMaterial.STONE)), true), List.of());
   }
   private static BlockPos position(int y) { return new BlockPos(POSITION.x(), y, POSITION.z()); }
   private static ChunkPos chunkPosition() {
      return new ChunkPos(Math.floorDiv(POSITION.x(), 16), Math.floorDiv(POSITION.z(), 16));
   }
   private static TrackingChunk chunk() { return new TrackingChunk(chunkPosition(), LevelHeightAccessor.create(-16, 48)); }
   private static class TrackingChunk extends ProtoChunk {
      int mutations;
      boolean failAtZero;
      boolean repeatRollbackFailure;
      boolean rollingBack;
      final RuntimeException cachedRollbackFailure = new IllegalStateException("cached rollback failure");
      TrackingChunk(ChunkPos position, LevelHeightAccessor height) {
         super(position, UpgradeData.EMPTY, height, biomes, null);
      }
      @Override public BlockState setBlockState(BlockPos position, BlockState state, boolean moved) {
         if (rollingBack && repeatRollbackFailure && (position.getY() == 0 || position.getY() == -1)) {
            throw cachedRollbackFailure;
         }
         mutations++;
         BlockState old = super.setBlockState(position, state, moved);
         if (failAtZero && position.getY() == 0) {
            failAtZero = false;
            rollingBack = true;
            throw new IllegalStateException("sink throws after writing");
         }
         return old;
      }
   }
}
