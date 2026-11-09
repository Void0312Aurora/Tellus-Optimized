package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.write.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real Earth classifier/cached native fill over the established local disk/empty-vector fixture, not GIS acceptance. */
class TellusEarthBiomeVolumeIntegrationTest {
   @TempDir static Path root;
   static Registry<Biome> registry;
   @BeforeAll static void initialize() throws Exception {
      EarthNativeFillCaptureIntegrationTest.directory=root;EarthNativeFillCaptureIntegrationTest.bootstrap();
      var mapped=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());EarthNativeFillCaptureIntegrationTest.lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(ref->Registry.register(mapped,ref.key(),ref.value()));registry=mapped.freeze();
   }
   @AfterAll static void cleanup() throws Exception {EarthNativeFillCaptureIntegrationTest.restore();}
   static ProtoChunk chunk(TellusGenerationContext g,ChunkPos pos) throws Exception {
      return (ProtoChunk)EarthNativeFillCaptureIntegrationTest.proto.newInstance(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(g.minY(),g.maxYExclusive()-g.minY()),registry,null);
   }
   static TellusBiomeVolumePlan.Request request(EarthChunkGenerator gen,ChunkPos pos){
      var g=new TellusGenerationContext(gen.worldSeed(),EarthNativeFillCaptureIntegrationTest.settings.worldScale(),gen.getSeaLevel(),gen.getMinY(),gen.getMinY()+gen.getGenDepth());var model=SphereWorldModel.forWorldScale(g.worldScale());List<WriteContext> columns=new ArrayList<>();
      for(int z=0;z<4;z++)for(int x=0;x<4;x++){
         double bx=pos.getMinBlockX()+4*x+0.5,bz=pos.getMinBlockZ()+4*z+0.5;var geo=new SphereWorldModel.GeoPoint(EarthProjection.blockZToLat(bz,g.worldScale()),EarthProjection.blockXToLongitude(bx,g.worldScale()),0);
         var c=TellusCoordinateContextFactory.fromGeo(model,geo,new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo,g.worldScale())));columns.add(WriteContext.canonical(c,g,"earth-biome-volume"));}
      return new TellusBiomeVolumePlan.Request(g,pos.x,pos.z,columns,SamplingResolution.exact(g.worldScale()));
   }
   @Test void realEarthClassifierCachedNativeVolumesMatchAtEveryVerticalQuartWithoutChangingFastSpawn() throws Exception {
      int cells=0,varying=0,chunks=0;Set<String> ids=new TreeSet<>();
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         for(var pos:List.of(new ChunkPos(0,0),new ChunkPos(-1,-1))){
            var source=new EarthBiomeSource(EarthNativeFillCaptureIntegrationTest.lookup.lookupOrThrow(Registries.BIOME),EarthNativeFillCaptureIntegrationTest.settings);var gen=new EarthChunkGenerator(source,EarthNativeFillCaptureIntegrationTest.settings);var request=request(gen,pos);
            assertEquals(Boolean.TRUE,CubeNetNativeBaseProviderPipelineTest.field(source,"fastSpawnMode"));
            var plan=gen.semanticBiomeVolumePlanner().plan(request);assertEquals(DecisionStatus.AVAILABLE,plan.status());assertEquals(Boolean.TRUE,CubeNetNativeBaseProviderPipelineTest.field(source,"fastSpawnMode"),"Explicit planning must not alter source fast-spawn state");
            var nativeChunk=chunk(request.generation(),pos);var semantic=chunk(request.generation(),pos);gen.createBiomes(EarthNativeFillCaptureIntegrationTest.random,null,null,nativeChunk).join();
            var result=new TellusNativeBiomeVolumeWriter().write(true,semantic,request,gen.semanticBiomeVolumePlanner(),registry);assertEquals(WriteStatus.WRITTEN,result.status());assertEquals(plan.cells().size(),result.writtenQuartCells());
            for(var cell:plan.cells()){
               int section=Math.floorDiv(cell.quartY()*4-request.generation().minY(),16);int localY=Math.floorMod(cell.quartY(),4),localX=Math.floorMod(cell.quartX(),4),localZ=Math.floorMod(cell.quartZ(),4);
               var a=nativeChunk.getSections()[section].getNoiseBiome(localX,localY,localZ);var b=semantic.getSections()[section].getNoiseBiome(localX,localY,localZ);assertEquals(a.unwrapKey(),b.unwrapKey());assertEquals(a.unwrapKey().orElseThrow().location().toString(),cell.biome().id());ids.add(cell.biome().id());cells++;}
            for(int x=0;x<4;x++)for(int z=0;z<4;z++){Set<String> column=new HashSet<>();for(var section:nativeChunk.getSections())for(int y=0;y<4;y++)column.add(section.getNoiseBiome(x,y,z).unwrapKey().orElseThrow().location().toString());if(column.size()>1)varying++;}
            chunks++;
         }
      }
      assertEquals(2,chunks);assertTrue(cells>0);
      System.out.println("NATIVE_EARTH_BIOME_VOLUME chunks="+chunks+" quartCells="+cells+" verticallyVaryingColumns="+varying+" biomeIds="+ids+" mismatchQuarts=0 explicitPlanningPreservesFastSpawn=true localFixtureVariationObserved="+(varying>0));
   }
   @Test void earthStructureQueryModeRejectsNewAndPreviouslyCreatedVolumeResolversBeforeClassification() {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         var source=new EarthBiomeSource(EarthNativeFillCaptureIntegrationTest.lookup.lookupOrThrow(Registries.BIOME),EarthNativeFillCaptureIntegrationTest.settings);var gen=new EarthChunkGenerator(source,EarthNativeFillCaptureIntegrationTest.settings);var request=request(gen,new ChunkPos(0,0));var resolver=source.semanticVolumeResolver();
         source.beginStructureBiomeQueries();try{
            assertThrows(IllegalStateException.class,source::semanticVolumeResolver);assertThrows(IllegalStateException.class,()->resolver.getNoiseBiome(0,-16,0,Climate.empty()));assertThrows(IllegalStateException.class,()->gen.semanticBiomeVolumePlanner().plan(request));
         }finally{source.endStructureBiomeQueries();}
         assertEquals(DecisionStatus.AVAILABLE,gen.semanticBiomeVolumePlanner().plan(request).status());
      }
   }
}
