package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.world.data.geology.*;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TellusMinecraft1201SphericalKernelParityTest {
   private static Registry<Biome> biomes;
   @BeforeAll static void bootstrap() {
      System.setProperty("tellus.overture.release", "2026-06-17.0");
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      MappedRegistry<Biome> mapped = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
      mapped.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
         .getOrThrow(Biomes.PLAINS).value(), Lifecycle.stable());
      biomes = mapped.freeze();
   }

   @Test void completeProductionKernelPipelineMatchesNativeForAllOwnersAndGeology() {
      double scale = 10.0;
      long seed = 42;
      SphereWorldModel model = SphereWorldModel.forWorldScale(scale);
      long offset = model.chunksPerFace() / 2L * 16L;
      List<TellusCoordinateContext> coordinates = new ArrayList<>();
      coordinates.add(TellusCoordinateContextFactory.fromSphericalBlock(model,
         new SphereWorldModel.SphereBlockAddress(0, offset + 5, offset + 7)));
      CubeNetLayout layout = CubeNetLayout.standard(model.config().faceBlocks());
      for (var face : CubeNetLayout.Face.values()) {
         coordinates.add(TellusCoordinateContextFactory.fromCubeNetCanonical(model, layout,
            new CubeNetLayout.FacePoint(face, offset + 5.5, offset + 7.5)));
      }
      SphereChunkSampler.SurfaceData.GeologyData[] geologies = {
         null,
         new SphereChunkSampler.SurfaceData.GeologyData(Lithology.ACID_PLUTONIC, null),
         new SphereChunkSampler.SurfaceData.GeologyData(Lithology.SILICICLASTIC_SEDIMENTARY, null),
         new SphereChunkSampler.SurfaceData.GeologyData(Lithology.BASIC_VOLCANIC,
            new MapUnit(Rock.SANDSTONE, Rock.SHALE, 35))
      };
      int columns = 0;
      for (var coordinate : coordinates) {
         for (int cover : new int[]{10, 30, 60, 70, 80, 90, 95}) {
            for (var geology : geologies) {
               for (double metres : new double[]{20.1, -15.1}) {
                  for (int waterMode : new int[]{0, 1, 2}) {
                     boolean enableWater = waterMode != 1;
                     var data = new SphereChunkSampler.SurfaceData(metres, cover,
                        waterMode == 0 ? Double.NaN : metres + 8.2,
                        waterMode == 0 ? SphereChunkSampler.SurfaceData.WaterKind.NONE
                           : SphereChunkSampler.SurfaceData.WaterKind.INLAND, geology);
                     TellusGenerationContext generation = new TellusGenerationContext(seed, scale, 64, -64, 320);
                     var request = TellusSampleRequest.fromContext(coordinate, SamplingPurpose.TERRAIN_COLUMN,
                        SamplingResolution.exact(scale), generation.geometry(), seed);
                     var sampler = new SphereChunkSampler(model, (point, resolution) -> data);
                     TellusSampleProvider provider = coordinate.owner() instanceof SphericalOwnerView
                        ? TellusSphericalSampleProvider.forSphereFaceProduction(sampler)
                        : TellusSphericalSampleProvider.forCubeNetProduction(sampler);
                     var engine = TellusColumnDecisionEngine.withSphericalProductionRules(
                        new TellusSphericalDecisionKernel.Settings(enableWater, 1.7, 2.5));
                     var pipeline = new TellusMinecraft1201CanonicalPipeline(true, provider, engine,
                        TellusFeaturePlanner.empty(), new TellusColumnMaterializer(), new TellusMinecraft1201SemanticMapper());
                     WriteContext context = WriteContext.canonical(coordinate, generation, "production-kernel-parity");
                     var pos = CanonicalColumnPosition.resolve(context);
                     ProtoChunk chunk = new ProtoChunk(new ChunkPos(Math.floorDiv(pos.x(), 16), Math.floorDiv(pos.z(), 16)),
                        UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), biomes, null);
                     var result = pipeline.write(chunk, context, generation, coordinate, request);
                     assertEquals(WriteStatus.WRITTEN, result.status());
                     var address = coordinate.owner() instanceof SphericalOwnerView sphere ? sphere.block()
                        : ((CubeNetCanonicalOwnerView)coordinate.owner()).block();
                     var nativePlan = new SphereTerrainMaterializer(scale, 64, enableWater, 1.7, 2.5, seed)
                        .plan(new SphereColumnContext(address, coordinate.geo(), data), -64, 320);
                     for (int y = -64; y < 320; y++) {
                        assertEquals(nativePlan.stateAt(y, -64), chunk.getBlockState(new BlockPos(pos.x(), y, pos.z())),
                           "owner=" + coordinate.owner().kind() + " cover=" + cover + " metres=" + metres + " y=" + y);
                     }
                     columns++;
                  }
               }
            }
         }
      }
      assertEquals(1176, columns);
      System.out.println("SPHERICAL_KERNEL_PARITY columns=" + columns + " mismatchBlocks=0");
   }

   @Test void allNativeStrataIndicesMapToTheSameBlockStates() {
      var mapper = new TellusMinecraft1201SemanticMapper();
      for (int material = 0; material < StrataPolicy.MATERIAL_COUNT; material++) {
         assertEquals(StrataMaterials.state(material), mapper.map(TellusSphericalColumnRules.material(material), 0, null));
      }
      assertThrows(IllegalArgumentException.class, () -> TellusSphericalColumnRules.material(999));
   }
}
