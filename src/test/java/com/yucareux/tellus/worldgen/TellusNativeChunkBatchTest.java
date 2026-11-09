package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual ProtoChunk batch recovery and participating-target exclusion, not world persistence or external-writer isolation. */
class TellusNativeChunkBatchTest {
   @TempDir static Path root;
   static final int MIN=-64,MAX=320;
   static final SphereWorldModel MODEL=SphereWorldModel.forWorldScale(1);
   static final TellusGenerationContext GENERATION=new TellusGenerationContext(42,1,64,MIN,MAX);
   @BeforeAll static void initialize() throws Exception {SphereFaceNativeBiomeKernelBridgeTest.directory=root;SphereFaceNativeBiomeKernelBridgeTest.bootstrap();}
   @AfterAll static void cleanup(){SphereFaceNativeBiomeKernelBridgeTest.restore();}

   static TellusCoordinateContext coordinate(double x,double z){
      var point=new SphereWorldModel.GeoPoint(EarthProjection.blockZToLat(z,1),EarthProjection.blockXToLongitude(x,1),0);
      return TellusCoordinateContextFactory.fromGeo(MODEL,point,new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(point,1)));
   }
   static TellusNativeChunkBatch.Request entry(int x,int z){return entry(coordinate(x+0.25,z+0.25),GENERATION,false);}
   static TellusNativeChunkBatch.Request entry(TellusCoordinateContext coordinate,TellusGenerationContext generation,boolean nonBlocking){
      var request=TellusSampleRequest.fromContext(coordinate,SamplingPurpose.TERRAIN_COLUMN,
         nonBlocking?SamplingResolution.nonBlocking(1):SamplingResolution.exact(1),generation.geometry(),generation.worldSeed());
      return new TellusNativeChunkBatch.Request(WriteContext.canonical(coordinate,generation,"chunk-batch-test"),generation,coordinate,request);
   }
   static TellusSample sample(TellusNativeChunkBatch.Request entry){return new TellusSample(entry.request(),SampleStatus.AVAILABLE,
      new TellusElevationSample(SampleStatus.AVAILABLE,1),new TellusCoverSample(SampleStatus.AVAILABLE,10,10),TellusWaterSample.dry(),
      TellusSampleProvenance.snapshot("tellus:batch-tests","r55",false,"resolved fixture"));}
   static ColumnDecision decision(TellusNativeChunkBatch.Request entry,DecisionStatus status){return new ColumnDecision(entry.coordinate(),status,OptionalInt.of(1),
      new SurfaceDecision(SemanticMaterial.STONE,SemanticMaterial.STONE,0),WaterDecision.dry(),BiomeDecision.defaultBiome(),
      StrataDecision.stone(entry.generation().minY(),1),List.of("batch resolved test"));}
   static TellusColumnPlan plan(TellusNativeChunkBatch.Request entry,boolean flow){return TellusColumnPlan.planned(sample(entry),decision(entry,DecisionStatus.AVAILABLE),
      flow?FeaturePlan.available(FeaturePlacement.canonicalFlow(entry.coordinate(),"batch-flow",4,2,8)):FeaturePlan.empty("no flow"));}
   @SuppressWarnings("unchecked") static SemanticMapper<BlockState> mapper() throws Exception {
      String v=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211";
      return (SemanticMapper<BlockState>)Class.forName("com.yucareux.tellus.worldgen.write.TellusMinecraft"+v+"SemanticMapper").getConstructor().newInstance();
   }
   static TellusNativeChunkBatch batch() throws Exception {return new TellusNativeChunkBatch(new TellusColumnMaterializer(),mapper());}
   static ProtoChunk chunk() throws Exception {var chunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(0,0));
      chunk.getPostProcessing()[0]=new ShortArrayList(new short[]{7,7,99});
      chunk.setBlockState(new BlockPos(1,4,2),Blocks.GLASS.defaultBlockState(),false);
      chunk.setBlockState(new BlockPos(9,12,9),Blocks.BRICKS.defaultBlockState(),false);chunk.setUnsaved(false);return chunk;}
   private record Before(BlockState[] states,ShortList[] lists,short[][] contents,boolean dirty){ }
   static Before before(ProtoChunk chunk){
      BlockState[] states=new BlockState[(chunk.getMaxBuildHeight()-chunk.getMinBuildHeight())*256];
      for(int i=0;i<states.length;i++)states[i]=chunk.getBlockState(position(chunk,i));
      ShortList[] lists=chunk.getPostProcessing().clone();short[][] contents=new short[lists.length][];
      for(int i=0;i<lists.length;i++)if(lists[i]!=null)contents[i]=lists[i].toShortArray();
      return new Before(states,lists,contents,chunk.isUnsaved());
   }
   static BlockPos position(ProtoChunk chunk,int index){return new BlockPos(chunk.getPos().getMinBlockX()+(index&15),
      chunk.getMinBuildHeight()+(index>>>8),chunk.getPos().getMinBlockZ()+((index>>>4)&15));}
   static void restored(ProtoChunk chunk,Before before){
      for(int i=0;i<before.states().length;i++)assertEquals(before.states()[i],chunk.getBlockState(position(chunk,i)),"whole-chunk state index="+i);
      for(int i=0;i<before.lists().length;i++){assertSame(before.lists()[i],chunk.getPostProcessing()[i],"original marker-list reference="+i);
         if(before.contents()[i]!=null)assertArrayEquals(before.contents()[i],chunk.getPostProcessing()[i].toShortArray());}
      assertEquals(before.dirty(),chunk.isUnsaved());
   }
   static void released(ProtoChunk chunk){try(var lease=TellusTargetWriteGuard.tryAcquire(chunk).orElseThrow()){lease.requireOwned(chunk);}}
   static void stopped(TellusNativeChunkBatch.Result result,WriteStatus status){assertEquals(status,result.status());assertTrue(result.columns().isEmpty());}
   static List<TellusNativeChunkBatch.Request> two(){return List.of(entry(1,2),entry(2,2));}

   @Test void successfulTwoColumnFlowBatchRetainsResultsAndImmutableShape() throws Exception {
      var target=chunk();var result=batch().write(true,target,two(),entry->plan(entry,true));
      assertEquals(WriteStatus.WRITTEN,result.status());assertEquals(2,result.columns().size());assertTrue(target.isUnsaved());
      for(var row:result.columns()){assertEquals(WriteStatus.WRITTEN,row.status());assertTrue(row.transientPostProcessing());}
      for(int x:new int[]{1,2})for(int y:new int[]{4,5}){
         assertEquals(8,target.getBlockState(new BlockPos(x,y,2)).getValue(LiquidBlock.LEVEL));
         assertEquals(1,new TellusNativePostProcessing(target).occurrences(new TellusCompositeColumnWriter.Cell(x,y,2)));}
      assertArrayEquals(new short[]{7,7,99},target.getPostProcessing()[0].toShortArray());
      assertThrows(UnsupportedOperationException.class,()->result.columns().clear());
      assertThrows(UnsupportedOperationException.class,()->result.diagnostics().clear());released(target);
   }

   @Test void secondColumnMapperFailureRestoresEarlierBlocksMarkersReferencesAndDirty() throws Exception {
      var target=chunk();var initial=before(target);var nativeMapper=mapper();AtomicBoolean sawCommittedPrefix=new AtomicBoolean();
      SemanticMapper<BlockState> fail=new SemanticMapper<>(){
         public BlockState map(SemanticMaterial material,int y,WriteContext context){
            if(CanonicalColumnPosition.resolve(context).x()==2){sawCommittedPrefix.set(target.getBlockState(new BlockPos(1,4,2)).getValue(LiquidBlock.LEVEL)==8);
               throw new IllegalStateException("second-column mapper failure");}
            return nativeMapper.map(material,y,context);}
         public BlockState mapFeature(FeaturePlacement placement,int y,WriteContext context){return nativeMapper.mapFeature(placement,y,context);}
      };
      var result=new TellusNativeChunkBatch(new TellusColumnMaterializer(),fail).write(true,target,two(),entry->plan(entry,true));
      stopped(result,WriteStatus.NO_WRITE_INVALID);assertTrue(sawCommittedPrefix.get());restored(target,initial);released(target);
   }

   @Test void firstRowFeatureInAnotherPhysicalColumnIsIncludedInWholeChunkRollback() throws Exception {
      var target=chunk();var initial=before(target);var nativeMapper=mapper();
      AtomicBoolean otherColumnWritten=new AtomicBoolean();
      SemanticMapper<BlockState> fail=(material,y,context)->{if(CanonicalColumnPosition.resolve(context).x()==2){
         otherColumnWritten.set(target.getBlockState(new BlockPos(9,20,9)).is(Blocks.GOLD_ORE));throw new IllegalStateException("late mapper failure");}
         return nativeMapper.map(material,y,context);};
      var result=new TellusNativeChunkBatch(new TellusColumnMaterializer(),fail).write(true,target,two(),entry->{
         var placement=FeaturePlacement.canonical(FeatureKind.CUSTOM,"offset",entry.coordinate(),PlacementOperation.SET,PlacementConflictPolicy.OWNER_ONLY,
            SemanticMaterial.GOLD_ORE,8,20,7,1,Map.of());
         return TellusColumnPlan.planned(sample(entry),decision(entry,DecisionStatus.AVAILABLE),FeaturePlan.available(placement));});
      // Offset target(9,20,9) is not one of the two requested base columns.
      stopped(result,WriteStatus.NO_WRITE_INVALID);assertTrue(otherColumnWritten.get());assertEquals(Blocks.AIR.defaultBlockState(),target.getBlockState(new BlockPos(9,20,9)));
      restored(target,initial);released(target);
   }

   @Test void unknownQueuedAndPartialPlansStopBeforeAnyEarlierColumnWrites() throws Exception {
      for(var terminal:List.of(TellusColumnPlan.Status.UNKNOWN,TellusColumnPlan.Status.QUEUED,TellusColumnPlan.Status.PARTIAL)){
         var target=chunk();var initial=before(target);AtomicInteger planned=new AtomicInteger();
         var result=batch().write(true,target,two(),entry->{
            assertEquals(Blocks.GLASS.defaultBlockState(),target.getBlockState(new BlockPos(1,4,2)),"all plans precede prefix writes");
            if(planned.incrementAndGet()==1)return plan(entry,true);
            if(terminal==TellusColumnPlan.Status.PARTIAL)return TellusColumnPlan.planned(TellusSample.partial(entry.request(),"partial"),decision(entry,DecisionStatus.PARTIAL),FeaturePlan.empty("partial"));
            if(terminal==TellusColumnPlan.Status.QUEUED)return TellusColumnPlan.planned(sample(entry),decision(entry,DecisionStatus.AVAILABLE),FeaturePlan.queued("feature queued"));
            return TellusColumnPlan.blocked(TellusSample.unknown(entry.request(),"unknown"),terminal,"terminal");
         });
         WriteStatus expected=terminal==TellusColumnPlan.Status.UNKNOWN?WriteStatus.NO_WRITE_UNKNOWN:terminal==TellusColumnPlan.Status.QUEUED?WriteStatus.NO_WRITE_QUEUED:WriteStatus.NO_WRITE_PARTIAL;
         stopped(result,expected);assertEquals(2,planned.get());restored(target,initial);released(target);
      }
   }

   @Test void completePreflightRejectsPhysicalAliasesProfilesBoundsApronAndNonblockingBeforeSource() throws Exception {
      var target=chunk();var initial=before(target);var good=entry(1,2);AtomicInteger planned=new AtomicInteger();
      Function<TellusNativeChunkBatch.Request,TellusColumnPlan> cold=entry->{planned.incrementAndGet();throw new AssertionError("invalid complete preflight reached source");};
      var alias=entry(coordinate(1.75,2.75),GENERATION,false);
      var otherProfile=entry(coordinate(2.25,2.25),new TellusGenerationContext(42,1,65,MIN,MAX),false);
      var wrongBounds=entry(coordinate(2.25,2.25),new TellusGenerationContext(42,1,64,MIN+16,MAX),false);
      var outside=entry(17,2);
      for(var rows:List.of(List.of(good,alias),List.of(good,otherProfile),List.of(good,wrongBounds),List.of(good,outside),List.of(good,apronEntry()))){
         stopped(batch().write(true,target,rows,cold),WriteStatus.NO_WRITE_INVALID);restored(target,initial);released(target);}
      var nonblocking=entry(coordinate(2.25,2.25),GENERATION,true);
      stopped(batch().write(true,target,List.of(good,nonblocking),cold),WriteStatus.NO_WRITE_QUEUED);
      assertEquals(0,planned.get());restored(target,initial);released(target);
   }

   @Test void oversizedWholeChunkSnapshotIsRejectedBeforeSourceOrAnyAllocationOfSnapshotCells() throws Exception {
      int height=4112;var target=(ProtoChunk)SphereFaceNativeBiomeKernelBridgeTest.proto.newInstance(new ChunkPos(0,0),UpgradeData.EMPTY,
         LevelHeightAccessor.create(MIN,height),SphereFaceNativeBiomeKernelBridgeTest.registry,null);
      var generation=new TellusGenerationContext(42,1,64,MIN,MIN+height);var row=entry(coordinate(1.25,2.25),generation,false);
      stopped(batch().write(true,target,List.of(row),entry->{throw new AssertionError("oversized snapshot reached planner");}),WriteStatus.NO_WRITE_INVALID);released(target);
   }

   @Test void plannerRuntimeFailureAndErrorReleaseLeaseWithoutWritesAndAllowRetry() throws Exception {
      var target=chunk();var initial=before(target);var batch=batch();
      stopped(batch.write(true,target,two(),entry->{throw new IllegalStateException("source unavailable");}),WriteStatus.NO_WRITE_INVALID);
      restored(target,initial);released(target);AssertionError original=new AssertionError("planning Error");
      assertSame(original,assertThrows(AssertionError.class,()->batch.write(true,target,two(),entry->{throw original;})));
      restored(target,initial);released(target);assertEquals(WriteStatus.WRITTEN,batch.write(true,target,two(),entry->plan(entry,false)).status());released(target);
   }

   @Test void secondColumnMapperErrorRestoresCommittedPrefixThenRethrowsSameError() throws Exception {
      var target=chunk();var initial=before(target);var nativeMapper=mapper();AssertionError original=new AssertionError("second-column Error");
      SemanticMapper<BlockState> failing=new SemanticMapper<>(){
         public BlockState map(SemanticMaterial material,int y,WriteContext context){if(CanonicalColumnPosition.resolve(context).x()==2)throw original;return nativeMapper.map(material,y,context);}
         public BlockState mapFeature(FeaturePlacement placement,int y,WriteContext context){return nativeMapper.mapFeature(placement,y,context);}
      };
      assertSame(original,assertThrows(AssertionError.class,()->new TellusNativeChunkBatch(new TellusColumnMaterializer(),failing).write(true,target,two(),entry->plan(entry,true))));
      restored(target,initial);released(target);
   }

   @Test void throwingExceptionMessageCannotBypassPrefixRecoveryOrColdPlanningCleanup() throws Exception {
      class BadMessageRuntime extends RuntimeException {
         final AtomicInteger messageCalls=new AtomicInteger();
         @Override public String getMessage(){messageCalls.incrementAndGet();throw this;}
      }
      class BadMessageError extends Error {
         final AtomicInteger messageCalls=new AtomicInteger();
         @Override public String getMessage(){messageCalls.incrementAndGet();throw this;}
      }
      for(boolean fatal:new boolean[]{false,true}){
         var target=chunk();var initial=before(target);var nativeMapper=mapper();AtomicBoolean prefixSeen=new AtomicBoolean();
         var runtime=new BadMessageRuntime();var error=new BadMessageError();
         SemanticMapper<BlockState> failing=new SemanticMapper<>(){
            public BlockState map(SemanticMaterial material,int y,WriteContext context){
               if(CanonicalColumnPosition.resolve(context).x()==2){
                  BlockState prefix=target.getBlockState(new BlockPos(1,4,2));prefixSeen.set(prefix.is(Blocks.WATER)&&prefix.getValue(LiquidBlock.LEVEL)==8);
                  if(fatal)throw error;throw runtime;
               }
               return nativeMapper.map(material,y,context);
            }
            public BlockState mapFeature(FeaturePlacement placement,int y,WriteContext context){return nativeMapper.mapFeature(placement,y,context);}
         };
         var batch=new TellusNativeChunkBatch(new TellusColumnMaterializer(),failing);
         if(fatal){assertSame(error,assertThrows(BadMessageError.class,()->batch.write(true,target,two(),entry->plan(entry,true))));
            assertEquals(0,error.messageCalls.get(),"Error diagnostics are not consulted before restoration and rethrow");}
         else stopped(batch.write(true,target,two(),entry->plan(entry,true)),WriteStatus.NO_WRITE_INVALID);
         assertTrue(prefixSeen.get(),"first-column flow mutation must exist before the late failure");restored(target,initial);released(target);
      }
      var target=chunk();var initial=before(target);var badPlanning=new BadMessageRuntime();
      stopped(batch().write(true,target,two(),entry->{throw badPlanning;}),WriteStatus.NO_WRITE_INVALID);
      assertEquals(0,badPlanning.messageCalls.get(),"preflight diagnostic also avoids overridable Throwable callbacks");restored(target,initial);released(target);
      var badPlanningError=new BadMessageError();
      assertSame(badPlanningError,assertThrows(BadMessageError.class,()->batch().write(true,target,two(),entry->{throw badPlanningError;})));
      assertEquals(0,badPlanningError.messageCalls.get());restored(target,initial);released(target);
   }

   @Test void otherThreadBatchAndNativeCompositeAreQueuedWhileDifferentActualTargetCanProceed() throws Exception {
      var target=chunk();var other=chunk();var rows=two();var batch=batch();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
      var executor=Executors.newSingleThreadExecutor();Future<TellusNativeChunkBatch.Result> active=null;
      try{
         active=executor.submit(()->batch.write(true,target,rows,entry->{entered.countDown();await(release);return plan(entry,false);}));
         assertTrue(entered.await(5,TimeUnit.SECONDS),"worker entered planner while holding lease");
         stopped(batch.write(true,target,rows,entry->{throw new AssertionError("busy batch reached planner");}),WriteStatus.NO_WRITE_QUEUED);
         assertEquals(WriteStatus.NO_WRITE_QUEUED,nativeComposite(target,rows.get(0),mapper()).status());
         assertEquals(WriteStatus.WRITTEN,batch.write(true,other,rows,entry->plan(entry,true)).status(),"same world coordinates are not the same target object");
         release.countDown();assertEquals(WriteStatus.WRITTEN,active.get(10,TimeUnit.SECONDS).status());released(target);released(other);
      }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
   }

   @Test void sameThreadMapperReentryIsQueuedEvenWithoutFlowAndLaterFailureRestoresPrefix() throws Exception {
      var target=chunk();var initial=before(target);var nativeMapper=mapper();var normal=batch();AtomicBoolean reentered=new AtomicBoolean();
      SemanticMapper<BlockState> mapper=(material,y,context)->{
         if(reentered.compareAndSet(false,true)){
            stopped(normal.write(true,target,two(),entry->{throw new AssertionError("reentrant batch reached planner");}),WriteStatus.NO_WRITE_QUEUED);
         }
         if(CanonicalColumnPosition.resolve(context).x()==2)throw new IllegalStateException("second nonflow row");
         return nativeMapper.map(material,y,context);
      };
      stopped(new TellusNativeChunkBatch(new TellusColumnMaterializer(),mapper).write(true,target,two(),entry->plan(entry,false)),WriteStatus.NO_WRITE_INVALID);
      assertTrue(reentered.get());restored(target,initial);released(target);
   }

   @Test void metadataRestoreErrorReportsIncompleteAfterRestoringEveryBlockAndReleasesLease() throws Exception {
      var target=chunk();AtomicBoolean failRestore=new AtomicBoolean();AssertionError metadataError=new AssertionError("marker restore Error");
      target.getPostProcessing()[0]=new ShortArrayList(new short[]{7,7,99}){
         @Override public void clear(){if(failRestore.get())throw metadataError;super.clear();}
      };
      var initial=before(target);var nativeMapper=mapper();
      SemanticMapper<BlockState> failing=new SemanticMapper<>(){
         public BlockState map(SemanticMaterial material,int y,WriteContext context){
            if(CanonicalColumnPosition.resolve(context).x()==2){failRestore.set(true);throw new IllegalStateException("late row failure");}
            return nativeMapper.map(material,y,context);}
         public BlockState mapFeature(FeaturePlacement placement,int y,WriteContext context){return nativeMapper.mapFeature(placement,y,context);}
      };
      var error=assertThrows(IllegalStateException.class,()->new TellusNativeChunkBatch(new TellusColumnMaterializer(),failing).write(true,target,two(),entry->plan(entry,true)));
      assertTrue(error.getMessage().contains("chunk batch rollback incomplete"));assertSame(metadataError,error.getSuppressed()[0]);
      for(int i=0;i<initial.states().length;i++)assertEquals(initial.states()[i],target.getBlockState(position(target,i)),"all blocks restored despite marker restore Error");
      assertTrue(target.isUnsaved(),"incomplete marker/dirty recovery must stay observable");released(target);
   }

   @Test void mismatchedOrNullPlannerResultIsInvalidBeforeSnapshotOrPrefixWrites() throws Exception {
      var target=chunk();var initial=before(target);var batch=batch();
      stopped(batch.write(true,target,two(),entry->plan(entry(3,2),false)),WriteStatus.NO_WRITE_INVALID);restored(target,initial);released(target);
      stopped(batch.write(true,target,two(),entry->null),WriteStatus.NO_WRITE_INVALID);restored(target,initial);released(target);
   }

   @Test void disabledEmptyOversizedAndValueShapeGuardsAreColdAndResultsCannotExposeRolledBackSuccess() throws Exception {
      var batch=batch();Function<TellusNativeChunkBatch.Request,TellusColumnPlan> cold=entry->{throw new AssertionError("early guard reached planner");};
      stopped(batch.write(false,null,null,null),WriteStatus.NO_WRITE_INVALID);
      stopped(batch.write(true,null,List.of(),cold),WriteStatus.NO_WRITE_INVALID);
      stopped(batch.write(true,null,Collections.nCopies(257,entry(1,2)),cold),WriteStatus.NO_WRITE_INVALID);
      assertThrows(NullPointerException.class,()->new TellusNativeChunkBatch.Request(null,GENERATION,entry(1,2).coordinate(),entry(1,2).request()));
      var result=new WriteResult<BlockState>(WriteStatus.WRITTEN,List.of(Blocks.STONE.defaultBlockState()),List.of());
      assertThrows(IllegalArgumentException.class,()->new TellusNativeChunkBatch.Result(WriteStatus.NO_WRITE_INVALID,List.of(result),List.of()));
      assertThrows(IllegalArgumentException.class,()->new TellusNativeChunkBatch.Result(WriteStatus.WRITTEN,List.of(),List.of()));
      assertTrue(Arrays.stream(StorageTransactionBoundary.class.getMethods()).noneMatch(method->Arrays.asList(method.getParameterTypes()).contains(TellusNativeChunkBatch.Result.class)),"no flat-storage overload pretending the batch has one canonical key");
   }

   static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("bounded latch wait expired");}
      catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
   static WriteResult<?> nativeComposite(ProtoChunk target,TellusNativeChunkBatch.Request row,SemanticMapper<BlockState> mapper) throws Exception {
      String version=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211";
      var type=Class.forName("com.yucareux.tellus.worldgen.write.TellusMinecraft"+version+"CompositeColumnWriter");
      var writer=type.getConstructor(TellusColumnMaterializer.class).newInstance(new TellusColumnMaterializer());
      return (WriteResult<?>)type.getMethod("write",ChunkAccess.class,WriteContext.class,ColumnDecision.class,FeaturePlan.class,SemanticMapper.class)
         .invoke(writer,target,row.context(),decision(row,DecisionStatus.AVAILABLE),FeaturePlan.empty("busy native test"),mapper);
   }
   static TellusNativeChunkBatch.Request apronEntry(){
      var layout=CubeNetLayout.standard(MODEL.config().faceBlocks());var resolver=new CubeNetApronResolver(layout,64);double middle=layout.faceSize()*0.5;
      for(var face:CubeNetLayout.Face.values())for(var side:CubeNetLayout.Side.values()){
         if(layout.arePlanarNeighbors(face,side))continue;var placement=layout.placement(face);
         var interior=switch(side){case WEST->new CubeNetLayout.FacePoint(face,0.5,middle);case EAST->new CubeNetLayout.FacePoint(face,layout.faceSize()-0.5,middle);
            case NORTH->new CubeNetLayout.FacePoint(face,middle,0.5);case SOUTH->new CubeNetLayout.FacePoint(face,middle,layout.faceSize()-0.5);};
         var center=layout.toPlanar(interior);double dx=switch(side){case WEST->-placement.uAxisX();case EAST->placement.uAxisX();case NORTH->-placement.vAxisX();case SOUTH->placement.vAxisX();};
         double dz=switch(side){case WEST->-placement.uAxisZ();case EAST->placement.uAxisZ();case NORTH->-placement.vAxisZ();case SOUTH->placement.vAxisZ();};
         var candidate=TellusCoordinateContextFactory.fromCubeNetPlanar(MODEL,layout,resolver,center.x()+dx*24,center.z()+dz*24);
         if(candidate.isPresent()&&candidate.orElseThrow().owner() instanceof CubeNetApronOwnerView apron){var c=candidate.orElseThrow();
            var canonical=TellusCoordinateContextFactory.fromCubeNetCanonical(MODEL,layout,apron.canonicalOwner().facePoint());
            var request=TellusSampleRequest.fromContext(c,SamplingPurpose.TERRAIN_COLUMN,SamplingResolution.exact(1),GENERATION.geometry(),GENERATION.worldSeed());
            return new TellusNativeChunkBatch.Request(WriteContext.apronMirror(c,canonical,GENERATION,"batch-apron"),GENERATION,c,request);}
      }
      throw new AssertionError("bounded face/side search did not provide apron fixture");
   }
}
