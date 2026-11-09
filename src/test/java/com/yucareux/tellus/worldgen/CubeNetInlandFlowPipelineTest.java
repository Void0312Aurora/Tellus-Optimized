package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.osm.*;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.write.*;
import com.yucareux.tellus.worldgen.sample.*;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real native states/marker arrays and genuine local geodetic hydrology; not live GIS/server or persistence acceptance. */
class CubeNetInlandFlowPipelineTest {
   @TempDir static Path root;
   static final int MIN=-64,HEIGHT=384;
   @BeforeAll static void initialize() throws Exception {SphereFaceNativeBiomeKernelBridgeTest.directory=root;SphereFaceNativeBiomeKernelBridgeTest.bootstrap();}
   @AfterAll static void cleanup(){SphereFaceNativeBiomeKernelBridgeTest.restore();}
   static CubeNetChunkGenerator generator(double scale,SphereChunkSampler.PointSampler source,CubeNetInlandWaterResolver resolver) throws Exception {
      var fixed=new FixedBiomeSource(SphereFaceNativeBiomeKernelBridgeTest.biomes.getOrThrow(Biomes.RIVER));
      var ctor=CubeNetChunkGenerator.class.getDeclaredConstructor(BiomeSource.class,FixedBiomeSource.class,double.class,int.class,int.class,int.class,
         SphereSingularityPolicy.Config.class,SphereChunkSampler.PointSampler.class,int.class,int.class,int.class,boolean.class);ctor.setAccessible(true);
      var gen=ctor.newInstance(fixed,fixed,scale,64,MIN,HEIGHT,SphereSingularityPolicy.DEFAULT_CONFIG,source,5,1,1,true);
      CubeNetNativeBaseProviderPipelineTest.set(gen,"inlandWaterResolver",resolver);CubeNetNativeBaseProviderPipelineTest.set(gen,"worldSeed",42L);
      ((SphereTerrainMaterializer)CubeNetNativeBaseProviderPipelineTest.field(gen,"materializer")).setWorldSeed(42);
      for(var face:(SphereFaceChunkGenerator[])CubeNetNativeBaseProviderPipelineTest.field(gen,"faces"))face.setWorldSeed(42);return gen;
   }
   static Object pipeline(CubeNetChunkGenerator gen,TellusFeaturePlanner features) throws Exception {
      String v=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211",prefix="com.yucareux.tellus.worldgen.write.TellusMinecraft"+v;
      var mapper=Class.forName(prefix+"SemanticMapper").getConstructor().newInstance();
      return Class.forName(prefix+"CanonicalPipeline").getConstructor(boolean.class,TellusSampleProvider.class,TellusColumnDecisionEngine.class,
         TellusFeaturePlanner.class,TellusColumnMaterializer.class,SemanticMapper.class).newInstance(true,gen.semanticBaseSampleProvider(),
         TellusColumnDecisionEngine.withSphericalProductionRules(gen.semanticBaseColumnKernel()),features,new TellusColumnMaterializer(),mapper);
   }
   static TellusCoordinateContext coordinate(CubeNetChunkGenerator gen,double earthX,double earthZ){
      var block=gen.model().blockForGeo(new SphereWorldModel.GeoPoint(EarthProjection.blockZToLat(earthZ,gen.worldScale()),
         EarthProjection.blockXToLongitude(earthX,gen.worldScale()),0));
      return TellusCoordinateContextFactory.fromCubeNetCanonical(gen.model(),gen.adapter().layout(),gen.adapter().facePoint(block));
   }
   static TellusCoordinateContext coordinate(CubeNetChunkGenerator gen,ChunkPos pos,int x,int z){
      var facePoint=gen.adapter().layout().fromPlanar(new CubeNetLayout.PlanarPoint(pos.getMinBlockX()+x+0.5,pos.getMinBlockZ()+z+0.5));
      return TellusCoordinateContextFactory.fromCubeNetCanonical(gen.model(),gen.adapter().layout(),facePoint);
   }
   static SphereChunkSampler.SurfaceData raw(SphereWorldModel.GeoPoint point){
      double z=EarthProjection.latToBlockZ(point.latitude(),1.0);return new SphereChunkSampler.SurfaceData((z<460?180:120)-64,80);
   }
   static CubeNetInlandWaterResolver hydrology(SphereWorldModel model){
      var features=List.of(InlandHydrologyRegressionTest.feature(11,false,OsmWaterKind.RIVER,280,220,744,220,744,460,280,460,280,220),
         InlandHydrologyRegressionTest.feature(12,false,OsmWaterKind.RIVER,280,520,744,520,744,800,280,800,280,520),
         InlandHydrologyRegressionTest.feature(13,false,OsmWaterKind.WATERFALL,280,460,744,460,744,520,280,520,280,460),
         InlandHydrologyRegressionTest.feature(14,true,OsmWaterKind.RIVER,512,230,512,790));
      CubeNetInlandWaterResolver.GeodeticWaterInput input=new CubeNetInlandWaterResolver.GeodeticWaterInput(){
         TellusOsmWaterSource.WaterQueryResult result(List<OsmWaterFeature> selected){return new TellusOsmWaterSource.WaterQueryResult(selected,TellusOsmWaterSource.CoverageStatus.COMPLETE,14);}
         public TellusOsmWaterSource.WaterQueryResult waterForGeoAreaWithStatus(com.yucareux.tellus.world.data.geo.GeodeticBounds bounds,double scale,OsmQueryMode mode){return result(features);}
         public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed,double scale){return result(features.stream().filter(f->f.featureId()==seed.featureId()).toList());}
         public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed,double scale){return connectedWaterBody(seed,scale);}
      };
      return new CubeNetInlandWaterResolver(model,64,new SphereChunkSampler(model,(point,resolution)->raw(point)),input);
   }
   static CubeNetInlandWaterResolver.Sample observed(CubeNetChunkGenerator gen,CubeNetInlandWaterResolver resolver,TellusCoordinateContext c) throws Exception {
      var owner=(CubeNetCanonicalOwnerView)c.owner();var faces=(SphereFaceChunkGenerator[])CubeNetNativeBaseProviderPipelineTest.field(gen,"faces");
      var surface=faces[owner.block().face()].terrainPoint(owner.block(),1).data();
      if(!surface.inlandWater())return CubeNetInlandWaterResolver.Sample.dry();
      var point=gen.model().exactSurfacePoint(owner.block().face(),owner.block().blockX()+0.5,owner.block().blockZ()+0.5);return resolver.sample(point,surface);
   }
   static void seed(ProtoChunk chunk){chunk.getPostProcessing()[0]=new ShortArrayList(new short[]{7,7,99});chunk.setUnsaved(false);}
   static void equalMarkers(ProtoChunk a,ProtoChunk b){
      var aa=a.getPostProcessing();var bb=b.getPostProcessing();assertEquals(aa.length,bb.length);
      for(int i=0;i<aa.length;i++){assertEquals(aa[i]==null,bb[i]==null,"null marker slot="+i);if(aa[i]!=null)assertArrayEquals(aa[i].toShortArray(),bb[i].toShortArray(),"marker slot="+i);}
   }
   @Test void genuineRiverAndFallingCurtainMatchWholeNativeChunksAndOrderedMarkerArrays() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         var model=SphereWorldModel.forWorldScale(1.0);var resolver=hydrology(model);
         var gen=generator(1.0,(point,resolution)->resolver.apply(point,raw(point)),resolver);
         var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);
         Map<Boolean,ChunkPos> selected=new HashMap<>();
         for(int z=420;z<=550&&selected.size()<2;z++){
            var c=coordinate(gen,512.5,z+0.5);var flow=observed(gen,resolver,c);
            if(flow.hasWater()&&flow.flowLevel()>0){var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"search"));selected.putIfAbsent(flow.falling(),new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)));}
         }
         assertEquals(Set.of(false,true),selected.keySet(),"Need genuine positive ordinary flow AND falling curtain, not resolved sample injection");
         var pipeline=pipeline(gen,gen.semanticInlandFlowFeaturePlanner());int columns=0,states=0,flowCells=0;Set<Integer> levels=new TreeSet<>();
         for(var pos:new LinkedHashSet<>(selected.values())){
            var nativeChunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var semantic=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);seed(nativeChunk);seed(semantic);
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){
               var c=coordinate(gen,pos,x,z);var r=CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.exact(1));
               var result=SphereFaceNativeProviderPipelineTest.write(pipeline,semantic,WriteContext.canonical(c,g,"genuine-flow"),g,c,r);assertEquals(WriteStatus.WRITTEN,result.status());
               if(result.transientPostProcessing())assertEquals(StorageTransactionStatus.NO_COMMIT_FALLBACK,new StorageTransactionBoundary().prepare(WriteContext.canonical(c,g,"no-flat-save"),result,StorageCommitPolicy.canonicalDefault(),"r54").status());columns++;
            }
            gen.fillFromNoise(Runnable::run,null,null,null,nativeChunk).join();equalMarkers(nativeChunk,semantic);
            for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int y=MIN;y<MIN+HEIGHT;y++){
               var point=new BlockPos(pos.getMinBlockX()+x,y,pos.getMinBlockZ()+z);var state=nativeChunk.getBlockState(point);assertEquals(state,semantic.getBlockState(point),"native flow block="+point);
               if(state.is(Blocks.WATER)&&state.getValue(LiquidBlock.LEVEL)>0){flowCells++;levels.add(state.getValue(LiquidBlock.LEVEL));}states++;
            }
         }
         assertTrue(columns>=256);assertTrue(flowCells>0);assertTrue(levels.contains(8),"Genuine falling curtain level8 must execute");
         System.out.println("CUBE_INLAND_FLOW_PIPELINE chunks="+new LinkedHashSet<>(selected.values()).size()+" columns="+columns+" nativeBlockStates="+states+" flowingCells="+flowCells+" levels="+levels+" mismatchBlocks=0 orderedMarkerArraysEqual=true");
      }
   }
   @Test void allEightExplicitLevelsKeepNativePropertiesAndAppendRealMarkers() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         var gen=generator(1000,(point,resolution)->new SphereChunkSampler.SurfaceData(100,10),null);var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);
         var c=CubeNetNativeBaseProviderPipelineTest.coordinate(gen,CubeNetLayout.Face.POS_X,1,2);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"levels"));
         var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)));seed(target);
         int cells=0;
         for(int level=1;level<=8;level++){
            int exact=level;var planner=(TellusFeaturePlanner)(generation,coordinate,column,sample)->FeaturePlan.available(FeaturePlacement.canonicalFlow(coordinate,"level"+exact,70+exact*3,3,exact));
            var result=SphereFaceNativeProviderPipelineTest.write(pipeline(gen,planner),target,WriteContext.canonical(c,g,"levels"),g,c,CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.exact(1000)));
            assertEquals(WriteStatus.WRITTEN,result.status());assertTrue(result.transientPostProcessing());var markers=new TellusNativePostProcessing(target);
            for(int i=0;i<3;i++){int y=70+level*3+i;var state=target.getBlockState(new BlockPos(p.x(),y,p.z()));assertEquals(level,state.getValue(LiquidBlock.LEVEL));assertTrue(TellusNativeSemanticMaterials.material(state).isEmpty());assertEquals(1,markers.occurrences(new TellusCompositeColumnWriter.Cell(p.x(),y,p.z())));cells++;}
         }
         assertEquals(24,cells);System.out.println("CUBE_FLOW_EXACT_STATES levels=8 cells=24 markers=24 defaultReverseMapperStillRejects=true");
      }
   }
   @Test void nativeMarkerSnapshotRestoresOriginalReferencesNullSlotsOrderDuplicatesAndDirtyFlag() throws Exception {
      var chunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(0,0));seed(chunk);ShortList[] originals=chunk.getPostProcessing().clone();
      var markers=new TellusNativePostProcessing(chunk);Object snapshot=markers.snapshot();var cell=new TellusCompositeColumnWriter.Cell(1,20,2);
      markers.mark(cell);markers.mark(cell);assertEquals(2,markers.occurrences(cell));chunk.getPostProcessing()[0].add((short)123);chunk.setUnsaved(true);markers.restore(snapshot);
      for(int i=0;i<originals.length;i++)assertSame(originals[i],chunk.getPostProcessing()[i]);assertArrayEquals(new short[]{7,7,99},chunk.getPostProcessing()[0].toShortArray());assertFalse(chunk.isUnsaved());
      assertThrows(IllegalArgumentException.class,()->new TellusNativePostProcessing(chunk).restore(snapshot));
   }
   @Test void actualProtoChunkRollsBackBlocksMarkerAppendAndDirtyFailureTogether() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         var gen=generator(1000,(point,resolution)->new SphereChunkSampler.SurfaceData(100,10),null);var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);
         var c=CubeNetNativeBaseProviderPipelineTest.coordinate(gen,CubeNetLayout.Face.POS_X,1,2);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"fault"));
         var r=CubeNetNativeBaseProviderPipelineTest.request(g,c,SamplingResolution.exact(1000));var sample=gen.semanticBaseSampleProvider().sample(r);var decision=gen.semanticBaseColumnKernel().decide(g,c,sample);
         var features=FeaturePlan.available(FeaturePlacement.canonicalFlow(c,"fault",71,3,8));
         String v=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211";
         @SuppressWarnings("unchecked")var mapper=(SemanticMapper<BlockState>)Class.forName("com.yucareux.tellus.worldgen.write.TellusMinecraft"+v+"SemanticMapper").getConstructor().newInstance();
         for(int mode=0;mode<3;mode++){
            int failureMode=mode;var chunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)));seed(chunk);
            chunk.setBlockState(new BlockPos(p.x(),71,p.z()),Blocks.GLASS.defaultBlockState(),false);chunk.setUnsaved(false);var original=new ArrayList<BlockState>();for(int y=MIN;y<MIN+HEIGHT;y++)original.add(chunk.getBlockState(new BlockPos(p.x(),y,p.z())));
            ShortList[] originalLists=chunk.getPostProcessing().clone();var post=new TellusNativePostProcessing(chunk);AtomicInteger marks=new AtomicInteger();
            var target=new TellusCompositeColumnWriter.Target<BlockState>(){
               public int chunkX(){return chunk.getPos().x;}public int chunkZ(){return chunk.getPos().z;}public int minY(){return MIN;}public int maxYExclusive(){return MIN+HEIGHT;}
               public BlockState read(TellusCompositeColumnWriter.Cell cell){return chunk.getBlockState(new BlockPos(cell.x(),cell.y(),cell.z()));}
               public void write(TellusCompositeColumnWriter.Cell cell,BlockState state){chunk.setBlockState(new BlockPos(cell.x(),cell.y(),cell.z()),state,false);}
               public boolean isAir(BlockState state){return state.isAir();}
               public void markDirty(){chunk.setUnsaved(true);if(failureMode==2)throw new IllegalStateException("dirty failure");}
               public TellusCompositeColumnWriter.PostProcessing postProcessing(){return new TellusCompositeColumnWriter.PostProcessing(){
                  public Object snapshot(){return post.snapshot();}public int occurrences(TellusCompositeColumnWriter.Cell cell){return post.occurrences(cell);}
                  public void mark(TellusCompositeColumnWriter.Cell cell){if(failureMode==0)return;post.mark(cell);if(failureMode==1&&marks.incrementAndGet()==2)throw new IllegalStateException("after append");}
                  public void restore(Object snapshot){post.restore(snapshot);}
               };}
            };
            var result=new TellusCompositeColumnWriter<BlockState>(new TellusColumnMaterializer()).write(target,WriteContext.canonical(c,g,"fault"),decision,features,mapper);
            assertEquals(WriteStatus.NO_WRITE_INVALID,result.status());for(int y=MIN;y<MIN+HEIGHT;y++)assertEquals(original.get(y-MIN),chunk.getBlockState(new BlockPos(p.x(),y,p.z())));
            for(int i=0;i<originalLists.length;i++)assertSame(originalLists[i],chunk.getPostProcessing()[i]);assertArrayEquals(new short[]{7,7,99},chunk.getPostProcessing()[0].toShortArray());assertFalse(chunk.isUnsaved());
         }
      }
   }
   @Test void legacyFeatureOnlySinkDoesNotSilentlyDropFlowPropertiesOrMarkers() throws Exception {
      var gen=generator(1000,(point,resolution)->new SphereChunkSampler.SurfaceData(100,10),null);var g=CubeNetNativeBaseProviderPipelineTest.generation(gen,42);
      var c=CubeNetNativeBaseProviderPipelineTest.coordinate(gen,CubeNetLayout.Face.POS_X,1,2);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"legacy"));
      var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16)));String v=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211";
      var sink=Class.forName("com.yucareux.tellus.worldgen.feature.TellusMinecraft"+v+"FeaturePlacementSink").getConstructor(ChunkAccess.class).newInstance(target);
      var features=FeaturePlan.available(FeaturePlacement.canonicalFlow(c,"legacy",71,1,1));var mapper=Class.forName("com.yucareux.tellus.worldgen.write.TellusMinecraft"+v+"SemanticMapper").getConstructor().newInstance();
      var result=(FeaturePlacementResult<?>)sink.getClass().getMethod("place",WriteContext.class,FeaturePlan.class,SemanticMapper.class).invoke(sink,WriteContext.canonical(c,g,"legacy"),features,mapper);
      assertNotEquals(FeaturePlacementStatus.WRITTEN,result.status());SphereFaceNativeProviderPipelineTest.allAir(target);for(var list:target.getPostProcessing())assertNull(list);
   }
}
