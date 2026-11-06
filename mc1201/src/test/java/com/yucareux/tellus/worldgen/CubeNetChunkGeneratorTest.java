package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.levelgen.structure.StructureSet;
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
   void filteredStructureLookupExcludesDisabledStructureSets() {
       var provider = VanillaRegistries.createLookup();
       var structureSets = provider.lookupOrThrow(Registries.STRUCTURE_SET);
       var disabled = new TellusStructurePolicy.StructureToggles(
          false, false, false, false, false, false, false, false, false, false,
          false, false, false, false, false, false, false, false, false);
       var filtered = new FilteredStructureLookup(structureSets, new TellusStructurePolicy(disabled)::enabledSet);
       ResourceKey<StructureSet> villages = ResourceKey.create(Registries.STRUCTURE_SET,
          new net.minecraft.resources.ResourceLocation("minecraft", "villages"));
       assertTrue(structureSets.get(villages).isPresent());
       assertTrue(filtered.listElements().noneMatch(holder -> holder.key().equals(villages)));
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
   void geodeticSurfacePositionUsesCanonicalFaceAndRoundTripsCoordinates() {
       var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1.0, 64, -64, 384,
          (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 30));
       BlockPos target = generator.surfacePosition(LevelHeightAccessor.create(-64, 384), 0.0, 0.0).orElseThrow();
       assertEquals(75, target.getY());
       assertTrue(Math.abs(generator.latitudeFromBlock(target.getX(), target.getZ())) < 0.1);
       assertTrue(Math.abs(generator.longitudeFromBlock(target.getX(), target.getZ())) < 0.1);
       var projected = generator.adapter().layout().fromPlanar(
          new CubeNetLayout.PlanarPoint(target.getX() + 0.5, target.getZ() + 0.5));
       assertEquals(generator.model().blockForGeo(new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0)).face(), projected.face().index());
    }

   @Test
   void initialSpawnPositionUsesEarthDefaultGeodeticCoordinates() {
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1.0, 64, -64, 384,
         (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 30));
      BlockPos expected = generator.surfacePosition(LevelHeightAccessor.create(-64, 384),
         EarthGeneratorSettings.DEFAULT.spawnLatitude(), EarthGeneratorSettings.DEFAULT.spawnLongitude()).orElseThrow();
      assertEquals(expected, generator.getInitialSpawnPosition(LevelHeightAccessor.create(-64, 384)));
   }

   @Test
   void defaultGeneratorUsesTellusSphericalSourceFactoryForCanonicalColumns() {
      String previous = System.getProperty("tellus.sphere.syntheticSampler");
      try {
         // Keep this test independent of local DEM/land-mask availability while
         // still exercising the production constructor and lazy source path.
         System.setProperty("tellus.sphere.syntheticSampler", "true");
         var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1.0, 64, -64, 384);
         var model = generator.model();
         var canonical = model.blockForGeo(new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0));
         var planarCenter = generator.adapter().layout().toPlanar(new CubeNetLayout.FacePoint(
            CubeNetLayout.Face.of(canonical.face()), canonical.blockX() + 0.5, canonical.blockZ() + 0.5));
         ChunkPos planar = new ChunkPos((int)Math.floor(planarCenter.x() / 16.0),
            (int)Math.floor(planarCenter.z() / 16.0));
         var address = generator.address(planar).orElseThrow();
         assertEquals(canonical.face(), address.face());

         var chunk = new ProtoChunk(planar, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), registry, null);
         chunk.setStatus(ChunkStatus.BIOMES);
         generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();

         int blockX = (int)Math.floor(planarCenter.x());
         int blockZ = (int)Math.floor(planarCenter.z());
         var sampled = TellusWorldgenSources.sphericalSampler(model).samplePoint(
            model.surfacePoint(new SphereWorldModel.SphereBlockAddress(
               canonical.face(), canonical.blockX(), canonical.blockZ())));
         var materializer = new SphereTerrainMaterializer(1.0, 64);
         int expectedSurface = materializer.height(sampled.elevationMeters());
         assertEquals(expectedSurface + 1, generator.getBaseHeight(blockX, blockZ,
            net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, chunk, null));
         assertEquals(SphereTerrainMaterializer.palette(sampled.coverClass()).top(),
            chunk.getBlockState(new net.minecraft.core.BlockPos(blockX, expectedSurface, blockZ)));
      } finally {
         if (previous == null) System.clearProperty("tellus.sphere.syntheticSampler");
         else System.setProperty("tellus.sphere.syntheticSampler", previous);
      }
   }

   @Test
   void cubeNetBiomeSourceUsesCanonicalWaterAndCover() {
      var lookup = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
      var fallback = new FixedBiomeSource(lookup.getOrThrow(Biomes.PLAINS));
      var model = SphereWorldModel.forWorldScale(1000.0);
      var layout = CubeNetLayout.standard(model.config().faceBlocks());
      var center = layout.toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X,
         layout.faceSize() * 0.5, layout.faceSize() * 0.5));
      int quartX = Math.floorDiv((int)Math.floor(center.x()), 4);
      int quartZ = Math.floorDiv((int)Math.floor(center.z()), 4);

      var wet = new CubeNetBiomeSource(lookup, 1000.0, 5, SphereSingularityPolicy.DEFAULT_CONFIG,
         fallback, (point, resolution) -> new SphereChunkSampler.SurfaceData(-10.0, 80, 0.0),
         (point, resolution, water) -> new CubeNetBiomeSource.ClimateData(null, null));
      assertTrue(wet.getNoiseBiome(quartX, 0, quartZ, null).is(Biomes.OCEAN));

      var snowy = new CubeNetBiomeSource(lookup, 1000.0, 5, SphereSingularityPolicy.DEFAULT_CONFIG,
         fallback, (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 70),
         (point, resolution, water) -> new CubeNetBiomeSource.ClimateData("Cfb", null));
      assertTrue(snowy.getNoiseBiome(quartX, 0, quartZ, null).is(Biomes.FROZEN_PEAKS));

      var forest = new CubeNetBiomeSource(lookup, 1000.0, 5, SphereSingularityPolicy.DEFAULT_CONFIG,
         fallback, (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 10),
         (point, resolution, water) -> new CubeNetBiomeSource.ClimateData("Cfa", null));
      assertTrue(forest.getNoiseBiome(quartX, 0, quartZ, null).is(Biomes.DARK_FOREST));
   }

   @Test
   void cubeNetBiomeSourceQueriesInlandWaterInGeodeticSpaceOnlyForWaterLikeCover() {
      var lookup = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
      var fallback = new FixedBiomeSource(lookup.getOrThrow(Biomes.PLAINS));
      var model = SphereWorldModel.forWorldScale(1000.0);
      var layout = CubeNetLayout.standard(model.config().faceBlocks());
      var center = layout.toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X,
         layout.faceSize() * 0.5, layout.faceSize() * 0.5));
      int quartX = Math.floorDiv((int)Math.floor(center.x()), 4);
      int quartZ = Math.floorDiv((int)Math.floor(center.z()), 4);
      var seen = new java.util.ArrayList<SphereWorldModel.GeoPoint>();
      AtomicInteger waterCalls = new AtomicInteger();
      var source = new CubeNetBiomeSource(lookup, 1000.0, 5, SphereSingularityPolicy.DEFAULT_CONFIG,
         fallback, (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 80),
         (point, resolution, water) -> new CubeNetBiomeSource.ClimateData("Cfb", null),
         (point, scale) -> {
            waterCalls.incrementAndGet();
            seen.add(point);
            return new CubeNetBiomeSource.WaterData(true, false);
         });
      assertTrue(source.getNoiseBiome(quartX, 0, quartZ, null).is(Biomes.RIVER));
      assertEquals(1, waterCalls.get());
      assertEquals(1, seen.size());
      assertTrue(Math.abs(seen.get(0).longitude()) <= 180.0);
      assertTrue(Math.abs(seen.get(0).latitude()) <= 90.0);

      var drySource = new CubeNetBiomeSource(lookup, 1000.0, 5, SphereSingularityPolicy.DEFAULT_CONFIG,
         fallback, (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 30),
         (point, resolution, water) -> new CubeNetBiomeSource.ClimateData("Cfb", null),
         (point, scale) -> {
            fail("dry cover must not trigger an OSM water query");
            return CubeNetBiomeSource.WaterData.dry();
         });
      assertNotNull(drySource.getNoiseBiome(quartX, 0, quartZ, null));
   }

   @Test
   void cubeNetBiomeSourceCodecUsesTheActiveBiomeRegistry() {
      var provider = VanillaRegistries.createLookup();
      var lookup = provider.lookupOrThrow(Registries.BIOME);
      var fallback = new FixedBiomeSource(lookup.getOrThrow(Biomes.PLAINS));
      var source = new CubeNetBiomeSource(lookup, 1000.0, 5,
         SphereSingularityPolicy.DEFAULT_CONFIG, fallback,
         (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 30));
      var ops = RegistryOps.create(JsonOps.INSTANCE, provider);
      JsonObject encoded = CubeNetBiomeSource.CODEC.codec().encodeStart(ops, source)
         .getOrThrow(false, failure -> fail(failure)).getAsJsonObject();
      assertEquals(1000.0, encoded.get("world_scale").getAsDouble());
      var restored = CubeNetBiomeSource.CODEC.codec().parse(ops, encoded)
         .getOrThrow(false, failure -> fail(failure));
      assertEquals(1000.0, restored.worldScale());
      assertEquals(5, restored.apronChunks());
   }

   @Test
   void cubeNetGeneratorCodecAcceptsTheGeodeticBiomeSource() {
      var provider = VanillaRegistries.createLookup();
      var ops = RegistryOps.create(JsonOps.INSTANCE, provider);
      JsonObject biomeSource = new JsonObject();
      biomeSource.addProperty("world_scale", 1000.0);
      biomeSource.addProperty("apron_chunks", 5);
      biomeSource.addProperty("excluded_radius_meters", 300000.0);
      biomeSource.addProperty("transition_width_meters", 50000.0);
      JsonObject fallback = new JsonObject();
      fallback.addProperty("biome", "minecraft:plains");
      biomeSource.add("fallback", fallback);

      JsonObject generator = new JsonObject();
      generator.addProperty("geometry_version", CubeNetChunkGenerator.GEOMETRY_VERSION);
      generator.addProperty("mapping", SphereWorldModel.MAPPING_ID);
      generator.addProperty("excluded_radius_meters", 300000.0);
      generator.addProperty("transition_width_meters", 50000.0);
      generator.add("biome_source", biomeSource);
      generator.addProperty("world_scale", 1000.0);
      generator.addProperty("sea_level", 64);
      generator.addProperty("min_y", -64);
      generator.addProperty("height", 384);
      generator.addProperty("experimental_apron_chunks", 5);

      var restored = CubeNetChunkGenerator.CODEC.parse(ops, generator)
         .getOrThrow(false, failure -> fail(failure));
      assertInstanceOf(CubeNetBiomeSource.class, restored.getBiomeSource());
      assertEquals(1000.0, restored.worldScale());
      assertEquals(5, restored.apronWidthChunks());
      biomeSource.addProperty("world_scale", 999.0);
      assertThrows(IllegalArgumentException.class, () -> CubeNetChunkGenerator.CODEC.parse(ops, generator));
   }

   @Test
   void cubeNetBiomeSourceCodecParsesWorldGenerationToggles() {
      var provider = VanillaRegistries.createLookup();
      var lookup = provider.lookupOrThrow(Registries.BIOME);
      JsonObject source = new JsonObject();
      source.addProperty("world_scale", 1000.0);
      source.addProperty("apron_chunks", 5);
      JsonObject fallback = new JsonObject();
      fallback.addProperty("biome", "minecraft:plains");
      source.add("fallback", fallback);
      JsonObject settings = new JsonObject();
      settings.addProperty("lake_freezing", false);
      settings.addProperty("lava_pools", true);
      settings.addProperty("geodes", true);
      settings.addProperty("enable_water", false);
      settings.addProperty("teleport_progress", false);
      settings.addProperty("height_offset", 96);
      settings.addProperty("spawn_latitude", 35.3606);
      settings.addProperty("spawn_longitude", 138.7274);
      source.add("settings", settings);
      var ops = RegistryOps.create(JsonOps.INSTANCE, provider);
      var decoded = CubeNetBiomeSource.CODEC.codec().parse(ops, source).getOrThrow(false, failure -> fail(failure));
      assertFalse(decoded.settings().lakeFreezing());
      assertTrue(decoded.settings().lavaPools());
      assertTrue(decoded.settings().geodes());
      assertFalse(decoded.settings().enableWater());
      assertFalse(decoded.settings().teleportProgress());
      assertEquals(96, decoded.settings().heightOffset());
      assertEquals(35.3606, decoded.settings().spawnLatitude(), 0.000001);
      assertEquals(138.7274, decoded.settings().spawnLongitude(), 0.000001);
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
   void materializesACutApronAsANativeChunkFromCanonicalFaceColumns() {
      AtomicInteger calls = new AtomicInteger();
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1000.0, 64, -64, 384,
         (point, resolution) -> {
            calls.incrementAndGet();
            return new SphereChunkSampler.SurfaceData(-1000.0, 80, 0.0);
         });
      var layout = generator.adapter().layout();
      ChunkPos proxy = null;
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(face, side)) continue;
         double edge = layout.faceSize() * 0.5;
         double u = switch (side) {
            case WEST -> -8.0;
            case EAST -> layout.faceSize() + 8.0;
            case NORTH, SOUTH -> edge;
         };
         double v = switch (side) {
            case NORTH -> -8.0;
            case SOUTH -> layout.faceSize() + 8.0;
            case WEST, EAST -> edge;
         };
         var planar = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(face, u, v));
         ChunkPos candidate = new ChunkPos((int)Math.floor(planar.x() / 16.0),
            (int)Math.floor(planar.z() / 16.0));
         if (generator.apronResolver().resolveChunk(candidate.x, candidate.z).isPresent()) {
            proxy = candidate;
            break;
         }
      }
      assertNotNull(proxy, "no native apron chunk found");
      var chunk = new ProtoChunk(proxy, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), registry, null);
      generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();
      assertEquals(256, calls.get());
      assertTrue(chunk.getBlockState(new net.minecraft.core.BlockPos(proxy.getMinBlockX(), 10,
         proxy.getMinBlockZ())).is(Blocks.STONE));
      assertTrue(chunk.getBlockState(new net.minecraft.core.BlockPos(proxy.getMinBlockX(), 64,
         proxy.getMinBlockZ())).is(Blocks.WATER));
   }

   @Test
   void convertsApronTargetFaceBlockCenterToCanonicalChunkCoordinates() {
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1000.0, 64, -64, 384,
         (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 30));
      var layout = generator.adapter().layout();
      CubeNetApronResolver.ChunkProjection projection = null;
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(face, side)) continue;
         double edge = layout.faceSize() * 0.5;
         double u = switch (side) {
            case WEST -> -8.0;
            case EAST -> layout.faceSize() + 8.0;
            case NORTH, SOUTH -> edge;
         };
         double v = switch (side) {
            case NORTH -> -8.0;
            case SOUTH -> layout.faceSize() + 8.0;
            case WEST, EAST -> edge;
         };
         var planar = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(face, u, v));
         ChunkPos candidate = new ChunkPos((int)Math.floor(planar.x() / 16.0),
            (int)Math.floor(planar.z() / 16.0));
         projection = generator.apronResolver().resolveChunk(candidate.x, candidate.z).orElse(null);
         if (projection != null) break;
      }
      assertNotNull(projection, "no native apron projection found");
      int targetX = projection.targetChunkX() * 16 + 8;
      int targetZ = projection.targetChunkZ() * 16 + 8;
      var targetCenter = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(
         projection.targetFace(), targetX + 0.5, targetZ + 0.5));
      ChunkPos expected = new ChunkPos(
         Math.floorDiv((int)Math.floor(targetCenter.x()), 16),
         Math.floorDiv((int)Math.floor(targetCenter.z()), 16));
      assertEquals(expected, CubeNetApronChunkSync.canonicalChunkPosition(layout, projection));
      assertTrue(generator.address(expected).isPresent(), "target must resolve to a canonical face chunk");
   }

   @Test
   void leavesQuarantinedCornerChunksEmptyInsteadOfThrowing() {
      AtomicInteger calls = new AtomicInteger();
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1000.0, 64, -64, 384,
         (point, resolution) -> {
            calls.incrementAndGet();
            return new SphereChunkSampler.SurfaceData(10.0, 30);
         });
      var layout = generator.adapter().layout();
      var planarCenter = layout.toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.NEG_Z,
         1 * 16.0 + 8.0, 627 * 16.0 + 8.0));
      var quarantined = new ChunkPos((int)Math.floor(planarCenter.x() / 16.0),
         (int)Math.floor(planarCenter.z() / 16.0));
      var chunk = new ProtoChunk(quarantined, UpgradeData.EMPTY,
         LevelHeightAccessor.create(-64, 384), registry, null);
      generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();
      assertEquals(0, calls.get());
      assertEquals(-64, generator.getBaseHeight(quarantined.getMinBlockX(), quarantined.getMinBlockZ(),
         net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, chunk, null));
   }

   @Test
   void materializesTheTransitionBufferWithTheOrdinarySampler() {
      AtomicInteger calls = new AtomicInteger();
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1000.0, 64, -64, 384,
         (point, resolution) -> {
            calls.incrementAndGet();
            return new SphereChunkSampler.SurfaceData(10.0, 30);
         });
      var policy = new SphereSingularityPolicy(generator.model(), SphereSingularityPolicy.DEFAULT_CONFIG);
      SphereWorldModel.SphereChunkAddress transition = null;
      int chunks = Math.toIntExact(generator.model().chunksPerFace());
      outer: for (int face = 0; face < SphereWorldModel.FACE_COUNT; face++) {
         for (int z = 0; z < chunks; z++) for (int x = 0; x < chunks; x++) {
            var candidate = new SphereWorldModel.SphereChunkAddress(face, x, z);
            if (policy.status(candidate) == SphereSingularityPolicy.Status.TRANSITION) {
               transition = candidate;
               break outer;
            }
         }
      }
      assertNotNull(transition, "test model must contain a transition chunk");
      assertTrue(policy.admitsTerrainMaterialization(transition));
      assertFalse(policy.admitsOrdinaryMaterialization(transition));

      var layout = generator.adapter().layout();
      var center = layout.toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.of(transition.face()),
         transition.blockX() + 8.0, transition.blockZ() + 8.0));
      var pos = new ChunkPos((int)Math.floor(center.x() / 16.0), (int)Math.floor(center.z() / 16.0));
      var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), registry, null);
      generator.fillFromNoise(Runnable::run, null, null, null, chunk).join();
      assertEquals(256, calls.get());
      assertTrue(chunk.getBlockState(new net.minecraft.core.BlockPos(pos.getMinBlockX(), 10,
         pos.getMinBlockZ())).is(Blocks.STONE));
   }

   @Test
   void boundaryVisualGateIncludesTheMovementChunkAllowanceAndIgnoresOrdinarySeams() {
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1000.0, 64, -64, 384,
         (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 30));
      assertTrue(generator.admitsOrdinaryPlanarPoint(10848.5, -607.5));
      assertFalse(generator.admitsBoundaryHintPoint(10848.5, -607.5),
         "the visual wall must include the conservative chunk movement gate");
      assertTrue(generator.admitsBoundaryHintPoint(10920.5, -679.5));
      assertTrue(generator.nearOrdinaryBoundary(10920.5, -679.5, 96.0));
      assertFalse(generator.nearOrdinaryBoundary(15360.0, -5120.0, 96.0));
      assertFalse(generator.nearOrdinaryBoundary(15360.0, -1.0, 96.0),
         "an ordinary cube seam must not display an exclusion wall");
      var contact = CubeNetRuntime.findBoundaryHint(generator, 10848.004495571211, -619.5);
      assertNotNull(contact);
      assertEquals(0.0, contact.tangentX(), 0.0001,
         "sub-block contact must retain the west wall direction");
      assertEquals(1.0, Math.abs(contact.tangentZ()), 0.0001);
      assertEquals(10847.5, contact.x(), 0.0001,
         "the half-block visual inset must remain beyond the camera near plane");
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
      assertEquals(CubeNetViewCoverage.DEFAULT_APRON_CHUNKS, restored.apronWidthChunks());
      encoded.addProperty("geometry_version", 2);
      assertTrue(CubeNetChunkGenerator.CODEC.parse(ops, encoded).error().isPresent());
   }
}
