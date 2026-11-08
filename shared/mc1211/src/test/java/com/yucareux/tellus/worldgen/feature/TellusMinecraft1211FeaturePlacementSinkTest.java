package com.yucareux.tellus.worldgen.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.write.TellusMinecraft1211SemanticMapper;
import com.yucareux.tellus.worldgen.write.WriteContext;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
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

/** Focused 1.21.1 feature sink status/owner/conflict and one-span mapping tests. */
class TellusMinecraft1211FeaturePlacementSinkTest {
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
         RegistrationInfo.BUILT_IN);
      biomes = mapped.freeze();
      double scale = 1.0;
      SphereWorldModel model = SphereWorldModel.forWorldScale(scale);
      coordinate = TellusCoordinateContextFactory.fromCubeNetCanonical(
         model, CubeNetLayout.standard(model.config().faceBlocks()),
         new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));
      generation = new TellusGenerationContext(5L, scale, 64, -64, 320,
         new GeometryIdentity("tellus:mc1211-feature", "v1", "projection", "noise", scale));
   }

   @Test
   void unknownQueuedOwnerAndConflictReturnWithoutTarget() {
      WriteContext context = WriteContext.canonical(coordinate, generation, "feature-test");
      TellusMinecraft1211FeaturePlacementSink sink = new TellusMinecraft1211FeaturePlacementSink(null);
      assertEquals(FeaturePlacementStatus.NO_WRITE_UNKNOWN,
         sink.place(context, FeaturePlan.unknown("unknown"), new TellusMinecraft1211SemanticMapper()).status());
      assertEquals(FeaturePlacementStatus.NO_WRITE_QUEUED,
         sink.place(context, FeaturePlan.queued("queued"), new TellusMinecraft1211SemanticMapper()).status());
      FeaturePlacement conflict = new FeaturePlacement(
         FeatureKind.VEGETATION, "bad", context.targetOwner(), context.targetOwner(),
         PlacementOperation.SET, PlacementConflictPolicy.MIRROR_OWNER,
         SemanticMaterial.GRASS, 0, 80, 0, 1, false, Map.of());
      assertEquals(FeaturePlacementStatus.NO_WRITE_CONFLICT,
         sink.place(context, FeaturePlan.available(conflict), new TellusMinecraft1211SemanticMapper()).status());
   }

   @Test
   void knownCanonicalPlacementMapsOneSpan() {
      WriteContext context = WriteContext.canonical(coordinate, generation, "feature-known");
      FeaturePlacement placement = FeaturePlacement.canonical(
         FeatureKind.VEGETATION, "grass", coordinate, PlacementOperation.SET,
         PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.GRASS, 0, 80, 0, 1, Map.of());
      ProtoChunk chunk = new ProtoChunk(new ChunkPos(2, 3), UpgradeData.EMPTY,
         LevelHeightAccessor.create(-64, 320), biomes, null);
      FeaturePlacementResult<BlockState> result = new TellusMinecraft1211FeaturePlacementSink(chunk)
         .place(context, FeaturePlan.available(placement), new TellusMinecraft1211SemanticMapper());
      assertEquals(FeaturePlacementStatus.WRITTEN, result.status());
      assertEquals(1, result.mappedWrites().size());
      assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), chunk.getBlockState(
         new net.minecraft.core.BlockPos(32, 80, 48)));
      assertTrue(result.mappedWrites().get(0).is(Blocks.GRASS_BLOCK));
   }
}
