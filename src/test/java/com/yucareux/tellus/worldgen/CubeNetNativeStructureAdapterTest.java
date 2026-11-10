package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.worldgen.global.*;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual generator -> shared rule -> native starts/pieces on both target versions; controlled source, not live GIS. */
class CubeNetNativeStructureAdapterTest {
   @TempDir static Path directory;
   static Registry<Structure> structures;
   static RegistryAccess access;
   static Method adapt;
   @BeforeAll static void bootstrap() throws Exception {
      SphereFaceNativeBiomeKernelBridgeTest.directory=directory;
      SphereFaceNativeBiomeKernelBridgeTest.bootstrap();
      var mapped=new MappedRegistry<Structure>(Registries.STRUCTURE,Lifecycle.stable());
      SphereFaceNativeBiomeKernelBridgeTest.lookup.lookupOrThrow(Registries.STRUCTURE).listElements().forEach(entry ->
         Registry.register(mapped,entry.key(),entry.value()));
      structures=mapped.freeze();access=new RegistryAccess.ImmutableRegistryAccess(List.of(structures));
      adapt=CubeNetChunkGenerator.class.getDeclaredMethod("retargetCubeNetUndergroundStarts",RegistryAccess.class,ChunkAccess.class);
      adapt.setAccessible(true);
   }
   @AfterAll static void restore() { SphereFaceNativeBiomeKernelBridgeTest.restore(); }
   static Structure structure(String path) {
      return structures.entrySet().stream().filter(e->e.getKey().location().getPath().equals(path))
         .map(Map.Entry::getValue).findFirst().orElseThrow();
   }
   static CubeNetChunkGenerator generator(boolean ocean, double elevation) throws Exception {
      return generator(ocean,elevation,64);
   }
   static CubeNetChunkGenerator generator(boolean ocean, double elevation, int seaLevel) throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()) {
         var gen=new CubeNetChunkGenerator(new FixedBiomeSource(SphereFaceNativeBiomeKernelBridgeTest.biomes.getOrThrow(Biomes.PLAINS)),
            1000.0,seaLevel,-64,384,(p,r)->ocean ? new SphereChunkSampler.SurfaceData(elevation,80,0)
               : new SphereChunkSampler.SurfaceData(elevation,30),0);
         var seed=CubeNetChunkGenerator.class.getDeclaredField("worldSeed");seed.setAccessible(true);seed.setLong(gen,773L);
         return gen;
      }
   }
   static ChunkPos pos(CubeNetChunkGenerator gen,CubeNetLayout.Face face) {
      var layout=gen.adapter().layout();int u=(layout.faceSize()/2/16)*16;
      var p=layout.toPlanar(new CubeNetLayout.FacePoint(face,u+0.5,u+0.5));
      return new ChunkPos(Math.floorDiv((int)Math.floor(p.x()),16),Math.floorDiv((int)Math.floor(p.z()),16));
   }
   static StructureStart start(Structure structure,ChunkPos pos,int minY,int maxY) {
      return new StructureStart(structure,pos,2,new PiecesContainer(List.of(
         new TestPiece(new BoundingBox(pos.getMinBlockX(),minY,pos.getMinBlockZ(),pos.getMinBlockX()+31,maxY,pos.getMinBlockZ()+31)),
         new TestPiece(new BoundingBox(pos.getMinBlockX()+2,minY+1,pos.getMinBlockZ()+2,pos.getMinBlockX()+8,maxY-1,pos.getMinBlockZ()+8)))));
   }
   @Test void realGeneratorUsesGeodeticSeedAndEarthDepthConditionsThenMovesEveryPiece() throws Exception {
      var kinds=new ArrayList<>(List.of(
         TellusStructureAdaptationRules.UndergroundKind.STRONGHOLD,TellusStructureAdaptationRules.UndergroundKind.NORMAL_MINESHAFT,
         TellusStructureAdaptationRules.UndergroundKind.ANCIENT_CITY));
      if(structures.entrySet().stream().anyMatch(e->e.getKey().location().getPath().equals("trial_chambers")))
         kinds.add(TellusStructureAdaptationRules.UndergroundKind.TRIAL_CHAMBERS);
      for(var face:CubeNetLayout.Face.values()) for(var kind:kinds) {
         var gen=generator(false,20000.0);var pos=pos(gen,face);var chunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);
         String id=switch(kind){case STRONGHOLD->"stronghold";case NORMAL_MINESHAFT->"mineshaft";
            case TRIAL_CHAMBERS->"trial_chambers";default->"ancient_city";};
         var type=structure(id);var old=start(type,pos,78,96);var box=old.getBoundingBox();
         var point=new SphereWorldModel.GeoPoint(gen.latitudeFromBlock(box.getCenter().getX(),box.getCenter().getZ()),
            gen.longitudeFromBlock(box.getCenter().getX(),box.getCenter().getZ()),0.0);
         int earthX=(int)Math.floor(EarthProjection.longitudeToBlockX(point.longitude(),1000.0));
         int earthZ=(int)Math.floor(EarthProjection.latToBlockZ(point.latitude(),1000.0));
         var expected=TellusStructureAdaptationRules.underground(kind,true,false,
            new TellusStructureAdaptationRules.Bounds(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()),
            earthX,earthZ,773L,-64,319,(x,z)->new TellusStructureAdaptationRules.CenterColumn(84,false),(x,z)->84);
         int[] before=old.getPieces().stream().mapToInt(p->p.getBoundingBox().minY()).toArray();
         chunk.setStartForStructure(type,old);adapt.invoke(gen,access,chunk);var actual=chunk.getStartForStructure(type);
         assertEquals(expected.outcome()!=TellusStructureAdaptationRules.Outcome.INVALIDATE,actual.isValid());
         if(actual.isValid()) {
            assertEquals(2,actual.getReferences());assertEquals(2,actual.getPieces().size());
            for(int i=0;i<before.length;i++) assertEquals(before[i]+expected.offsetY(),actual.getPieces().get(i).getBoundingBox().minY(),face+" "+kind);
         }
      }
   }
   @Test void realGeneratorKeepsAlreadyBuriedStrongholdsAndNonShellMesaIdentity() throws Exception {
      var gen=generator(false,100000.0);var pos=pos(gen,CubeNetLayout.Face.POS_Y);
      for(String id:List.of("stronghold","mineshaft_mesa")) {
         var chunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var type=structure(id);var old=start(type,pos,0,16);
         chunk.setStartForStructure(type,old);adapt.invoke(gen,access,chunk);assertSame(old,chunk.getStartForStructure(type),id);
      }
   }
   @Test void realGeneratorAdaptsDeepOceanMonumentAndRejectsShallowOrDryFootprints() throws Exception {
      for(var face:CubeNetLayout.Face.values()) for(int scenario=0;scenario<3;scenario++) {
         var gen=generator(scenario!=2,scenario==0?-50000.0:-5000.0);var pos=pos(gen,face);
         var chunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var type=structure("monument");var old=start(type,pos,20,42);
         chunk.setStartForStructure(type,old);adapt.invoke(gen,access,chunk);var actual=chunk.getStartForStructure(type);
         if(scenario==0) {
            assertTrue(actual.isValid());assertEquals(62,actual.getBoundingBox().maxY());
            assertEquals(2,actual.getReferences());assertEquals(2,actual.getPieces().size());
            assertEquals(40,actual.getPieces().get(0).getBoundingBox().minY());
            assertEquals(41,actual.getPieces().get(1).getBoundingBox().minY());
         } else assertSame(StructureStart.INVALID_START,actual,"scenario="+scenario+" "+face);
      }
   }
   @Test void unavailableColumnsKeepNativeStartsAndAllPiecesUnchanged() throws Exception {
      var gen=generator(false,Double.NaN);var pos=pos(gen,CubeNetLayout.Face.POS_Y);
      for(String id:List.of("stronghold","mineshaft","ancient_city","monument")) {
         var chunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var type=structure(id);var old=start(type,pos,20,42);
         int[] before=old.getPieces().stream().mapToInt(p->p.getBoundingBox().minY()).toArray();
         chunk.setStartForStructure(type,old);adapt.invoke(gen,access,chunk);
         assertSame(old,chunk.getStartForStructure(type));
         assertArrayEquals(before,old.getPieces().stream().mapToInt(p->p.getBoundingBox().minY()).toArray());
      }
   }
   @Test void monumentUsesEffectiveNondefaultSeaAndOnlyMovesAfterCompleteWaterPreflight() throws Exception {
      for(int sea:List.of(37,88)) {
         var gen=generator(true,-50000,sea);var pos=pos(gen,CubeNetLayout.Face.NEG_Z);
         var chunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var type=structure("monument");var old=start(type,pos,20,42);
         chunk.setStartForStructure(type,old);adapt.invoke(gen,access,chunk);var actual=chunk.getStartForStructure(type);
         assertTrue(actual.isValid());assertEquals(sea-2,actual.getBoundingBox().maxY());
         assertEquals(sea-24,actual.getPieces().get(0).getBoundingBox().minY());
         assertEquals(sea-23,actual.getPieces().get(1).getBoundingBox().minY());
      }
   }
   private static final class TestPiece extends StructurePiece {
      TestPiece(BoundingBox box){super(StructurePieceType.STRONGHOLD_STAIRS_DOWN,0,box);}
      @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context,CompoundTag tag) { }
      @Override public void postProcess(WorldGenLevel level,StructureManager structures,net.minecraft.world.level.chunk.ChunkGenerator generator,
         RandomSource random,BoundingBox box,ChunkPos chunkPos,BlockPos position) { }
   }
}
