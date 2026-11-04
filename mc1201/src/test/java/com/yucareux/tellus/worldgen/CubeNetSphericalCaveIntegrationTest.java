package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.caves.CubeNetSphericalCaveField;
import com.yucareux.tellus.worldgen.caves.CubeNetSphericalCaveSampler;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class CubeNetSphericalCaveIntegrationTest {
   private static Registry<Biome> biomes;
   private static Holder<Biome> plains;
   private static RandomState random;
   private static CubeNetSphericalCaveField field;
   private static Field tags;
   private static final Map<Holder.Reference<?>, Object> savedTags = new HashMap<>();
   private static final int SURFACE = 140;
   @TempDir static Path gameDir;
   private static final String[] PROPERTIES = {"tellus.gameDir", "tellus.configDir", "tellus.overture.release"};
   private static final String[] oldProperties = new String[PROPERTIES.length];

   @BeforeAll
   @SuppressWarnings("deprecation")
   static void bootstrap() throws Exception {
      for (int i=0; i<PROPERTIES.length; i++) oldProperties[i] = System.getProperty(PROPERTIES[i]);
      System.setProperty(PROPERTIES[0], gameDir.toString());
      System.setProperty(PROPERTIES[1], gameDir.resolve("config").toString());
      System.setProperty(PROPERTIES[2], "2026-06-17.0");
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      var lookup = VanillaRegistries.createLookup();
      var registry = new MappedRegistry<Biome>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
      plains = registry.register(Biomes.PLAINS, lookup.lookupOrThrow(Registries.BIOME)
         .getOrThrow(Biomes.PLAINS).value(), com.mojang.serialization.Lifecycle.stable());
      biomes = registry.freeze();
      random = RandomState.create(lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
         .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value(), lookup.lookupOrThrow(Registries.NOISE), 1234L);
      field = new CubeNetSphericalCaveField(1234L);
      // Plain JUnit does not load datapack block tags. Restore the holders afterwards.
      tags = Holder.Reference.class.getDeclaredField("tags");
      tags.setAccessible(true);
      Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
      bind.setAccessible(true);
      for (var block : List.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT, Blocks.GRASS_BLOCK)) {
         var holder = block.builtInRegistryHolder();
         savedTags.put(holder, tags.get(holder));
         bind.invoke(holder, List.of(BlockTags.BASE_STONE_OVERWORLD, BlockTags.OVERWORLD_CARVER_REPLACEABLES));
      }
   }

   @AfterAll
   static void restore() throws Exception {
      for (var entry : savedTags.entrySet()) tags.set(entry.getKey(), entry.getValue());
      for (int i=0; i<PROPERTIES.length; i++) {
         if (oldProperties[i] == null) System.clearProperty(PROPERTIES[i]);
         else System.setProperty(PROPERTIES[i], oldProperties[i]);
      }
   }

   @Test
   void productionCarvingMatchesThePhysicalFieldOnEveryRotatedFace() {
      var generator = generator(0);
      var layout = generator.adapter().layout();
      int caves = 0;
      for (var face : CubeNetLayout.Face.values()) {
         var pos = chunkAt(layout, face, layout.faceSize()/2.0+0.5, layout.faceSize()/2.0+0.5);
         var chunk = filled(generator, pos);
         generator.applyCarvers(null, 1234L, random, null, null, chunk, GenerationStep.Carving.AIR);
         var cursor = new BlockPos.MutableBlockPos();
         for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = pos.getMinBlockX()+x, wz = pos.getMinBlockZ()+z;
            var p = layout.fromPlanar(new CubeNetLayout.PlanarPoint(wx+0.5, wz+0.5));
            var column = field.column(generator.model().exactSurfacePoint(face.index(), p.u(), p.v()), 1000.0);
            for (int y = -63; y <= SURFACE-5; y++) {
               var cavity = field.cavity(column.atHeight(y-64), y-64, SURFACE-y, y, -64);
               if (cavity == CubeNetSphericalCaveField.Cavity.SOLID) continue;
               cursor.set(wx, y, wz);
               BlockState expected = switch(cavity) {
                  case AIR -> Blocks.CAVE_AIR.defaultBlockState();
                  case WATER -> Blocks.WATER.defaultBlockState();
                  case LAVA -> Blocks.LAVA.defaultBlockState();
                  default -> throw new AssertionError();
               };
               assertEquals(expected, chunk.getBlockState(cursor), face+" "+cursor);
               caves++;
            }
            for (int y = SURFACE-4; y <= SURFACE; y++) {
               cursor.set(wx, y, wz);
               assertFalse(chunk.getBlockState(cursor).isAir(), "surface cover");
            }
            cursor.set(wx, -64, wz);
            assertTrue(chunk.getBlockState(cursor).is(Blocks.BEDROCK), "dimension floor");
         }
      }
      assertTrue(caves > 100, "fixture must actually carve cavities: "+caves);
   }

   @Test
   void floodFloorStructureAndSurfaceEntranceSwitchesGuardActualWrites() {
      var generator = generator(0);
      var layout = generator.adapter().layout();
      ChunkPos pos = null;
      SphereWorldModel.SphereChunkAddress address = null;
      int caveY = Integer.MIN_VALUE;
      outer: for (var candidateFace : CubeNetLayout.Face.values()) for (int step=1; step<=8; step++) {
         double u = layout.faceSize() * step / 9.0, v = layout.faceSize() * (9-step) / 9.0;
         ChunkPos candidate = chunkAt(layout, candidateFace, u, v);
         var candidateAddress = generator.address(candidate).orElse(null);
         if (candidateAddress == null) continue;
         for (int z=0; z<16 && caveY==Integer.MIN_VALUE; z++) for(int x=0; x<16 && caveY==Integer.MIN_VALUE; x++) {
            var point = layout.fromPlanar(new CubeNetLayout.PlanarPoint(candidate.getMinBlockX()+x+0.5, candidate.getMinBlockZ()+z+0.5));
            var column = field.column(generator.model().exactSurfacePoint(point.face().index(),point.u(),point.v()),1000.0);
            for (int y=-56; y<SURFACE-8; y++) if(field.cavity(column.atHeight(y-64), y-64, SURFACE-y, y, -64)
               != CubeNetSphericalCaveField.Cavity.SOLID) { caveY=y; pos=candidate; address=candidateAddress; break; }
         }
         if (caveY != Integer.MIN_VALUE) break outer;
      }
      assertNotEquals(Integer.MIN_VALUE,caveY,"fixture must contain a real cavity");
      int floorY = caveY-1, guardY=caveY+1;
      int[] surfaces = array(SURFACE), guards = array(guardY), floors = array(floorY);
      var protectedChunk = filled(generator, pos);
      var box = new UndergroundStructureExclusion.Box(pos.getMinBlockX(), -64, pos.getMinBlockZ(),
         pos.getMaxBlockX(), SURFACE, pos.getMaxBlockZ());
      var writes = new ArrayList<BlockPos>();
      var sampler = new CubeNetSphericalCaveSampler();
      sampler.apply(generator.model(), address, field, protectedChunk, -64, 64,
         true, true, false, false, surfaces, null, guards, floors, List.of(box),
         (chunk, p, state, fluid) -> writes.add(p.immutable()));
      assertTrue(writes.isEmpty(), "fully protected fixture must not be carved");

      sampler.apply(generator.model(), address, field, protectedChunk, -64, 64,
         true, false, false, false, surfaces, null, guards, floors, List.of(),
         (chunk, p, state, fluid) -> writes.add(p.immutable()));
      assertFalse(writes.isEmpty(), "guard test must exercise actual cave candidates");
      assertTrue(writes.stream().allMatch(p -> p.getY() > floorY && p.getY() < guardY));

      // Pick a real cavity at the surface rather than assuming a lucky noise seed.
      outer: for (var face : CubeNetLayout.Face.values()) {
         for (int cx = 3; cx < 30; cx += 4) {
            var surfacePos = chunkAt(layout, face, cx*16+8.5, layout.faceSize()/2.0+8.5);
            var surfaceChunk = filled(generator, surfacePos);
            var surfaceAddress = generator.address(surfacePos).orElseThrow();
            var entrances = new ArrayList<BlockPos>();
            sampler.apply(generator.model(), surfaceAddress, field, surfaceChunk, -64, 64,
               true, true, false, false, surfaces, null, null, floors, List.of(),
               (chunk, p, state, fluid) -> { if(p.getY() == SURFACE) entrances.add(p.immutable()); });
            if (!entrances.isEmpty()) { assertTrue(entrances.size() > 0); break outer; }
            if (face == CubeNetLayout.Face.NEG_Z && cx == 27) fail("no surface entrance candidate");
         }
      }
   }

   @Test
   void allCutApronsMatchTheirCanonicalCarvedOwnerAtEveryY() {
      var generator = generator(5);
      var layout = generator.adapter().layout();
      int views=0, caveBlocks=0;
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(face,side)) continue;
         double edge=layout.faceSize()*0.5+8.5;
         double u=side==CubeNetLayout.Side.WEST ? -8.5 : side==CubeNetLayout.Side.EAST ? layout.faceSize()+8.5 : edge;
         double v=side==CubeNetLayout.Side.NORTH ? -8.5 : side==CubeNetLayout.Side.SOUTH ? layout.faceSize()+8.5 : edge;
         var center=layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(face,u,v));
         var pos=new ChunkPos((int)Math.floor(center.x()/16.0),(int)Math.floor(center.z()/16.0));
         var projection=generator.apronProjection(pos).orElseThrow();
         var apron=filled(generator,pos);
         var ownerPos=CubeNetApronChunkSync.canonicalChunkPosition(layout,projection);
         var owner=filled(generator,ownerPos);
         generator.applyCarvers(null,1234L,random,null,null,owner,GenerationStep.Carving.AIR);
         generator.applyCarversToApron(biomes,1234L,random,null,apron,projection);
         var cursor=new BlockPos.MutableBlockPos();
         for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            var target=layout.toPlanar(new CubeNetLayout.FacePoint(projection.targetFace(),
               projection.targetBlockX(x,z)+0.5,projection.targetBlockZ(x,z)+0.5));
            for(int y=-64;y<320;y++) {
               cursor.set((int)Math.floor(target.x()),y,(int)Math.floor(target.z()));
               var expected=owner.getBlockState(cursor);
               cursor.set(pos.getMinBlockX()+x,y,pos.getMinBlockZ()+z);
               assertEquals(expected,apron.getBlockState(cursor),face+" "+side+" "+cursor);
               if(y<SURFACE-5 && expected.is(Blocks.CAVE_AIR)) caveBlocks++;
            }
         }
         views++;
      }
      assertEquals(14,views);
      assertTrue(caveBlocks>0,"apron fixture must contain carved caves");
   }

   @Test
   void waterHaloCrossesPhysicalCutSeamsEvenWithApronsDisabled() {
      var generator = new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1000.0, 64, -64, 384,
         (p, resolution) -> new SphereChunkSampler.SurfaceData(76000, 80, 80000), 0);
      var layout = generator.adapter().layout();
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(face, side)) continue;
         double u = side == CubeNetLayout.Side.WEST ? 8.5 : side == CubeNetLayout.Side.EAST ? layout.faceSize()-8.5 : layout.faceSize()/2.0+8.5;
         double v = side == CubeNetLayout.Side.NORTH ? 8.5 : side == CubeNetLayout.Side.SOUTH ? layout.faceSize()-8.5 : layout.faceSize()/2.0+8.5;
         var pos = chunkAt(layout, face, u, v);
         var columns = generator.undergroundColumns(pos, generator.address(pos).orElseThrow());
         for (int guard : columns.floodGuardY()) assertEquals(137, guard);
      }
   }

   private static CubeNetChunkGenerator generator(int apron) {
      return new CubeNetChunkGenerator(new FixedBiomeSource(plains), 1000.0, 64, -64, 384,
         (p, resolution) -> new SphereChunkSampler.SurfaceData(76000, 30), apron);
   }
   private static ProtoChunk filled(CubeNetChunkGenerator generator, ChunkPos pos) {
      var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(-64,384), biomes, null);
      chunk.setStatus(ChunkStatus.BIOMES);
      generator.fillFromNoise(Runnable::run, null, random, null, chunk).join();
      return chunk;
   }
   private static ChunkPos chunkAt(CubeNetLayout layout, CubeNetLayout.Face face, double u, double v) {
      var center = layout.toPlanar(new CubeNetLayout.FacePoint(face,u,v));
      return new ChunkPos((int)Math.floor(center.x()/16.0), (int)Math.floor(center.z()/16.0));
   }
   private static int[] array(int value) { int[] result = new int[256]; Arrays.fill(result,value); return result; }
}
