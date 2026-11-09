package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual tagged native badlands and transactional terrain+overlay pipeline; flow properties and live GIS remain outside this stage. */
class CubeNetBadlandsFeaturePipelineTest {
   @TempDir static Path root;
   static final int MIN=-64,HEIGHT=384;
   static final double SCALE=1000.0;
   static HolderGetter<Biome> tagged;
   @BeforeAll static void initialize() throws Exception {
      SphereFaceNativeBiomeKernelBridgeTest.directory=root;SphereFaceNativeBiomeKernelBridgeTest.bootstrap();
      var mapped=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
      Map<ResourceKey<Biome>,Holder.Reference<Biome>> references=new HashMap<>();
      SphereFaceNativeBiomeKernelBridgeTest.biomes.listElements().forEach(reference->references.put(reference.key(),
         Registry.registerForHolder(mapped,reference.key(),reference.value())));
      mapped.freeze();mapped.bindTags(Map.of(BiomeTags.IS_BADLANDS,List.of(references.get(Biomes.BADLANDS),
         references.get(Biomes.ERODED_BADLANDS),references.get(Biomes.WOODED_BADLANDS))));
      tagged=new HolderGetter<>(){
         public Optional<Holder.Reference<Biome>> get(ResourceKey<Biome> key){return Optional.ofNullable(references.get(key));}
         public Optional<HolderSet.Named<Biome>> get(TagKey<Biome> tag){return Optional.empty();}
      };
      assertTrue(tagged.getOrThrow(Biomes.BADLANDS).is(BiomeTags.IS_BADLANDS),"Native tag branch must be real, not id-only or vacuous");
   }
   @AfterAll static void cleanup(){SphereFaceNativeBiomeKernelBridgeTest.restore();}
   static CubeNetBiomeSource source(boolean modified,AtomicInteger reads){
      var json=CubeNetBiomeSource.Settings.CODEC.encodeStart(JsonOps.INSTANCE,CubeNetNativeBaseProviderPipelineTest.settings(modified)).result().orElseThrow().getAsJsonObject();
      json.addProperty("random_biomes",true);json.addProperty("random_biome_density",0.4);json.add("random_biome_ids",JsonOps.INSTANCE.createList(java.util.stream.Stream.of(JsonOps.INSTANCE.createString("minecraft:badlands"))));
      var settings=CubeNetBiomeSource.Settings.CODEC.parse(JsonOps.INSTANCE,json).result().orElseThrow();
      return new CubeNetBiomeSource(tagged,SCALE,5,SphereSingularityPolicy.DEFAULT_CONFIG,
         new FixedBiomeSource(tagged.getOrThrow(Biomes.PLAINS)),(point,resolution)->{reads.incrementAndGet();return new SphereChunkSampler.SurfaceData(100,10);},
         (point,scale,water)->new CubeNetBiomeSource.ClimateData("Cfa",null),(point,scale)->CubeNetBiomeSource.WaterData.dry(),settings);
   }
   static TellusCoordinateContext coordinate(CubeNetChunkGenerator gen,CubeNetLayout.Face face,int faceX,int faceZ){
      return TellusCoordinateContextFactory.fromCubeNetCanonical(gen.model(),gen.adapter().layout(),new CubeNetLayout.FacePoint(face,faceX+0.5,faceZ+0.5));
   }
   static int[] badlandsChunk(CubeNetChunkGenerator gen,CubeNetLayout.Face face){
      int n=gen.model().config().faceBlocks(),step=Math.max(16,(n/16/16)*16);
      for(int j=0;j<8;j++)for(int i=0;i<8;i++){
         int[] origin={n/4/16*16+i*step,n/4/16*16+j*step};
         if(badlandsLocals(gen,face,origin).size()==4)return origin;
      }
      throw new AssertionError("No four real tagged badlands columns in bounded64-chunk face search: "+face);
   }
   static List<int[]> badlandsLocals(CubeNetChunkGenerator gen,CubeNetLayout.Face face,int[] origin){
      List<int[]> selected=new ArrayList<>();var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);
      for(int z=0;z<16;z++)for(int x=0;x<16;x++){
         var c=coordinate(gen,face,origin[0]+x,origin[1]+z);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"tag-search"));
         if(gen.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(p.x()),0,QuartPos.fromBlock(p.z()),Climate.empty()).is(BiomeTags.IS_BADLANDS))selected.add(new int[]{x,z});
         if(selected.size()==4)return selected;
      }
      return selected;
   }
   static SphereChunkSampler.SurfaceData data(int scene,boolean wet){
      double relief=scene==1?25000.0:Double.NaN;double slope=scene==2?27.0:Double.NaN;
      var neighbourhood=new TerrainSlopePolicy.Neighbourhood(new TerrainSlopePolicy.Gradient(5010.1,slope,0,0),relief);
      return new SphereChunkSampler.SurfaceData(5010.1,60,wet?6010.1:Double.NaN,
         wet?SphereChunkSampler.SurfaceData.WaterKind.INLAND:SphereChunkSampler.SurfaceData.WaterKind.NONE,null,neighbourhood);
   }
   static Object pipeline(CubeNetChunkGenerator gen,TellusFeaturePlanner features,SemanticMapper<BlockState> override) throws Exception {
      String v=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211",prefix="com.yucareux.tellus.worldgen.write.TellusMinecraft"+v;
      var mapper=override!=null?override:Class.forName(prefix+"SemanticMapper").getConstructor().newInstance();
      return Class.forName(prefix+"CanonicalPipeline").getConstructor(boolean.class,TellusSampleProvider.class,TellusColumnDecisionEngine.class,
         TellusFeaturePlanner.class,TellusColumnMaterializer.class,SemanticMapper.class).newInstance(true,gen.semanticBaseSampleProvider(),
         TellusColumnDecisionEngine.withSphericalProductionRules(gen.semanticBaseColumnKernel()),features,new TellusColumnMaterializer(),mapper);
   }
   static FeaturePlan plan(CubeNetChunkGenerator gen,TellusGenerationContext g,TellusCoordinateContext c){
      var request=CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.exact(SCALE));var sample=gen.semanticBaseSampleProvider().sample(request);
      var column=gen.semanticBaseColumnKernel().decide(g,c,sample);return gen.semanticBadlandsFeaturePlanner().plan(g,c,column,sample);
   }
   static List<SemanticColumnCell> frozenColumn(int min,int top,double scale,double relief,double slope,int x,int z){
      int blocks=Double.isFinite(relief)?Math.max(0,(int)Math.ceil(relief/Math.max(0.0001,scale))):0;
      if(blocks<3&&Double.isFinite(slope))blocks=Math.max(0,(int)Math.round(slope/9.0)*3);
      List<SemanticColumnCell> cells=new ArrayList<>();
      if(blocks<3){int material=BadlandsTerrainPolicy.plateauMaterialIndex(x,z);SemanticMaterial m=switch(material){
         case BadlandsTerrainPolicy.PLATEAU_TERRACOTTA,BadlandsTerrainPolicy.PLATEAU_BROWN_TERRACOTTA->SemanticMaterial.TERRACOTTA;
         case BadlandsTerrainPolicy.PLATEAU_COARSE_DIRT->SemanticMaterial.COARSE_DIRT;default->SemanticMaterial.RED_SAND;};cells.add(new SemanticColumnCell(top,m));return cells;}
      int bottom=Math.max(min+1,top-BadlandsTerrainPolicy.cliffBandDepth(3,blocks)+1);
      for(int y=top;y>=Math.min(top,bottom);y--)cells.add(new SemanticColumnCell(y,switch(BadlandsTerrainPolicy.bandMaterialIndex(x,z,y)){
         case BadlandsTerrainPolicy.ORANGE_TERRACOTTA->SemanticMaterial.ORANGE_TERRACOTTA;case BadlandsTerrainPolicy.YELLOW_TERRACOTTA->SemanticMaterial.YELLOW_TERRACOTTA;
         case BadlandsTerrainPolicy.BROWN_TERRACOTTA->SemanticMaterial.BROWN_TERRACOTTA;case BadlandsTerrainPolicy.RED_TERRACOTTA->SemanticMaterial.RED_TERRACOTTA;
         case BadlandsTerrainPolicy.LIGHT_GRAY_TERRACOTTA->SemanticMaterial.LIGHT_GRAY_TERRACOTTA;case BadlandsTerrainPolicy.WHITE_TERRACOTTA->SemanticMaterial.WHITE_TERRACOTTA;
         default->SemanticMaterial.TERRACOTTA;}));
      return cells;
   }
   @Test void actualTaggedBadlandsPostpassAndCompositePipelineMatchFullColumns() throws Exception {
      int columns=0,states=0,overlays=0,changedFromBase=0;Set<SemanticMaterial> colors=new HashSet<>();
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         for(var face:CubeNetLayout.Face.values())for(boolean modified:new boolean[]{false,true})for(int scene=0;scene<3;scene++){
            int scenario=scene;var gen=CubeNetNativeBaseProviderPipelineTest.generator(source(modified,new AtomicInteger()),(point,resolution)->data(scenario,false),false,42);
            var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);int[] origin=badlandsChunk(gen,face);
            var c0=coordinate(gen,face,origin[0]+1,origin[1]+2);var p0=CanonicalColumnPosition.resolve(WriteContext.canonical(c0,g,"chunk"));
            var pos=new ChunkPos(Math.floorDiv(p0.x(),16),Math.floorDiv(p0.z(),16));var nativeChunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);
            var semantic=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var baseOnly=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);
            var pipeline=pipeline(gen,gen.semanticBadlandsFeaturePlanner(),null);var base=CubeNetNativeBaseProviderPipelineTest.pipeline(true,gen);
            for(int[] local:badlandsLocals(gen,face,origin)){
               var c=coordinate(gen,face,origin[0]+local[0],origin[1]+local[1]);var r=CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.exact(SCALE));
               var features=plan(gen,g,c);assertEquals(FeaturePlanStatus.AVAILABLE,features.status());assertFalse(features.placements().isEmpty());
               var surface=data(scenario,false);int top=new SphereTerrainMaterializer(SCALE,g.seaLevel(),true,modified?1.7:1,modified?2.5:1,42).height(surface.elevationMeters());
               var owner=(CubeNetCanonicalOwnerView)c.owner();var point=gen.model().exactSurfacePoint(owner.block().face(),owner.block().blockX()+0.5,owner.block().blockZ()+0.5);
               int mx=(int)Math.floor(EarthProjection.longitudeToBlockX(point.longitude(),SCALE)),mz=(int)Math.floor(EarthProjection.latToBlockZ(point.latitude(),SCALE));
               var frozen=frozenColumn(MIN,top,SCALE,surface.neighbourhood().relief(),surface.neighbourhood().gradient().slopeDegrees(),mx,mz);
               assertEquals(frozen.size(),features.placements().size());
               for(int i=0;i<frozen.size();i++){var expected=frozen.get(i);var actual=features.placements().get(i);
                  assertEquals(expected.y(),actual.offsetY());assertEquals(expected.material(),actual.material());colors.add(actual.material());
                  assertEquals(0,actual.offsetX());assertEquals(0,actual.offsetZ());assertEquals(1,actual.height());assertEquals(PlacementConflictPolicy.OWNER_ONLY,actual.conflictPolicy());overlays++;}
               assertEquals(WriteStatus.WRITTEN,SphereFaceNativeProviderPipelineTest.write(pipeline,semantic,WriteContext.canonical(c,g,"badlands"),g,c,r).status());
               assertEquals(WriteStatus.WRITTEN,SphereFaceNativeProviderPipelineTest.write(base,baseOnly,WriteContext.canonical(c,g,"base"),g,c,r).status());columns++;
            }
            gen.fillFromNoise(Runnable::run,null,null,null,nativeChunk).join();
            for(int[] local:badlandsLocals(gen,face,origin)){var c=coordinate(gen,face,origin[0]+local[0],origin[1]+local[1]);
               var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"check"));for(int y=MIN;y<MIN+HEIGHT;y++){
                  var block=new BlockPos(p.x(),y,p.z());assertEquals(nativeChunk.getBlockState(block),semantic.getBlockState(block),"face="+face+" profile="+modified+" scene="+scene+" at="+block);
                  if(!baseOnly.getBlockState(block).equals(semantic.getBlockState(block)))changedFromBase++;states++;}}
         }
      }
      assertEquals(144,columns);assertEquals(55296,states);assertTrue(overlays>0);assertTrue(changedFromBase>0);assertTrue(colors.size()>=5);
      System.out.println("CUBE_BADLANDS_FEATURE_PIPELINE columns="+columns+" nativeBlockStates="+states+" overlayCells="+overlays+" changedFromBase="+changedFromBase+" mismatchBlocks=0");
   }
   @Test void allBandColorsAndPlateauMappingsMatchRetainedNativeVersionHelpers() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         var gen=CubeNetNativeBaseProviderPipelineTest.generator(source(false,new AtomicInteger()),(point,resolution)->data(1,false),false,42);
         Set<SemanticMaterial> materials=new HashSet<>();int cells=0;
         for(int y=-64;y<256;y++){
            var actual=TellusCubeBadlandsDecisionKernel.resolve(-2032,y,SCALE,3000,Double.NaN,713,-419).get(0);materials.add(actual.material());
            assertEquals(CubeNetNativeBaseProviderPipelineTest.invoke(gen,"cubeNetBadlandsBand",new Class<?>[]{int.class,int.class,int.class},713,-419,y),
               TellusNativeSemanticMaterials.state(actual.material()));cells++;
            var plateau=TellusCubeBadlandsDecisionKernel.resolve(-64,y,SCALE,0,Double.NaN,713+y,-419-y).get(0);
            assertEquals(CubeNetNativeBaseProviderPipelineTest.invoke(gen,"cubeNetBadlandsPlateauTop",new Class<?>[]{int.class,int.class},713+y,-419-y),
               TellusNativeSemanticMaterials.state(plateau.material()));cells++;
         }
         assertEquals(7,materials.size());assertEquals(640,cells);System.out.println("CUBE_BADLANDS_NATIVE_MAPPINGS cells=640 bandColors=7 mismatchStates=0");
      }
   }
   @Test void wetRawColumnSkipsBadlandsEvenWhenWaterIsDisabledAndTerminalRequestsStayCold() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         AtomicInteger reads=new AtomicInteger();var gen=CubeNetNativeBaseProviderPipelineTest.generator(source(true,new AtomicInteger()),
            (point,resolution)->{reads.incrementAndGet();return data(1,true);},false,42);
         var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);var c=CubeNetNativeBaseProviderPipelineTest.coordinate(gen,CubeNetLayout.Face.POS_X,1,2);
         var r=CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.nonBlocking(SCALE));var queued=TellusSample.queued(r,"fixture","r53","queued");
         var planner=gen.semanticBadlandsFeaturePlanner();assertEquals(FeaturePlanStatus.QUEUED,planner.plan(g,c,ColumnDecision.unavailable(c,DecisionStatus.QUEUED,"queued"),queued).status());
         assertEquals(0,reads.get());assertEquals(FeaturePlanStatus.EMPTY,plan(gen,g,c).status());assertFalse(gen.enableWater());
         var fixed=CubeNetNativeBaseProviderPipelineTest.generator(new FixedBiomeSource(SphereFaceNativeBiomeKernelBridgeTest.biomes.getOrThrow(Biomes.DESERT)),
            (point,resolution)->data(0,false),false,42);var fg=CubeNetNativeBaseProviderPipelineTest.generation(fixed,42);
         var fc=CubeNetNativeBaseProviderPipelineTest.coordinate(fixed,CubeNetLayout.Face.POS_X,1,2);assertEquals(FeaturePlanStatus.EMPTY,plan(fixed,fg,fc).status());
      }
   }
   @Test void overlayMappingFailureLeavesActualChunkUntouched() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         var gen=CubeNetNativeBaseProviderPipelineTest.generator(source(false,new AtomicInteger()),(point,resolution)->data(1,false),false,42);
         var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);int[] origin=badlandsChunk(gen,CubeNetLayout.Face.POS_X);
         var local=badlandsLocals(gen,CubeNetLayout.Face.POS_X,origin).get(0);
         var c=coordinate(gen,CubeNetLayout.Face.POS_X,origin[0]+local[0],origin[1]+local[1]);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"failure"));
         var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)));
         var features=plan(gen,g,c);var denied=features.placements().stream().map(FeaturePlacement::material).filter(material->material!=SemanticMaterial.TERRACOTTA).findFirst().orElseThrow();
         AtomicInteger calls=new AtomicInteger();SemanticMapper<BlockState> failing=(material,y,context)->{calls.incrementAndGet();if(material==denied)throw new IllegalStateException("overlay mapping sentinel");return TellusNativeSemanticMaterials.state(material);};
         var r=CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.exact(SCALE));
         assertEquals(WriteStatus.NO_WRITE_INVALID,SphereFaceNativeProviderPipelineTest.write(pipeline(gen,gen.semanticBadlandsFeaturePlanner(),failing),target,
            WriteContext.canonical(c,g,"failure"),g,c,r).status());assertTrue(calls.get()>0);SphereFaceNativeProviderPipelineTest.allAir(target);
      }
   }
   @Test void rawPostpassReadsFreshTerrainNotTheCachedBaseColumn() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         AtomicBoolean changed=new AtomicBoolean();var gen=CubeNetNativeBaseProviderPipelineTest.generator(source(false,new AtomicInteger()),
            (point,resolution)->data(1,false).withElevation(changed.get()?9010.1:1010.1),false,42);
         var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);int[] origin=badlandsChunk(gen,CubeNetLayout.Face.POS_X);
         var local=badlandsLocals(gen,CubeNetLayout.Face.POS_X,origin).get(0);
         var c=coordinate(gen,CubeNetLayout.Face.POS_X,origin[0]+local[0],origin[1]+local[1]);var r=CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.exact(SCALE));
         var sample=gen.semanticBaseSampleProvider().sample(r);var base=gen.semanticBaseColumnKernel().decide(g,c,sample);changed.set(true);
         var features=gen.semanticBadlandsFeaturePlanner().plan(g,c,base,sample);assertEquals(FeaturePlanStatus.AVAILABLE,features.status());
         assertTrue(features.placements().get(0).offsetY()>base.terrainY().orElseThrow(),"Raw post-pass can intentionally write above cached base terrain");
         var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"fresh"));var pos=new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));
         var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var nativeChunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);
         assertEquals(WriteStatus.WRITTEN,SphereFaceNativeProviderPipelineTest.write(pipeline(gen,gen.semanticBadlandsFeaturePlanner(),null),target,
            WriteContext.canonical(c,g,"fresh"),g,c,r).status());gen.fillFromNoise(Runnable::run,null,null,null,nativeChunk).join();
         for(int y=MIN;y<MIN+HEIGHT;y++)assertEquals(nativeChunk.getBlockState(new BlockPos(p.x(),y,p.z())),target.getBlockState(new BlockPos(p.x(),y,p.z())));
      }
   }
}
