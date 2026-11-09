package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.write.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual native quart classification and section palettes; controlled inputs are not live GIS or world save acceptance. */
class TellusNativeBiomeVolumeIntegrationTest {
   @TempDir static Path root;
   static Registry<Biome> registry;
   @BeforeAll static void initialize() throws Exception {
      SphereFaceNativeBiomeKernelBridgeTest.directory=root;SphereFaceNativeBiomeKernelBridgeTest.bootstrap();
      var mapped=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
      SphereFaceNativeBiomeKernelBridgeTest.biomes.listElements().forEach(reference->Registry.register(mapped,reference.key(),reference.value()));registry=mapped.freeze();
   }
   @AfterAll static void cleanup(){SphereFaceNativeBiomeKernelBridgeTest.restore();}
   static ProtoChunk chunk(ChunkPos pos,int min,int height) throws Exception {
      var chunk=(ProtoChunk)SphereFaceNativeBiomeKernelBridgeTest.proto.newInstance(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(min,height),registry,null);
      chunk.setBlockState(new BlockPos(pos.getMinBlockX()+1,min+4,pos.getMinBlockZ()+2),Blocks.GLASS.defaultBlockState(),false);
      chunk.getPostProcessing()[0]=new it.unimi.dsi.fastutil.shorts.ShortArrayList(new short[]{7,7,99});chunk.setUnsaved(false);return chunk;
   }
   static TellusBiomeVolumePlan.Request request(TellusGenerationContext g,ChunkPos pos,BiFunction<Integer,Integer,TellusCoordinateContext> coordinates){
      List<WriteContext> columns=new ArrayList<>();for(int z=0;z<4;z++)for(int x=0;x<4;x++)columns.add(WriteContext.canonical(coordinates.apply(pos.getMinBlockX()+x*4,pos.getMinBlockZ()+z*4),g,"biome-volume-integration"));
      return new TellusBiomeVolumePlan.Request(g,pos.x,pos.z,columns,SamplingResolution.exact(g.worldScale()));
   }
   static void nativeFill(Object gen,ProtoChunk chunk) throws Exception {
      var method=Arrays.stream(gen.getClass().getMethods()).filter(m->m.getName().equals("createBiomes")&&(m.getParameterCount()==4||m.getParameterCount()==5)).sorted(Comparator.comparingInt(java.lang.reflect.Method::getParameterCount)).findFirst().orElseThrow();
      Object[] args=new Object[method.getParameterCount()];args[args.length-1]=chunk;if(args.length==5)args[0]=(java.util.concurrent.Executor)Runnable::run;
      if(gen instanceof EarthChunkGenerator)args[0]=net.minecraft.world.level.levelgen.RandomState.create(SphereFaceNativeBiomeKernelBridgeTest.lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD).value(),SphereFaceNativeBiomeKernelBridgeTest.lookup.lookupOrThrow(Registries.NOISE),0L);
      try{((CompletableFuture<?>)method.invoke(gen,args)).join();}catch(java.lang.reflect.InvocationTargetException error){if(error.getCause() instanceof RuntimeException runtime)throw runtime;throw error;}
   }
   static int compare(ProtoChunk expected,ProtoChunk actual,Set<String> ids){
      int cells=0;for(int section=0;section<expected.getSections().length;section++)for(int x=0;x<4;x++)for(int y=0;y<4;y++)for(int z=0;z<4;z++){
         var a=expected.getSections()[section].getNoiseBiome(x,y,z);var b=actual.getSections()[section].getNoiseBiome(x,y,z);
         assertEquals(a.unwrapKey(),b.unwrapKey(),"quart="+section+","+x+","+y+","+z);assertTrue(b.canSerializeIn(registry.holderOwner()));ids.add(a.unwrapKey().orElseThrow().location().toString());cells++;}
      CubeNetInlandFlowPipelineTest.equalMarkers(expected,actual);return cells;
   }
   static void retained(ProtoChunk target,LevelChunkSection[] old){
      for(int i=0;i<old.length;i++){assertSame(old[i],target.getSections()[i]);assertSame(old[i].getStates(),target.getSections()[i].getStates());
         assertEquals(old[i].hasOnlyAir(),target.getSections()[i].hasOnlyAir());assertEquals(old[i].isRandomlyTicking(),target.getSections()[i].isRandomlyTicking());}
      assertTrue(target.isUnsaved());
   }
   static CubeNetBiomeSource.Settings cubeSettings(int scene){
      var json=CubeNetBiomeSource.Settings.CODEC.encodeStart(JsonOps.INSTANCE,CubeNetBiomeSource.Settings.DEFAULT).result().orElseThrow().getAsJsonObject();
      json.addProperty("random_biomes",scene==2);json.addProperty("random_biome_density",0.4);json.addProperty("cave_generation",scene!=0);json.addProperty("deep_dark",scene!=0);json.addProperty("buildings",false);
      return CubeNetBiomeSource.Settings.CODEC.parse(JsonOps.INSTANCE,json).result().orElseThrow();
   }
   @Test void cubeNativeCreateBiomesMatchesCompleteVolumesAcrossSixFacesAndCaveProfiles() throws Exception {
      int chunks=0,cells=0,verticalColumns=0;Set<String> ids=new TreeSet<>();
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         for(int scene=0;scene<3;scene++)for(var face:CubeNetLayout.Face.values()){
            AtomicInteger reads=new AtomicInteger();var source=CubeNetNativeBaseProviderPipelineTest.source(cubeSettings(scene),reads);
            var gen=CubeNetNativeBaseProviderPipelineTest.generator(source,(point,resolution)->new SphereChunkSampler.SurfaceData(100,10),false,42);
            var c=CubeNetNativeBaseProviderPipelineTest.coordinate(gen,face,1,2);var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"biome-origin"));
            var pos=new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));
            var request=request(g,pos,(x,z)->TellusCoordinateContextFactory.fromCubeNetCanonical(gen.model(),gen.adapter().layout(),gen.adapter().layout().fromPlanar(new CubeNetLayout.PlanarPoint(x+0.5,z+0.5))));
            var nativeChunk=chunk(pos,g.minY(),g.maxYExclusive()-g.minY());var semantic=chunk(pos,g.minY(),g.maxYExclusive()-g.minY());var old=semantic.getSections().clone();
            nativeFill(gen,nativeChunk);int nativeReads=reads.get();reads.set(0);
            var result=new TellusNativeBiomeVolumeWriter().write(true,semantic,request,gen.semanticBiomeVolumePlanner(),registry);
            assertEquals(WriteStatus.WRITTEN,result.status());assertEquals(1536,result.writtenQuartCells());assertEquals(nativeReads,reads.get(),"Must retain complete native source-query count");
            retained(semantic,old);cells+=compare(nativeChunk,semantic,ids);chunks++;
            if(scene!=0)for(int x=0;x<4;x++)for(int z=0;z<4;z++){
               Set<String> column=new HashSet<>();for(var section:nativeChunk.getSections())for(int y=0;y<4;y++)column.add(section.getNoiseBiome(x,y,z).unwrapKey().orElseThrow().location().toString());if(column.size()>1)verticalColumns++;}
         }
      }
      assertEquals(18,chunks);assertEquals(27648,cells);assertTrue(verticalColumns>0,"Cave-enabled native source must actually vary with Y");
      assertTrue(ids.stream().anyMatch(id->id.contains("caves")||id.contains("deep_dark")),ids.toString());
      System.out.println("NATIVE_CUBE_BIOME_VOLUME chunks="+chunks+" quartCells="+cells+" verticallyVaryingColumns="+verticalColumns+" biomeIds="+ids+" mismatchQuarts=0 blockContainersRetained=true");
   }
   @Test void fixedSphereVolumesMatchNativeAcrossFacesBiomesAndNegativeEngineCoordinates() throws Exception {
      int chunks=0,cells=0;Set<String> ids=new TreeSet<>();
      for(var face:CubeNetLayout.Face.values())for(var key:List.of(Biomes.PLAINS,Biomes.DESERT,Biomes.FROZEN_OCEAN)){
         AtomicInteger terrain=new AtomicInteger();var gen=SphereFaceNativeBiomeKernelBridgeTest.generator(face.index(),registry.getHolderOrThrow(key),terrain,new SphereChunkSampler.SurfaceData(100,10));gen.setWorldSeed(42);
         var g=SphereFaceNativeBiomeKernelBridgeTest.generation(gen,42);var pos=new ChunkPos(17,-23);
         var request=request(g,pos,(x,z)->SphereFaceNativeBiomeKernelBridgeTest.coordinate(gen,x,z));
         var nativeChunk=chunk(pos,g.minY(),g.maxYExclusive()-g.minY());var semantic=chunk(pos,g.minY(),g.maxYExclusive()-g.minY());var old=semantic.getSections().clone();
         nativeFill(gen,nativeChunk);var result=new TellusNativeBiomeVolumeWriter().write(true,semantic,request,gen.semanticBiomeVolumePlanner(),registry);
         assertEquals(WriteStatus.WRITTEN,result.status());assertEquals(1536,result.writtenQuartCells());retained(semantic,old);cells+=compare(nativeChunk,semantic,ids);assertEquals(0,terrain.get());chunks++;
      }
      assertEquals(18,chunks);assertEquals(27648,cells);assertEquals(3,ids.size());System.out.println("NATIVE_SPHERE_BIOME_VOLUME chunks="+chunks+" quartCells="+cells+" terrainReads=0 mismatchQuarts=0");
   }
   @Test void earthFixedNativeGeneratorVolumeRetainsActualQuartOrderAndFullHeight() throws Exception {
      var settings=EarthGeneratorSettings.DEFAULT;var source=new FixedBiomeSource(registry.getHolderOrThrow(Biomes.DESERT));var gen=new EarthChunkGenerator(source,settings);
      var g=new TellusGenerationContext(gen.worldSeed(),settings.worldScale(),gen.getSeaLevel(),gen.getMinY(),gen.getMinY()+gen.getGenDepth());var pos=new ChunkPos(-7,9);var model=SphereWorldModel.forWorldScale(g.worldScale());
      var request=request(g,pos,(x,z)->{var geo=new SphereWorldModel.GeoPoint(EarthProjection.blockZToLat(z+0.5,g.worldScale()),EarthProjection.blockXToLongitude(x+0.5,g.worldScale()),0);return TellusCoordinateContextFactory.fromGeo(model,geo,new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo,g.worldScale())));});
      var nativeChunk=chunk(pos,g.minY(),g.maxYExclusive()-g.minY());var semantic=chunk(pos,g.minY(),g.maxYExclusive()-g.minY());nativeFill(gen,nativeChunk);
      var result=new TellusNativeBiomeVolumeWriter().write(true,semantic,request,gen.semanticBiomeVolumePlanner(),registry);assertEquals(WriteStatus.WRITTEN,result.status());Set<String> ids=new TreeSet<>();int cells=compare(nativeChunk,semantic,ids);assertEquals(Set.of("minecraft:desert"),ids);
      System.out.println("NATIVE_EARTH_FIXED_BIOME_VOLUME chunks=1 quartCells="+cells+" mismatchQuarts=0 realEarthClassifier=false");
   }
   @Test void nativeVolumeFactoriesStayColdForNonblockingInvalidSeedAndWrongOwner() throws Exception {
      AtomicInteger reads=new AtomicInteger();var source=CubeNetNativeBaseProviderPipelineTest.source(cubeSettings(1),reads);var gen=CubeNetNativeBaseProviderPipelineTest.generator(source,(point,resolution)->new SphereChunkSampler.SurfaceData(100,10),false,42);
      var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);var c=CubeNetNativeBaseProviderPipelineTest.coordinate(gen,CubeNetLayout.Face.POS_X,1,2);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"cold"));var pos=new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));
      var exact=request(g,pos,(x,z)->TellusCoordinateContextFactory.fromCubeNetCanonical(gen.model(),gen.adapter().layout(),gen.adapter().layout().fromPlanar(new CubeNetLayout.PlanarPoint(x+0.5,z+0.5))));
      var queued=new TellusBiomeVolumePlan.Request(g,pos.x,pos.z,exact.columns(),SamplingResolution.nonBlocking(1024));assertEquals(DecisionStatus.QUEUED,gen.semanticBiomeVolumePlanner().plan(queued).status());assertEquals(0,reads.get());
      var wrongOwner=request(g,pos,(x,z)->{var geo=new SphereWorldModel.GeoPoint(EarthProjection.blockZToLat(z+0.5,g.worldScale()),EarthProjection.blockXToLongitude(x+0.5,g.worldScale()),0);return TellusCoordinateContextFactory.fromGeo(gen.model(),geo,new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo,g.worldScale())));});
      assertThrows(IllegalArgumentException.class,()->gen.semanticBiomeVolumePlanner().plan(wrongOwner));assertEquals(0,reads.get());
      CubeNetNativeBaseProviderPipelineTest.set(gen,"worldSeed",43L);assertThrows(IllegalArgumentException.class,()->gen.semanticBiomeVolumePlanner().plan(exact));assertEquals(0,reads.get());
      CubeNetNativeBaseProviderPipelineTest.set(gen,"worldSeed",42L);source.beginStructureBiomeQueries();try{assertThrows(IllegalStateException.class,()->gen.semanticBiomeVolumePlanner().plan(exact));assertEquals(0,reads.get());}finally{source.endStructureBiomeQueries();}
      System.out.println("NATIVE_BIOME_VOLUME_GUARDS nonblockingSeedStructureCold=true nativeLifecycleUnchanged=true");
   }
}
