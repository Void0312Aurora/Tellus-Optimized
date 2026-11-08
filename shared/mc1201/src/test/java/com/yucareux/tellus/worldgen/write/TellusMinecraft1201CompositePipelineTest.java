package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import java.util.List;
import java.util.Map;
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

class TellusMinecraft1201CompositePipelineTest {
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1000.0);
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(42, 1000, 0, -16, 32);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromCubeNetCanonical(
      MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
      new CubeNetLayout.FacePoint(CubeNetLayout.Face.NEG_X, 16.5, 32.5));
   private static final WriteContext CONTEXT = WriteContext.canonical(COORDINATE, GENERATION, "composite-fixture");
   private static final CanonicalColumnPosition BASE = CanonicalColumnPosition.resolve(CONTEXT);
   private static Registry<Biome> biomes;

   @BeforeAll static void bootstrap() {
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      MappedRegistry<Biome> mapped = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
      mapped.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
         .getOrThrow(Biomes.PLAINS).value(), Lifecycle.stable());
      biomes = mapped.freeze();
   }

   @Test void enabledPipelineWritesTerrainAndFeaturesTogether() {
      TrackingChunk chunk = chunk();
      WriteResult<BlockState> result = pipeline(features(), new TellusMinecraft1201SemanticMapper())
         .write(chunk, CONTEXT, GENERATION, COORDINATE, request());
      assertEquals(WriteStatus.WRITTEN, result.status());
      assertEquals(24, result.mappedWrites().size());
      assertTrue(result.persistenceIdentity().isPresent());
      assertEquals(Blocks.STONE.defaultBlockState(), chunk.getBlockState(position(4)));
      assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), chunk.getBlockState(position(5)));
      assertEquals(Blocks.DIRT.defaultBlockState(), chunk.getBlockState(position(6)));
      assertEquals(Blocks.DIRT.defaultBlockState(), chunk.getBlockState(position(7)));
      assertEquals(Blocks.AIR.defaultBlockState(), chunk.getBlockState(position(8)));
      assertTrue(chunk.isUnsaved());
   }

   @Test void lateFeatureMapperFailureLeavesTerrainAndFeaturesUntouched() {
      TrackingChunk chunk = chunk();
      AtomicInteger maps = new AtomicInteger();
      WriteResult<BlockState> result = pipeline(features(), (material, y, context) -> {
         maps.incrementAndGet();
         if (y == 7) throw new IllegalStateException("last feature mapping failed");
         return Blocks.STONE.defaultBlockState();
      }).write(chunk, CONTEXT, GENERATION, COORDINATE, request());
      assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
      assertEquals(24, maps.get());
      assertEquals(0, chunk.mutations);
      assertTrue(result.mappedWrites().isEmpty());
      assertEquals(Blocks.AIR.defaultBlockState(), chunk.getBlockState(position(-16)));
   }

   @Test void throwingFeatureWriteRestoresTerrainAndEarlierFeatures() {
      TrackingChunk chunk = chunk();
      chunk.setBlockState(position(-16), Blocks.GOLD_BLOCK.defaultBlockState(), false);
      chunk.setBlockState(position(6), Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
      chunk.failY = 7;
      WriteResult<BlockState> result = pipeline(features(), new TellusMinecraft1201SemanticMapper())
         .write(chunk, CONTEXT, GENERATION, COORDINATE, request());
      assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
      assertTrue(result.diagnostics().get(0).contains("terrain/features were restored"));
      assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), chunk.getBlockState(position(-16)));
      assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), chunk.getBlockState(position(6)));
      for (int y = -15; y <= 7; y++) {
         if (y != 6) assertEquals(Blocks.AIR.defaultBlockState(), chunk.getBlockState(position(y)));
      }
   }

   @Test void lastFeatureOutsideChunkStopsBeforeMappingOrAnyWrite() {
      FeaturePlacement outside = placement("other-chunk", 16, 8, 1, PlacementOperation.SET,
         PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.DIRT);
      FeaturePlan features = new FeaturePlan(FeaturePlanStatus.AVAILABLE,
         List.of(features().placements().get(0), outside), List.of());
      TrackingChunk chunk = chunk();
      AtomicInteger maps = new AtomicInteger();
      var result = pipeline(features, (material, y, context) -> { maps.incrementAndGet();
         return Blocks.STONE.defaultBlockState(); }).write(chunk, CONTEXT, GENERATION, COORDINATE, request());
      assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
      assertEquals(0, maps.get());
      assertEquals(0, chunk.mutations);
   }

   @Test void replaceAirAndPreserveExistingInspectPlannedTerrain() {
      FeaturePlan features = new FeaturePlan(FeaturePlanStatus.AVAILABLE, List.of(
         placement("air-only", 0, 4, 1, PlacementOperation.REPLACE_AIR, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS),
         placement("preserve-rock", 0, 3, 1, PlacementOperation.SET, PlacementConflictPolicy.PRESERVE_EXISTING, SemanticMaterial.DIRT),
         placement("place-in-air", 0, 5, 1, PlacementOperation.REPLACE_AIR, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS)
      ), List.of());
      TrackingChunk chunk = chunk();
      var result = pipeline(features, new TellusMinecraft1201SemanticMapper())
         .write(chunk, CONTEXT, GENERATION, COORDINATE, request());
      assertEquals(WriteStatus.WRITTEN, result.status());
      assertEquals(22, result.mappedWrites().size());
      assertEquals(Blocks.STONE.defaultBlockState(), chunk.getBlockState(position(3)));
      assertEquals(Blocks.STONE.defaultBlockState(), chunk.getBlockState(position(4)));
      assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), chunk.getBlockState(position(5)));
   }

   @Test void mismatchedGenerationIsRejectedBeforeProvider() {
      AtomicInteger reads = new AtomicInteger();
      TellusSampleProvider provider = new TellusSampleProvider() {
         public String providerId() { return "not-called"; }
         public TellusSample sample(TellusSampleRequest request) { reads.incrementAndGet();
            throw new AssertionError("provider must not run"); }
      };
      var pipeline = new TellusMinecraft1201CanonicalPipeline(true, provider, new TellusColumnDecisionEngine(),
         TellusFeaturePlanner.empty(), new TellusColumnMaterializer(), new TellusMinecraft1201SemanticMapper());
      var other = new TellusGenerationContext(99, 1000, 0, -16, 32);
      assertEquals(WriteStatus.NO_WRITE_INVALID, pipeline.write(null, CONTEXT, other, COORDINATE, request()).status());
      assertEquals(0, reads.get());
   }

   private static TellusMinecraft1201CanonicalPipeline pipeline(FeaturePlan features, SemanticMapper<BlockState> mapper) {
      TellusSampleProvider provider = new TellusSampleProvider() {
         public String providerId() { return "composite-fixture"; }
         public TellusSample sample(TellusSampleRequest request) {
            return new TellusSample(request, SampleStatus.AVAILABLE,
               new TellusElevationSample(SampleStatus.AVAILABLE, 4000),
               new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30), TellusWaterSample.dry(),
               TellusSampleProvenance.snapshot(providerId(), "v1", false, "fixture"));
         }
      };
      return new TellusMinecraft1201CanonicalPipeline(true, provider, new TellusColumnDecisionEngine(),
         (generation, coordinate, decision, sample) -> features, new TellusColumnMaterializer(), mapper);
   }
   private static FeaturePlan features() {
      return new FeaturePlan(FeaturePlanStatus.AVAILABLE, List.of(
         placement("turf", 0, 5, 1, PlacementOperation.SET, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS),
         placement("stem", 0, 6, 2, PlacementOperation.FILL, PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.DIRT)), List.of());
   }
   private static FeaturePlacement placement(String id, int x, int y, int height, PlacementOperation operation,
                                              PlacementConflictPolicy policy, SemanticMaterial material) {
      return FeaturePlacement.canonical(FeatureKind.VEGETATION, id, COORDINATE, operation, policy, material,
         x, y, 0, height, Map.of());
   }
   private static TellusSampleRequest request() {
      return TellusSampleRequest.fromContext(COORDINATE, SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.exact(1000), GENERATION.geometry(), GENERATION.worldSeed());
   }
   private static BlockPos position(int y) { return new BlockPos(BASE.x(), y, BASE.z()); }
   private static TrackingChunk chunk() { return new TrackingChunk(); }
   private static final class TrackingChunk extends ProtoChunk {
      int mutations;
      int failY = Integer.MIN_VALUE;
      TrackingChunk() {
         super(new ChunkPos(Math.floorDiv(BASE.x(), 16), Math.floorDiv(BASE.z(), 16)), UpgradeData.EMPTY,
            LevelHeightAccessor.create(-16, 48), biomes, null);
      }
      @Override public BlockState setBlockState(BlockPos position, BlockState state, boolean moved) {
         mutations++;
         BlockState previous = super.setBlockState(position, state, moved);
         if (position.getY() == failY) { failY = Integer.MIN_VALUE;
            throw new IllegalStateException("feature sink threw after changing cell"); }
         return previous;
      }
   }
}
