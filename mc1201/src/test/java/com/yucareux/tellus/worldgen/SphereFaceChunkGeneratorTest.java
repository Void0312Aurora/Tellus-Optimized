package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SphereFaceChunkGeneratorTest {
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
      var mapped = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
      plains = mapped.register(Biomes.PLAINS,
         VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), Lifecycle.stable());
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
   void fillsRealProtoChunksOnAllSixFacesAndKeepsNoiseQueriesConsistent() {
      for (int face = 0; face < 6; face++) {
         AtomicInteger calls = new AtomicInteger();
         var generator = generator(face, (point, resolution) -> {
            calls.incrementAndGet();
            // Analytic radial heights, not real-world scene quality evidence.
            double height = 10.1 + Math.sin(Math.toRadians(point.latitude())) * 5.0;
            return new SphereChunkSampler.SurfaceData(height, 30);
         });
         var chunk = chunk(new ChunkPos(-1, 2));
         assertSame(chunk, generator.createBiomes(Runnable::run, null, null, null, chunk).join());
         chunk.setStatus(ChunkStatus.BIOMES); // The engine advances status after the biome task.
         assertSame(chunk, generator.fillFromNoise(Runnable::run, null, null, null, chunk).join());
         assertEquals(256, calls.get(), "generation samples only the requested chunk");
         assertTrue(chunk.isUnsaved());
         for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
               int wx = chunk.getPos().getMinBlockX() + x, wz = chunk.getPos().getMinBlockZ() + z;
               var base = generator.getBaseColumn(wx, wz, chunk, null);
               for (int y = -64; y < 320; y++) assertSame(base.getBlock(y), chunk.getBlockState(new BlockPos(wx, y, wz)));
               for (var type : Heightmap.Types.values()) {
                  assertEquals(generator.getBaseHeight(wx, wz, type, chunk, null) - 1, chunk.getHeight(type, x, z));
               }
               assertTrue(chunk.getBlockState(new BlockPos(wx, -64, wz)).is(Blocks.BEDROCK));
               assertSame(plains, chunk.getNoiseBiome(Math.floorDiv(wx, 4), 0, Math.floorDiv(wz, 4)));
            }
         }
         assertEquals(256, calls.get(), "height and column queries share the same immutable terrain grid");
         for (var section : chunk.getSections()) {
            section.acquire();
            section.release(); // Every palette lock was released by materialization.
         }
      }
   }

   @Test
   void explicitWaterLevelSeparatesOceanFloorAndWorldSurfaceHeightmaps() {
      var generator = generator(0, (point, resolution) -> new SphereChunkSampler.SurfaceData(-8.2, 80, 0.0));
      var chunk = chunk(new ChunkPos(0, 0));
      generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();
      assertEquals(55, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, 0, 0));
      assertEquals(64, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 0, 0));
      assertTrue(chunk.getBlockState(new BlockPos(0, 55, 0)).is(Blocks.SAND));
      assertTrue(chunk.getBlockState(new BlockPos(0, 64, 0)).is(Blocks.WATER));
      assertTrue(chunk.getBlockState(new BlockPos(0, 65, 0)).isAir());
      var inland = generator(0, (point, resolution) -> new SphereChunkSampler.SurfaceData(-8.2, 80));
      var inlandChunk = chunk(new ChunkPos(0, 0));
      inland.fillFromNoise(Runnable::run, null, null, null, inlandChunk).join();
      assertEquals(55, inlandChunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 0, 0),
         "cover=80 alone must not invent sea-level water");
   }

   @Test
   void missingAndOutOfRangeColumnsFailBeforeAnyBlocksAreWritten() {
      for (double invalid : new double[]{Double.NaN, 10_000.0, -10_000.0}) {
         AtomicInteger calls = new AtomicInteger();
         var generator = generator(0, (point, resolution) ->
            new SphereChunkSampler.SurfaceData(calls.incrementAndGet() == 256 ? invalid : 10.0, 30));
         var chunk = chunk(new ChunkPos(0, 0));
         assertThrows(CompletionException.class, () -> generator.fillFromNoise(Runnable::run, null, null, null, chunk).join());
         assertEquals(256, calls.get());
         for (var section : chunk.getSections()) assertTrue(section.hasOnlyAir());
      }
   }

   @Test
   void missingGridIsRetriedWhenItsDataBecomesAvailable() {
      AtomicInteger calls = new AtomicInteger();
      var generator = generator(0, (point, resolution) ->
         new SphereChunkSampler.SurfaceData(calls.incrementAndGet() <= 256 ? Double.NaN : 10.0, 30));
      var chunk = chunk(new ChunkPos(0, 0));
      assertThrows(CompletionException.class, () -> generator.fillFromNoise(Runnable::run, null, null, null, chunk).join());
      for (var section : chunk.getSections()) assertTrue(section.hasOnlyAir());
      generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();
      assertEquals(512, calls.get());
      assertEquals(74, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, 0, 0));
   }

   @Test
   void faceBoundaryDoesNotWrapOrQueryAnUnrelatedGeographicRegion() {
      AtomicInteger calls = new AtomicInteger();
      var generator = generator(0, (point, resolution) -> {
         calls.incrementAndGet();
         return new SphereChunkSampler.SurfaceData(10.0, 30);
      });
      int edge = (int)(generator.model().chunksPerFace() / 2);
      var outside = chunk(new ChunkPos(edge, 0));
      generator.fillFromNoise(Runnable::run, null, null, null, outside).join();
      assertEquals(-64, generator.getBaseHeight(edge * 16, 0, Heightmap.Types.WORLD_SURFACE_WG, outside, null));
      assertEquals(0, calls.get());
      assertThrows(IllegalArgumentException.class, () -> generator.address(outside.getPos()));
      for (var section : outside.getSections()) assertTrue(section.hasOnlyAir());
   }

   @Test
   void codecPreservesFaceScaleAndVerticalConfigurationAndRejectsUnknownGeometry() {
      var generator = new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 5, 1.0, 100, -128, 512, "tellus:test_earth");
      var ops = RegistryOps.create(JsonOps.INSTANCE, HolderLookup.Provider.create(java.util.stream.Stream.of(registry.asLookup())));
      JsonObject encoded = SphereFaceChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow(false, fail -> fail(fail)).getAsJsonObject();
      var restored = SphereFaceChunkGenerator.CODEC.parse(ops, encoded).getOrThrow(false, fail -> fail(fail));
      assertEquals(5, restored.face());
      assertEquals(1.0, restored.worldScale());
      assertEquals(-128, restored.getMinY());
      assertEquals(512, restored.getGenDepth());
      assertEquals(100, restored.getSeaLevel());
      assertEquals("tellus:test_earth", restored.planet());
      encoded.remove("planet");
      assertEquals("", SphereFaceChunkGenerator.CODEC.parse(ops, encoded).getOrThrow(false, fail -> fail(fail)).planet());
      assertEquals(generator.address(new ChunkPos(-1, 4)), restored.address(new ChunkPos(-1, 4)));
      encoded.addProperty("geometry_version", 2);
      assertTrue(SphereFaceChunkGenerator.CODEC.parse(ops, encoded).error().isPresent());
      assertThrows(IllegalArgumentException.class,
         () -> new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 0, 1.0, 64, -63, 384));
      assertThrows(IllegalArgumentException.class,
         () -> new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 0, 1.0, 64, -64, 384, "bad planet"));
      assertThrows(IllegalArgumentException.class,
         () -> new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 0, 1.0, 64, -64, 384, "unnamespaced"));
   }

   @Test
   void groupedFacesMustShareScaleAndRadialHeightConfiguration() {
      var first = new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 0, 1, 64, -64, 384, "tellus:a");
      assertTrue(SphereFaceRuntime.sameGeometry(first,
         new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 1, 1, 64, -64, 384, "tellus:a")));
      for (var other : new SphereFaceChunkGenerator[]{
         new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 1, 30, 64, -64, 384, "tellus:a"),
         new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 1, 1, 65, -64, 384, "tellus:a"),
         new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 1, 1, 64, -128, 384, "tellus:a"),
         new SphereFaceChunkGenerator(new FixedBiomeSource(plains), 1, 1, 64, -64, 512, "tellus:a")}) {
         assertFalse(SphereFaceRuntime.sameGeometry(first, other));
      }
   }

   @Test
   void radialHeightDoesNotGainLatitudeDependentMagnification() {
      var transform = new SphereTerrainMaterializer(1.0, 64);
      assertEquals(165, transform.height(100.1));
      assertEquals(-37, transform.height(-100.1));
      assertEquals(64, transform.height(0.0));
      assertEquals(69, new SphereTerrainMaterializer(30.0, 64).height(150.0));
   }

   private static SphereFaceChunkGenerator generator(int face, SphereChunkSampler.PointSampler sampler) {
      return new SphereFaceChunkGenerator(new FixedBiomeSource(plains), face, 1.0, 64, -64, 384, sampler);
   }

   private static ProtoChunk chunk(ChunkPos pos) {
      return new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), registry, null);
   }
}
