package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class CubeNetChunkGeneratorTest {
   @TempDir static Path gameDir;
   private static final String[] PROPERTY_NAMES = {"tellus.gameDir", "tellus.configDir", "tellus.overture.release"};
   private static final String[] previousProperties = new String[3];
   private static Registry<Biome> registry;
   private static Holder<Biome> plains;

   @BeforeAll
   static void bootstrap() {
      for (int i = 0; i < 3; i++) previousProperties[i] = System.getProperty(PROPERTY_NAMES[i]);
      System.setProperty("tellus.gameDir", gameDir.toString());
      System.setProperty("tellus.configDir", gameDir.resolve("config").toString());
      System.setProperty("tellus.overture.release", "2026-06-17.0");
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      var mapped = new MappedRegistry<Biome>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
      plains = mapped.register(Biomes.PLAINS,
         VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
         com.mojang.serialization.Lifecycle.stable());
      registry = mapped.freeze();
   }

   @AfterAll
   static void restoreProperties() {
      for (int i = 0; i < 3; i++) {
         if (previousProperties[i] == null) System.clearProperty(PROPERTY_NAMES[i]);
         else System.setProperty(PROPERTY_NAMES[i], previousProperties[i]);
      }
   }

   @Test
   void resolvesAndGeneratesAnInteriorPlanarChunkThroughItsCanonicalFace() {
      AtomicInteger calls = new AtomicInteger();
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1.0, 64, -64, 384,
         (point, resolution) -> {
            calls.incrementAndGet();
            return new SphereChunkSampler.SurfaceData(10.0, 30);
         });
      int faceChunks = Math.toIntExact(generator.model().chunksPerFace());
      ChunkPos planar = new ChunkPos(faceChunks / 2, -faceChunks + faceChunks / 2);
      var address = generator.address(planar).orElseThrow();
      assertEquals(2, address.face());
      var chunk = new ProtoChunk(planar, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), registry, null);
      chunk.setStatus(ChunkStatus.BIOMES);
      assertSame(chunk, generator.fillFromNoise(Runnable::run, null, null, null, chunk).join());
      assertEquals(256, calls.get());
      assertTrue(chunk.getBlockState(new net.minecraft.core.BlockPos(planar.getMinBlockX(), 10,
         planar.getMinBlockZ())).is(Blocks.STONE));
      assertEquals(75, generator.getBaseHeight(planar.getMinBlockX(), planar.getMinBlockZ(),
         net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, chunk, null));
   }

   @Test
   void materializedColumnsFollowThePlanarOrientationOnEveryFace() {
      for (var face : CubeNetLayout.Face.values()) {
         AtomicInteger calls = new AtomicInteger();
         var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1000.0, 64, -64, 384,
            (point, resolution) -> new SphereChunkSampler.SurfaceData(calls.getAndIncrement() * 1000.0, 30));
         var layout = generator.adapter().layout();
         var center = layout.toPlanar(new CubeNetLayout.FacePoint(face,
            layout.faceSize() / 2.0 + 0.5, layout.faceSize() / 2.0 + 0.5));
         var pos = new ChunkPos((int)Math.floor(center.x() / 16), (int)Math.floor(center.z() / 16));
         var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), registry, null);
         chunk.setStatus(ChunkStatus.BIOMES);
         generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();
         for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int gx = pos.getMinBlockX() + x, gz = pos.getMinBlockZ() + z;
            var local = layout.fromPlanar(new CubeNetLayout.PlanarPoint(gx + 0.5, gz + 0.5));
            int top = 64 + ((int)Math.floor(local.v()) & 15) * 16 + ((int)Math.floor(local.u()) & 15);
            String label = face + " column " + x + "," + z;
            assertTrue(chunk.getBlockState(new net.minecraft.core.BlockPos(gx, top, gz)).is(Blocks.GRASS_BLOCK), label);
            assertTrue(chunk.getBlockState(new net.minecraft.core.BlockPos(gx, top + 1, gz)).isAir(), label);
            assertEquals(top + 1, generator.getBaseHeight(gx, gz,
               net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, chunk, null), label);
            assertTrue(generator.getBaseColumn(gx, gz, chunk, null).getBlock(top).is(Blocks.GRASS_BLOCK), label);
         }
         assertEquals(256, calls.get(), face.toString());
      }
   }

   @Test
   void leavesNetGapsOutsideTheOrdinaryChunkGeneratorPath() {
      AtomicInteger calls = new AtomicInteger();
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1.0, 64, -64, 384,
         (point, resolution) -> {
            calls.incrementAndGet();
            return new SphereChunkSampler.SurfaceData(10.0, 30);
         });
      int faceChunks = Math.toIntExact(generator.model().chunksPerFace());
      ChunkPos gap = new ChunkPos(2 * faceChunks + 1, 0);
      assertTrue(generator.address(gap).isEmpty());
      var chunk = new ProtoChunk(gap, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), registry, null);
      generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();
      assertEquals(0, calls.get());
      assertEquals(-64, generator.getBaseHeight(gap.getMinBlockX(), gap.getMinBlockZ(),
         net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, chunk, null));
   }

   @Test
   void codecPreservesTheSingleLevelCubeNetContract() {
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1.0, 100, -128, 512);
      var ops = RegistryOps.create(JsonOps.INSTANCE,
         HolderLookup.Provider.create(java.util.stream.Stream.of(registry.asLookup())));
      JsonObject encoded = CubeNetChunkGenerator.CODEC.encodeStart(ops, generator)
         .getOrThrow(false, fail -> fail(fail)).getAsJsonObject();
      var restored = CubeNetChunkGenerator.CODEC.parse(ops, encoded).getOrThrow(false, fail -> fail(fail));
      assertEquals(1.0, restored.worldScale());
      assertEquals(100, restored.getSeaLevel());
      assertEquals(-128, restored.getMinY());
      assertEquals(512, restored.getGenDepth());
      assertEquals(generator.model().config(), restored.model().config());
      encoded.addProperty("geometry_version", 1);
      assertTrue(CubeNetChunkGenerator.CODEC.parse(ops, encoded).error().isPresent());
   }
}
