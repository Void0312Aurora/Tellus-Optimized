package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
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
