package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** One genuinely mixed native flow/badlands chunk, explicit batch failure atomicity and participating writer exclusion. */
class CubeNetComposedChunkBatchTest {
   @TempDir static Path root;
   static final int MIN=-64,HEIGHT=384;
   @BeforeAll static void initialize() throws Exception {CubeNetBadlandsFeaturePipelineTest.root=root;CubeNetBadlandsFeaturePipelineTest.initialize();}
   @AfterAll static void cleanup(){CubeNetBadlandsFeaturePipelineTest.cleanup();}
   record Scene(CubeNetChunkGenerator generator,ChunkPos pos,int selectedBiomeSeed) { }
   static SphereChunkSampler.SurfaceData raw(SphereWorldModel.GeoPoint point,double split){
      var base=CubeNetInlandFlowPipelineTest.raw(point);boolean water=EarthProjection.longitudeToBlockX(point.longitude(),1)<split;
      return new SphereChunkSampler.SurfaceData(base.elevationMeters(),water?80:10,Double.NaN,SphereChunkSampler.SurfaceData.WaterKind.NONE,null,
         new TerrainSlopePolicy.Neighbourhood(new TerrainSlopePolicy.Gradient(base.elevationMeters(),27,0,0),27));
   }
   static CubeNetBiomeSource source(int seed,SphereChunkSampler.PointSampler sampler){
      var json=CubeNetBiomeSource.Settings.CODEC.encodeStart(JsonOps.INSTANCE,CubeNetNativeBaseProviderPipelineTest.settings(false)).result().orElseThrow().getAsJsonObject();
      json.addProperty("random_biomes",true);json.addProperty("random_biome_density",0.4);json.addProperty("random_biome_seed",seed);
      json.add("random_biome_ids",JsonOps.INSTANCE.createList(java.util.stream.Stream.of(JsonOps.INSTANCE.createString("minecraft:badlands"))));
      var settings=CubeNetBiomeSource.Settings.CODEC.parse(JsonOps.INSTANCE,json).result().orElseThrow();
      return new CubeNetBiomeSource(CubeNetBadlandsFeaturePipelineTest.tagged,1,5,SphereSingularityPolicy.DEFAULT_CONFIG,
         new FixedBiomeSource(CubeNetBadlandsFeaturePipelineTest.tagged.getOrThrow(Biomes.PLAINS)),sampler,
         (point,scale,water)->new CubeNetBiomeSource.ClimateData("Cfa",null),(point,scale)->CubeNetBiomeSource.WaterData.dry(),settings);
   }
   static CubeNetChunkGenerator generator(CubeNetBiomeSource source,SphereChunkSampler.PointSampler sampler,CubeNetInlandWaterResolver resolver) throws Exception {
      var fixed=source.fallback();var ctor=CubeNetChunkGenerator.class.getDeclaredConstructor(BiomeSource.class,FixedBiomeSource.class,double.class,int.class,int.class,int.class,
         SphereSingularityPolicy.Config.class,SphereChunkSampler.PointSampler.class,int.class,int.class,int.class,boolean.class);ctor.setAccessible(true);
      var gen=ctor.newInstance(source,fixed,1.0,64,MIN,HEIGHT,SphereSingularityPolicy.DEFAULT_CONFIG,sampler,5,1,1,true);
      CubeNetNativeBaseProviderPipelineTest.set(gen,"inlandWaterResolver",resolver);CubeNetNativeBaseProviderPipelineTest.set(gen,"worldSeed",42L);
      ((SphereTerrainMaterializer)CubeNetNativeBaseProviderPipelineTest.field(gen,"materializer")).setWorldSeed(42);
      for(var face:(SphereFaceChunkGenerator[])CubeNetNativeBaseProviderPipelineTest.field(gen,"faces"))face.setWorldSeed(42);return gen;
   }
   static Scene scene() throws Exception {
      var model=SphereWorldModel.forWorldScale(1);var resolver=CubeNetInlandFlowPipelineTest.hydrology(model);
      var probe=CubeNetInlandFlowPipelineTest.generator(1,(point,resolution)->resolver.apply(point,raw(point,Double.POSITIVE_INFINITY)),resolver);
      ChunkPos selected=null;
      for(int z=445;z<=480&&selected==null;z++){
         var c=CubeNetInlandFlowPipelineTest.coordinate(probe,512.5,z+0.5);var observed=CubeNetInlandFlowPipelineTest.observed(probe,resolver,c);
         if(observed.hasWater()&&observed.flowLevel()>0&&observed.falling()){
            var g=CubeNetNativeBaseProviderPipelineTest.generation(probe,42);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"locate-fall"));selected=new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));}
      }
      assertNotNull(selected,"Bounded true hydrology search must find a falling curtain");
      double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;TellusCoordinateContext dry=null;
      for(int z=0;z<16;z++)for(int x=0;x<16;x++){
         var c=CubeNetInlandFlowPipelineTest.coordinate(probe,selected,x,z);double earth=EarthProjection.longitudeToBlockX(c.geo().longitude(),1);
         min=Math.min(min,earth);if(earth>max){max=earth;dry=c;}}
      double split=(min+max)/2;assertTrue(max>min+4,"Need nontrivial wet/dry source split within one actual chunk");
      SphereChunkSampler.PointSampler sampler=(point,resolution)->resolver.apply(point,raw(point,split));
      var dg=CubeNetNativeBaseProviderPipelineTest.generation(probe,42);var dp=CanonicalColumnPosition.resolve(WriteContext.canonical(dry,dg,"dry-tag"));
      for(int seed=0;seed<64;seed++){
         var source=source(seed,sampler);
         if(source.getNoiseBiome(QuartPos.fromBlock(dp.x()),32,QuartPos.fromBlock(dp.z()),Climate.empty()).is(BiomeTags.IS_BADLANDS))return new Scene(generator(source,sampler,resolver),selected,seed);
      }
      throw new AssertionError("No actual tagged dry-land random patch in bounded64-seed fixture search");
   }
   static List<TellusNativeChunkBatch.Request> requests(Scene scene){
      var g=CubeNetNativeBaseProviderPipelineTest.generation(scene.generator(),42);List<TellusNativeChunkBatch.Request> requests=new ArrayList<>();
      for(int z=0;z<16;z++)for(int x=0;x<16;x++){
         var c=CubeNetInlandFlowPipelineTest.coordinate(scene.generator(),scene.pos(),x,z);
         requests.add(new TellusNativeChunkBatch.Request(WriteContext.canonical(c,g,"mixed-batch"),g,c,CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.exact(1))));}
      return List.copyOf(requests);
   }
   static Object pipeline(Scene scene,SemanticMapper<BlockState> mapper) throws Exception {
      String v=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211",prefix="com.yucareux.tellus.worldgen.write.TellusMinecraft"+v;
      if(mapper==null){@SuppressWarnings("unchecked")var nativeMapper=(SemanticMapper<BlockState>)Class.forName(prefix+"SemanticMapper").getConstructor().newInstance();mapper=nativeMapper;}
      var gen=scene.generator();return Class.forName(prefix+"CanonicalPipeline").getConstructor(boolean.class,TellusSampleProvider.class,TellusColumnDecisionEngine.class,TellusFeaturePlanner.class,TellusColumnMaterializer.class,SemanticMapper.class)
         .newInstance(true,gen.semanticBaseSampleProvider(),TellusColumnDecisionEngine.withSphericalProductionRules(gen.semanticBaseColumnKernel()),
            TellusFeaturePlanner.sequence(gen.semanticInlandFlowFeaturePlanner(),gen.semanticBadlandsFeaturePlanner()),new TellusColumnMaterializer(),mapper);
   }
   static TellusNativeChunkBatch.Result write(Object pipeline,ProtoChunk target,List<TellusNativeChunkBatch.Request> requests) throws Exception {
      try{return (TellusNativeChunkBatch.Result)pipeline.getClass().getMethod("writeChunk",ChunkAccess.class,List.class).invoke(pipeline,target,requests);}
      catch(java.lang.reflect.InvocationTargetException error){if(error.getCause() instanceof RuntimeException runtime)throw runtime;if(error.getCause() instanceof Error fatal)throw fatal;throw error;}
   }
   @Test void fullBatchComposesActualFlowAndTaggedBadlandsInTheSameNativeChunk() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         var scene=scene();var requests=requests(scene);var pipeline=pipeline(scene,null);int flowColumns=0,badlandsColumns=0,plannedFlowCells=0,plannedBands=0;
         for(var request:requests){var plan=(TellusColumnPlan)pipeline.getClass().getMethod("plan",TellusGenerationContext.class,TellusCoordinateContext.class,TellusSampleRequest.class).invoke(pipeline,request.generation(),request.coordinate(),request.request());
            assertEquals(TellusColumnPlan.Status.AVAILABLE,plan.status());var features=plan.featurePlan().orElseThrow();boolean flow=false,badlands=false;
            for(var placement:features.placements()){if(placement.flowState().isPresent()){flow=true;plannedFlowCells+=placement.height();}else{badlands=true;plannedBands+=placement.height();}}
            if(flow)flowColumns++;if(badlands)badlandsColumns++;assertFalse(flow&&badlands,"Raw wet badlands exclusion keeps native phases disjoint per column");}
         assertTrue(flowColumns>0&&badlandsColumns>0,"Both real native post-stages must execute in this SAME chunk");
         var nativeChunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(scene.pos());var semantic=SphereFaceNativeBiomeKernelBridgeTest.chunk(scene.pos());CubeNetInlandFlowPipelineTest.seed(nativeChunk);CubeNetInlandFlowPipelineTest.seed(semantic);
         var result=write(pipeline,semantic,requests);assertEquals(WriteStatus.WRITTEN,result.status());assertEquals(256,result.columns().size());
         scene.generator().fillFromNoise(Runnable::run,null,null,null,nativeChunk).join();CubeNetInlandFlowPipelineTest.equalMarkers(nativeChunk,semantic);
         int states=0,actualFlows=0,terra=0;
         for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int y=MIN;y<MIN+HEIGHT;y++){
            var pos=new BlockPos(scene.pos().getMinBlockX()+x,y,scene.pos().getMinBlockZ()+z);var nativeState=nativeChunk.getBlockState(pos);assertEquals(nativeState,semantic.getBlockState(pos),"mixed native state="+pos);
            if(nativeState.is(Blocks.WATER)&&nativeState.getValue(LiquidBlock.LEVEL)>0)actualFlows++;
            if(nativeState.is(Blocks.TERRACOTTA)||nativeState.is(Blocks.ORANGE_TERRACOTTA)||nativeState.is(Blocks.WHITE_TERRACOTTA))terra++;states++;}
         assertEquals(98304,states);assertEquals(plannedFlowCells,actualFlows);assertTrue(terra>0&&plannedBands>0);assertTrue(result.columns().stream().anyMatch(WriteResult::transientPostProcessing));
         System.out.println("CUBE_COMPOSED_CHUNK_BATCH columns=256 nativeBlockStates="+states+" flowColumns="+flowColumns+" flowCells="+actualFlows+" badlandsColumns="+badlandsColumns+" badlandsCells="+plannedBands+" selectedBiomeSeed="+scene.selectedBiomeSeed()+" mismatchBlocks=0 orderedMarkerArraysEqual=true");
      }
   }
   @Test void lateMixedBatchMappingFailureRestoresEarlierColumnsAndAllMetadata() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         var scene=scene();var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(scene.pos());CubeNetInlandFlowPipelineTest.seed(target);
         var originalLists=target.getPostProcessing().clone();AtomicInteger mapped=new AtomicInteger();AtomicBoolean prefixObserved=new AtomicBoolean();
         SemanticMapper<BlockState> failing=new SemanticMapper<>(){
            public BlockState map(SemanticMaterial material,int y,WriteContext context){
               if(mapped.incrementAndGet()==20000){prefixObserved.set(!target.getBlockState(new BlockPos(scene.pos().getMinBlockX(),0,scene.pos().getMinBlockZ())).isAir());throw new IllegalStateException("late mixed batch mapper sentinel");}return TellusNativeSemanticMaterials.state(material);}
            public BlockState mapFeature(FeaturePlacement placement,int y,WriteContext context){var state=map(placement.material(),y,context);return placement.flowState().map(flow->state.setValue(LiquidBlock.LEVEL,flow.level())).orElse(state);}
         };
         var result=write(pipeline(scene,failing),target,requests(scene));assertEquals(WriteStatus.NO_WRITE_INVALID,result.status());assertTrue(result.columns().isEmpty());assertTrue(prefixObserved.get(),"Failure must follow actual earlier-column mutations");
         SphereFaceNativeProviderPipelineTest.allAir(target);for(int i=0;i<originalLists.length;i++)assertSame(originalLists[i],target.getPostProcessing()[i]);assertArrayEquals(new short[]{7,7,99},target.getPostProcessing()[0].toShortArray());assertFalse(target.isUnsaved());
         try(var lease=TellusTargetWriteGuard.tryAcquire(target).orElseThrow()){lease.requireOwned(target);}
      }
   }
   @Test void allParticipatingNativeEntryPointsQueueWhileTheSameChunkLeaseIsHeld() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         AtomicInteger sampled=new AtomicInteger(),mapped=new AtomicInteger();var gen=CubeNetInlandFlowPipelineTest.generator(1000,(point,resolution)->new SphereChunkSampler.SurfaceData(100,10),null);
         var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);var c=CubeNetNativeBaseProviderPipelineTest.coordinate(gen,CubeNetLayout.Face.POS_X,1,2);var r=CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.exact(1000));
         var sample=gen.semanticBaseSampleProvider().sample(r);var decision=gen.semanticBaseColumnKernel().decide(g,c,sample);var context=WriteContext.canonical(c,g,"held");var p=CanonicalColumnPosition.resolve(context);
         var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)));
         var real=gen.semanticBaseSampleProvider();TellusSampleProvider tracked=new TellusSampleProvider(){public String providerId(){return real.providerId();}public TellusSample sample(TellusSampleRequest request){sampled.incrementAndGet();return real.sample(request);}};
         SemanticMapper<BlockState> mapper=(material,y,ctx)->{mapped.incrementAndGet();return TellusNativeSemanticMaterials.state(material);};
         String v=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211",prefix="com.yucareux.tellus.worldgen.write.TellusMinecraft"+v;
         var pipeline=Class.forName(prefix+"CanonicalPipeline").getConstructor(boolean.class,TellusSampleProvider.class,TellusColumnDecisionEngine.class,TellusFeaturePlanner.class,TellusColumnMaterializer.class,SemanticMapper.class)
            .newInstance(true,tracked,TellusColumnDecisionEngine.withSphericalProductionRules(gen.semanticBaseColumnKernel()),TellusFeaturePlanner.empty(),new TellusColumnMaterializer(),mapper);
         var placement=FeaturePlacement.canonical(FeatureKind.CUSTOM,"held",c,PlacementOperation.SET,PlacementConflictPolicy.OWNER_ONLY,SemanticMaterial.STONE,0,90,0,1,Map.of());
         try(var lease=TellusTargetWriteGuard.tryAcquire(target).orElseThrow()){
            assertEquals(WriteStatus.NO_WRITE_QUEUED,SphereFaceNativeProviderPipelineTest.write(pipeline,target,context,g,c,r).status());
            assertEquals(WriteStatus.NO_WRITE_QUEUED,write(pipeline,target,List.of(new TellusNativeChunkBatch.Request(context,g,c,r))).status());
            var composite=Class.forName(prefix+"CompositeColumnWriter").getConstructor(TellusColumnMaterializer.class).newInstance(new TellusColumnMaterializer());
            assertEquals(WriteStatus.NO_WRITE_QUEUED,((WriteResult<?>)composite.getClass().getMethod("write",ChunkAccess.class,WriteContext.class,ColumnDecision.class,FeaturePlan.class,SemanticMapper.class).invoke(composite,target,context,decision,FeaturePlan.empty("none"),mapper)).status());
            var column=Class.forName(prefix+"CanonicalColumnAdapter").getConstructor(TellusColumnMaterializer.class,SemanticMapper.class).newInstance(new TellusColumnMaterializer(),mapper);
            assertEquals(WriteStatus.NO_WRITE_QUEUED,((WriteResult<?>)column.getClass().getMethod("write",ChunkAccess.class,WriteContext.class,ColumnDecision.class).invoke(column,target,context,decision)).status());
            var sink=Class.forName("com.yucareux.tellus.worldgen.feature.TellusMinecraft"+v+"FeaturePlacementSink").getConstructor(ChunkAccess.class).newInstance(target);
            assertEquals(FeaturePlacementStatus.NO_WRITE_QUEUED,((FeaturePlacementResult<?>)sink.getClass().getMethod("place",WriteContext.class,FeaturePlan.class,SemanticMapper.class).invoke(sink,context,FeaturePlan.available(placement),mapper)).status());
            assertEquals(0,sampled.get());assertEquals(0,mapped.get());SphereFaceNativeProviderPipelineTest.allAir(target);assertFalse(target.isUnsaved());
         }
         assertEquals(WriteStatus.WRITTEN,SphereFaceNativeProviderPipelineTest.write(pipeline,target,context,g,c,r).status());assertTrue(sampled.get()>0&&mapped.get()>0);
         System.out.println("NATIVE_CHUNK_PARTICIPANTS guardedPaths=5 queuedBeforeSamplingOrMapping=true releaseAllowsRetry=true");
      }
   }
}
