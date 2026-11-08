package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.write.*;
import java.util.List;
import java.util.OptionalInt;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Compares actual adapter writes with the existing native materializer's independent stateAt. */
class TellusMinecraft1201MaterializerParityTest {
   private static Registry<Biome> biomes;
   @BeforeAll static void bootstrap() {
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      MappedRegistry<Biome> mapped = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
      mapped.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
         .getOrThrow(Biomes.PLAINS).value(), Lifecycle.stable());
      biomes = mapped.freeze();
   }

   @Test void expandedWriterMatchesNativeStrataSurfaceBedrockAndWaterSemantics() {
      SphereWorldModel model = SphereWorldModel.forWorldScale(1000.0);
      long offset = model.chunksPerFace() / 2L * 16L;
      var coordinate = TellusCoordinateContextFactory.fromSphericalBlock(model,
         new SphereWorldModel.SphereBlockAddress(0, offset, offset));
      var generation = new TellusGenerationContext(1, 1000, 0, -16, 64);
      var context = WriteContext.canonical(coordinate, generation, "native-materializer-parity");
      var mapper = new TellusMinecraft1201SemanticMapper();
      var nativeMaterializer = new SphereTerrainMaterializer(1000, 0);
      int columns = 0;
      for (int cover : new int[]{10, 30, 40, 60, 70, 80, 90, 95}) {
         var base = nativeMaterializer.plan(new SphereChunkSampler.SurfaceData(4000, cover, 7000), -16, 64);
         for (int depth : new int[]{0, 1, 3, 100}) {
            for (int waterY : new int[]{3, 4, 5, 7}) {
               var nativePlan = new SphereTerrainMaterializer.ColumnPlan(base.surfaceY(), waterY,
                  base.top(), base.filler(), depth, null);
               var semantic = new ColumnDecision(coordinate, DecisionStatus.AVAILABLE,
                  OptionalInt.of(nativePlan.surfaceY()),
                  new SurfaceDecision(material(base.top(), mapper), material(base.filler(), mapper), depth),
                  new WaterDecision(WaterDecision.Kind.INLAND, OptionalInt.of(waterY)), BiomeDecision.defaultBiome(),
                  new StrataDecision(List.of(new StrataDecision.Layer(-16, -16, SemanticMaterial.BEDROCK),
                     new StrataDecision.Layer(-15, nativePlan.surfaceY(), SemanticMaterial.STONE)), true), List.of());
               ProtoChunk target = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY,
                  LevelHeightAccessor.create(-16, 80), biomes, null);
               var result = new TellusMinecraft1201ColumnWriter(target).write(context, semantic, mapper);
               assertEquals(WriteStatus.WRITTEN, result.status());
               for (int y = -16; y < 64; y++) {
                  assertEquals(nativePlan.stateAt(y, -16), target.getBlockState(new BlockPos(0, y, 0)),
                     "cover=" + cover + " depth=" + depth + " water=" + waterY + " y=" + y);
               }
               columns++;
            }
         }
      }
      assertEquals(128, columns);
      System.out.println("SEMANTIC_MATERIALIZER_PARITY columns=" + columns + " mismatchBlocks=0");
   }

   private static SemanticMaterial material(BlockState state, SemanticMapper<BlockState> mapper) {
      for (SemanticMaterial material : SemanticMaterial.values()) {
         if (material != SemanticMaterial.UNKNOWN && mapper.map(material, 0, null).equals(state)) return material;
      }
      throw new IllegalArgumentException("fixture material has no semantic mapping: " + state);
   }
}
