package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import it.unimi.dsi.fastutil.shorts.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual ProtoChunk section publication and participant exclusion; not world/engine persistence. */
class TellusNativeBiomeVolumeWriterTest {
   @TempDir static Path root;
   static final int MIN=-64,MAX=320;
   static final SphereWorldModel MODEL=SphereWorldModel.forWorldScale(1);
   static final TellusGenerationContext GENERATION=new TellusGenerationContext(42,1,64,MIN,MAX);
   @BeforeAll static void initialize() throws Exception {SphereFaceNativeBiomeKernelBridgeTest.directory=root;SphereFaceNativeBiomeKernelBridgeTest.bootstrap();}
   @AfterAll static void cleanup(){SphereFaceNativeBiomeKernelBridgeTest.restore();}
   static Registry<Biome> registry(){return SphereFaceNativeBiomeKernelBridgeTest.registry;}
   static TellusCoordinateContext coordinate(int x,int z){
      var geo=new SphereWorldModel.GeoPoint(EarthProjection.blockZToLat(z+0.5,1),EarthProjection.blockXToLongitude(x+0.5,1),0);
      return TellusCoordinateContextFactory.fromGeo(MODEL,geo,new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo,1)));
   }
   static TellusBiomeVolumePlan.Request request(){return request(GENERATION,SamplingResolution.exact(1));}
   static TellusBiomeVolumePlan.Request request(TellusGenerationContext generation,SamplingResolution resolution){
      List<WriteContext> columns=new ArrayList<>();for(int z=0;z<4;z++)for(int x=0;x<4;x++)columns.add(WriteContext.canonical(coordinate(x*4,z*4),generation,"biome-volume-test"));
      return new TellusBiomeVolumePlan.Request(generation,0,0,columns,resolution);
   }
   static String biome(int x,int y,int z){return switch(Math.floorMod(x+y+z,3)){case 0->"minecraft:plains";case 1->"minecraft:desert";default->"minecraft:frozen_ocean";};}
   static TellusBiomeVolumePlan plan(TellusBiomeVolumePlan.Request request,String fixed){
      List<TellusBiomeVolumePlan.Cell> cells=new ArrayList<>();for(var c:TellusBiomeVolumePlan.nativeCellOrder(request))cells.add(new TellusBiomeVolumePlan.Cell(c.quartX(),c.quartY(),c.quartZ(),new BiomeDecision(fixed==null?biome(c.quartX(),c.quartY(),c.quartZ()):fixed,true)));
      return TellusBiomeVolumePlan.available(request,cells,List.of("resolved biome fixture"));
   }
   static ProtoChunk chunk() throws Exception {var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(0,0));seed(target);return target;}
   static void seed(ProtoChunk target){
      target.getPostProcessing()[0]=new ShortArrayList(new short[]{7,7,99});
      target.setBlockState(new BlockPos(1,4,2),Blocks.GLASS.defaultBlockState(),false);
      target.setBlockState(new BlockPos(2,4,2),Blocks.WHEAT.defaultBlockState(),false);
      target.setBlockState(new BlockPos(3,4,2),Blocks.WATER.defaultBlockState(),false);target.setUnsaved(false);
   }
   record Before(LevelChunkSection[] sections,Object[] states,Object[] palettes,List<Holder<Biome>> holders,List<BlockState> blocks,
                 List<List<Boolean>> flags,ShortList[] markers,short[][] markerContents,boolean dirty){ }
   static Before before(ProtoChunk target){
      var sections=target.getSections().clone();Object[] states=new Object[sections.length],palettes=new Object[sections.length];
      List<Holder<Biome>> holders=new ArrayList<>();List<BlockState> blocks=new ArrayList<>();List<List<Boolean>> flags=new ArrayList<>();
      for(int i=0;i<sections.length;i++){var section=sections[i];states[i]=section.getStates();palettes[i]=section.getBiomes();
         flags.add(List.of(section.hasOnlyAir(),section.isRandomlyTicking(),section.isRandomlyTickingBlocks(),section.isRandomlyTickingFluids()));
         for(int r=0;r<64;r++)holders.add(section.getBiomes().get(r/16,(r/4)%4,r%4));
         for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)blocks.add(section.getBlockState(x,y,z));}
      var markers=target.getPostProcessing().clone();short[][] contents=new short[markers.length][];for(int i=0;i<markers.length;i++)if(markers[i]!=null)contents[i]=markers[i].toShortArray();
      return new Before(sections,states,palettes,holders,blocks,flags,markers,contents,target.isUnsaved());
   }
   static void untouchedOldObjects(ProtoChunk target,Before old){
      int block=0;for(int i=0;i<old.sections().length;i++){var original=old.sections()[i];assertSame(original,target.getSections()[i]);assertSame(old.states()[i],original.getStates());assertSame(old.palettes()[i],original.getBiomes());
         assertSame(old.states()[i],target.getSections()[i].getStates());assertEquals(old.flags().get(i),List.of(original.hasOnlyAir(),original.isRandomlyTicking(),original.isRandomlyTickingBlocks(),original.isRandomlyTickingFluids()));
         assertEquals(old.flags().get(i),List.of(target.getSections()[i].hasOnlyAir(),target.getSections()[i].isRandomlyTicking(),target.getSections()[i].isRandomlyTickingBlocks(),target.getSections()[i].isRandomlyTickingFluids()));
         for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)assertSame(old.blocks().get(block++),target.getSections()[i].getBlockState(x,y,z));}
      for(int i=0;i<old.markers().length;i++){assertSame(old.markers()[i],target.getPostProcessing()[i]);if(old.markers()[i]!=null)assertArrayEquals(old.markerContents()[i],target.getPostProcessing()[i].toShortArray());}
   }
   static void restored(ProtoChunk target,Before old){int quart=0;for(int i=0;i<old.sections().length;i++){assertSame(old.sections()[i],target.getSections()[i]);assertSame(old.palettes()[i],target.getSections()[i].getBiomes());
      for(int r=0;r<64;r++)assertSame(old.holders().get(quart++),target.getSections()[i].getBiomes().get(r/16,(r/4)%4,r%4));}untouchedOldObjects(target,old);assertEquals(old.dirty(),target.isUnsaved());}
   static void released(ProtoChunk target){try(var lease=TellusTargetWriteGuard.tryAcquire(target).orElseThrow()){lease.requireOwned(target);}}
   static TellusNativeBiomeVolumeWriter.Result write(ProtoChunk target,TellusBiomeVolumePlanner planner){return new TellusNativeBiomeVolumeWriter().write(true,target,request(),planner,registry());}
   static void stopped(TellusNativeBiomeVolumeWriter.Result result,WriteStatus status){assertEquals(status,result.status());assertEquals(0,result.writtenQuartCells());}

   @Test void complete1536QuartPublicationRetainsSectionsPalettesBlockContainersAndEveryNativeCounter() throws Exception {
      var target=chunk();var old=before(target);AtomicInteger calls=new AtomicInteger();assertTrue(old.flags().stream().anyMatch(flags->flags.get(3)),"Fixture must retain native incremental fluid counter before publication");
      var result=write(target,r->{calls.incrementAndGet();return plan(r,null);});assertEquals(WriteStatus.WRITTEN,result.status());assertEquals(1536,result.writtenQuartCells());assertEquals(1,calls.get());
      var nativeOrder=TellusBiomeVolumePlan.nativeCellOrder(request());for(int i=0;i<nativeOrder.size();i++){var q=nativeOrder.get(i);var holder=target.getSections()[i/64].getBiomes().get((i%64)/16,((i%64)/4)%4,i%4);
         assertEquals(biome(q.quartX(),q.quartY(),q.quartZ()),holder.unwrapKey().orElseThrow().location().toString());assertSame(registry().getHolder(holder.unwrapKey().orElseThrow()).orElseThrow(),holder);}
      for(int i=0;i<old.sections().length;i++){assertSame(old.sections()[i],target.getSections()[i]);assertSame(old.palettes()[i],target.getSections()[i].getBiomes());}
      untouchedOldObjects(target,old);assertTrue(target.isUnsaved());released(target);
      System.out.println("NATIVE_BIOME_VOLUME_WRITER cells=1536 exactRegistryHolders=true sectionIdentity=true stateContainerIdentity=true biomeContainerIdentity=true blockCounters=true nativeFluidCounterRetained=true markersUntouched=true");
   }

   @SuppressWarnings("unchecked") static Registry<Biome> coldRegistry(){return (Registry<Biome>)Proxy.newProxyInstance(Registry.class.getClassLoader(),new Class<?>[]{Registry.class},(proxy,method,args)->{throw new AssertionError("nonblocking registry callback: "+method.getName());});}
   @SuppressWarnings("unchecked") static PalettedContainerRO<Holder<Biome>> paletteProxy(PalettedContainerRO<Holder<Biome>> original,java.util.function.Function<String,Object> override){
      return (PalettedContainerRO<Holder<Biome>>)Proxy.newProxyInstance(PalettedContainerRO.class.getClassLoader(),new Class<?>[]{PalettedContainerRO.class},(proxy,method,args)->{
         Object value=override.apply(method.getName());if(value!=null)return value;try{return method.invoke(original,args);}catch(InvocationTargetException error){throw error.getCause();}});
   }
   @Test void nonblockingStopsBeforePlannerPaletteRegistryAndDisabledNullOrBusyStayCold() throws Exception {
      var target=chunk();var original=target.getSections()[0];var coldPalette=paletteProxy(original.getBiomes(),name->{throw new AssertionError("nonblocking palette callback");});
      target.getSections()[0]=new LevelChunkSection(original.getStates(),coldPalette);
      TellusBiomeVolumePlanner cold=r->{throw new AssertionError("cold planner reached");};var writer=new TellusNativeBiomeVolumeWriter();
      stopped(writer.write(true,target,request(GENERATION,SamplingResolution.nonBlocking(1)),cold,coldRegistry()),WriteStatus.NO_WRITE_QUEUED);
      stopped(writer.write(false,null,null,null,null),WriteStatus.NO_WRITE_INVALID);stopped(writer.write(true,null,request(),cold,registry()),WriteStatus.NO_WRITE_INVALID);
      try(var lease=TellusTargetWriteGuard.tryAcquire(target).orElseThrow()){stopped(writer.write(true,target,request(),cold,coldRegistry()),WriteStatus.NO_WRITE_QUEUED);}assertFalse(target.isUnsaved());released(target);
   }

   @Test void unknownQueuedMismatchedOrNullPlanLeavesEveryOriginalReferenceUntouched() throws Exception {
      var target=chunk();var old=before(target);
      for(var status:List.of(DecisionStatus.UNKNOWN,DecisionStatus.QUEUED)){stopped(write(target,r->TellusBiomeVolumePlan.blocked(r,status,"blocked")),status==DecisionStatus.UNKNOWN?WriteStatus.NO_WRITE_UNKNOWN:WriteStatus.NO_WRITE_QUEUED);restored(target,old);released(target);}
      stopped(write(target,r->null),WriteStatus.NO_WRITE_INVALID);restored(target,old);
      var other=new TellusBiomeVolumePlan.Request(GENERATION,1,0,request().columns(),request().resolution());stopped(write(target,r->plan(other,"minecraft:plains")),WriteStatus.NO_WRITE_INVALID);restored(target,old);released(target);
   }

   @Test void invalidOrUnmappedSemanticIdsNeverPublishAndForeignRegistryIsPlannerCold() throws Exception {
      var target=chunk();var old=before(target);for(var id:List.of("minecraft:missing","Bad ID?")){stopped(write(target,r->plan(r,id)),WriteStatus.NO_WRITE_INVALID);restored(target,old);released(target);}
      var mapped=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());for(var key:List.of(Biomes.PLAINS,Biomes.DESERT,Biomes.FROZEN_OCEAN))Registry.register(mapped,key,registry().getOrThrow(key));var foreign=mapped.freeze();
      stopped(new TellusNativeBiomeVolumeWriter().write(true,target,request(),r->{throw new AssertionError("foreign target palette must be rejected before planner");},foreign),WriteStatus.NO_WRITE_INVALID);restored(target,old);released(target);
   }

   @Test void lateResolverRuntimeAndErrorDoNotPublishAndAlwaysReleaseWithoutMessageCallbacks() throws Exception {
      var target=chunk();var old=before(target);var runtime=new RuntimeException(){public String getMessage(){throw this;}};var fatal=new AssertionError(){public String getMessage(){throw this;}};
      for(boolean error:new boolean[]{false,true}){AtomicInteger calls=new AtomicInteger();var planner=TellusBiomeVolumePlanner.fromResolver(req->(c,x,y,z)->{if(calls.incrementAndGet()==1000){if(error)throw fatal;throw runtime;}return BiomeDecision.defaultBiome();});
         if(error)assertSame(fatal,assertThrows(AssertionError.class,()->write(target,planner)));else stopped(write(target,planner),WriteStatus.NO_WRITE_INVALID);assertEquals(1000,calls.get());restored(target,old);released(target);}
   }

   static class DirtyFaultChunk extends ProtoChunk {
      int mode=-1;boolean armed;RuntimeException runtime;Error fatal;
      DirtyFaultChunk(){super(new ChunkPos(0,0),UpgradeData.EMPTY,LevelHeightAccessor.create(MIN,MAX-MIN),registry(),null);}
      @Override public void setUnsaved(boolean value){
         if(!armed){super.setUnsaved(value);return;}
         if(value){if(mode==2)return;super.setUnsaved(true);if(mode==1)throw fatal;throw runtime;}
         if(mode==3)throw fatal;super.setUnsaved(false);
      }
   }
   @Test void dirtyMutationRuntimeErrorAndNoopRestoreSectionsBiomesStatesCountersMarkersAndDirty() throws Exception {
      for(int mode=0;mode<3;mode++){var target=new DirtyFaultChunk();seed(target);var old=before(target);AtomicInteger getters=new AtomicInteger();
         target.runtime=new RuntimeException(){public String getMessage(){getters.incrementAndGet();throw this;}};target.fatal=new AssertionError(){public String getMessage(){getters.incrementAndGet();throw this;}};target.mode=mode;target.armed=true;
         if(mode==1)assertSame(target.fatal,assertThrows(AssertionError.class,()->write(target,r->plan(r,null))));else stopped(write(target,r->plan(r,null)),WriteStatus.NO_WRITE_INVALID);
         assertEquals(0,getters.get());restored(target,old);released(target);}
   }

   @Test void dirtyRestorationErrorReportsIncompleteAfterEverySectionReferenceIsRestored() throws Exception {
      var target=new DirtyFaultChunk();seed(target);var old=before(target);target.runtime=new IllegalStateException("after dirty mutation");target.fatal=new AssertionError("dirty restore Error");target.mode=3;target.armed=true;
      var failure=assertThrows(IllegalStateException.class,()->write(target,r->plan(r,null)));assertEquals("biome volume rollback incomplete",failure.getMessage());assertSame(target.runtime,failure.getCause());assertSame(target.fatal,failure.getSuppressed()[0]);
      int quart=0;for(int i=0;i<old.sections().length;i++){assertSame(old.sections()[i],target.getSections()[i]);for(int r=0;r<64;r++)assertSame(old.holders().get(quart++),target.getSections()[i].getBiomes().get(r/16,(r/4)%4,r%4));}untouchedOldObjects(target,old);assertTrue(target.isUnsaved());released(target);
   }

   @Test void malformedAnchorsProfileBoundsBudgetAndCustomOrMissingSectionsArePlannerCold() throws Exception {
      var target=chunk();TellusBiomeVolumePlanner cold=r->{throw new AssertionError("invalid preflight reached planner");};var writer=new TellusNativeBiomeVolumeWriter();var good=request();
      List<TellusBiomeVolumePlan.Request> bad=new ArrayList<>();bad.add(new TellusBiomeVolumePlan.Request(GENERATION,0,0,good.columns().subList(0,15),good.resolution()));
      var duplicate=new ArrayList<>(good.columns());duplicate.set(1,duplicate.get(0));bad.add(new TellusBiomeVolumePlan.Request(GENERATION,0,0,duplicate,good.resolution()));
      bad.add(request(new TellusGenerationContext(42,1,64,-63,321),good.resolution()));bad.add(request(new TellusGenerationContext(42,1,64,MIN,MIN+4112),good.resolution()));
      var profiles=new ArrayList<>(good.columns());profiles.set(0,WriteContext.canonical(coordinate(0,0),new TellusGenerationContext(99,1,64,MIN,MAX),"wrong-seed"));bad.add(new TellusBiomeVolumePlan.Request(GENERATION,0,0,profiles,good.resolution()));
      var apron=new ArrayList<>(good.columns());apron.set(0,TellusNativeChunkBatchTest.apronEntry().context());bad.add(new TellusBiomeVolumePlan.Request(GENERATION,0,0,apron,good.resolution()));
      for(var request:bad){stopped(writer.write(true,target,request,cold,registry()),WriteStatus.NO_WRITE_INVALID);released(target);}
      var original=target.getSections()[0];target.getSections()[0]=new LevelChunkSection(registry()){};stopped(writer.write(true,target,good,cold,registry()),WriteStatus.NO_WRITE_INVALID);target.getSections()[0]=null;stopped(writer.write(true,target,good,cold,registry()),WriteStatus.NO_WRITE_INVALID);target.getSections()[0]=original;released(target);
      var aliasTarget=chunk();var first=aliasTarget.getSections()[0];aliasTarget.getSections()[1]=new LevelChunkSection(aliasTarget.getSections()[1].getStates(),first.getBiomes());var aliasOld=before(aliasTarget);AtomicInteger aliasCalls=new AtomicInteger();stopped(writer.write(true,aliasTarget,good,r->{aliasCalls.incrementAndGet();return plan(r,null);},registry()),WriteStatus.NO_WRITE_INVALID);assertEquals(0,aliasCalls.get());restored(aliasTarget,aliasOld);released(aliasTarget);
   }

   @Test void unsupportedReadOnlyAndMutablePaletteSubclassesRemainPlannerAndRegistryCold() throws Exception {
      for(boolean readOnly:new boolean[]{true,false}){var target=chunk();var section=target.getSections()[0];PalettedContainerRO<Holder<Biome>> unsupported;
         if(readOnly)unsupported=paletteProxy(section.getBiomes(),name->{throw new AssertionError("unsupported RO palette method must remain cold");});
         else unsupported=new PalettedContainer<Holder<Biome>>(registry().asHolderIdMap(),registry().getHolderOrThrow(Biomes.PLAINS),PalettedContainer.Strategy.SECTION_BIOMES){};
         target.getSections()[0]=new LevelChunkSection(section.getStates(),unsupported);var original=target.getSections()[0];
         stopped(new TellusNativeBiomeVolumeWriter().write(true,target,request(),r->{throw new AssertionError("unsupported palette reached planner");},coldRegistry()),WriteStatus.NO_WRITE_INVALID);
         assertSame(original,target.getSections()[0]);assertSame(unsupported,target.getSections()[0].getBiomes());assertFalse(target.isUnsaved());released(target);}
   }

   @Test void sameThreadReentryAndFiveExistingNativeWritePathsQueueWithoutSamplingOrMapping() throws Exception {
      var target=chunk();AtomicInteger observed=new AtomicInteger();var nativeRequest=request();var context=nativeRequest.columns().get(0);var column=new ColumnDecision(context.targetCoordinate(),DecisionStatus.AVAILABLE,OptionalInt.of(1),SurfaceDecision.defaultStone(),WaterDecision.dry(),BiomeDecision.defaultBiome(),StrataDecision.stone(MIN,1),List.of());
      String v=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211",prefix="com.yucareux.tellus.worldgen.write.TellusMinecraft"+v;
      TellusSampleProvider coldProvider=new TellusSampleProvider(){public String providerId(){return "cold";}public TellusSample sample(TellusSampleRequest r){throw new AssertionError("busy native sampler");}};
      SemanticMapper<BlockState> coldMapper=(m,y,c)->{throw new AssertionError("busy native mapper");};var materializer=new TellusColumnMaterializer();
      var pipeline=Class.forName(prefix+"CanonicalPipeline").getConstructor(boolean.class,TellusSampleProvider.class,TellusColumnDecisionEngine.class,TellusFeaturePlanner.class,TellusColumnMaterializer.class,SemanticMapper.class).newInstance(true,coldProvider,new TellusColumnDecisionEngine(),TellusFeaturePlanner.empty(),materializer,coldMapper);
      var sampleRequest=TellusSampleRequest.fromContext(context.targetCoordinate(),SamplingPurpose.TERRAIN_COLUMN,SamplingResolution.exact(1),GENERATION.geometry(),42);
      var composite=Class.forName(prefix+"CompositeColumnWriter").getConstructor(TellusColumnMaterializer.class).newInstance(materializer);var adapter=Class.forName(prefix+"CanonicalColumnAdapter").getConstructor(TellusColumnMaterializer.class,SemanticMapper.class).newInstance(materializer,coldMapper);
      var sink=Class.forName("com.yucareux.tellus.worldgen.feature.TellusMinecraft"+v+"FeaturePlacementSink").getConstructor(ChunkAccess.class).newInstance(target);var feature=FeaturePlan.available(FeaturePlacement.canonical(FeatureKind.CUSTOM,"lease",context.targetCoordinate(),PlacementOperation.SET,PlacementConflictPolicy.OWNER_ONLY,SemanticMaterial.STONE,0,10,0,1,Map.of()));
      var result=write(target,r->{
         stopped(write(target,nested->{throw new AssertionError("same-thread reentry planner");}),WriteStatus.NO_WRITE_QUEUED);
         try{
            assertEquals(WriteStatus.NO_WRITE_QUEUED,((WriteResult<?>)pipeline.getClass().getMethod("write",ChunkAccess.class,WriteContext.class,TellusGenerationContext.class,TellusCoordinateContext.class,TellusSampleRequest.class).invoke(pipeline,target,context,GENERATION,context.targetCoordinate(),sampleRequest)).status());
            assertEquals(WriteStatus.NO_WRITE_QUEUED,((TellusNativeChunkBatch.Result)pipeline.getClass().getMethod("writeChunk",ChunkAccess.class,List.class).invoke(pipeline,target,List.of(new TellusNativeChunkBatch.Request(context,GENERATION,context.targetCoordinate(),sampleRequest)))).status());
            assertEquals(WriteStatus.NO_WRITE_QUEUED,((WriteResult<?>)composite.getClass().getMethod("write",ChunkAccess.class,WriteContext.class,ColumnDecision.class,FeaturePlan.class,SemanticMapper.class).invoke(composite,target,context,column,FeaturePlan.empty("empty"),coldMapper)).status());
            assertEquals(WriteStatus.NO_WRITE_QUEUED,((WriteResult<?>)adapter.getClass().getMethod("write",ChunkAccess.class,WriteContext.class,ColumnDecision.class).invoke(adapter,target,context,column)).status());
            assertEquals(FeaturePlacementStatus.NO_WRITE_QUEUED,((FeaturePlacementResult<?>)sink.getClass().getMethod("place",WriteContext.class,FeaturePlan.class,SemanticMapper.class).invoke(sink,context,feature,coldMapper)).status());
         }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}observed.incrementAndGet();return plan(r,null);
      });assertEquals(WriteStatus.WRITTEN,result.status());assertEquals(1,observed.get());released(target);
   }

   @Test void deterministicOtherThreadContentionQueuesWhileAnotherActualObjectCanProceed() throws Exception {
      var target=chunk();var other=chunk();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
      try{var active=executor.submit(()->write(target,r->{entered.countDown();await(release);return plan(r,null);}));assertTrue(entered.await(5,TimeUnit.SECONDS));
         stopped(write(target,r->{throw new AssertionError("busy other-thread planner");}),WriteStatus.NO_WRITE_QUEUED);assertEquals(WriteStatus.WRITTEN,write(other,r->plan(r,null)).status());
         release.countDown();assertEquals(WriteStatus.WRITTEN,active.get(10,TimeUnit.SECONDS).status());released(target);released(other);
      }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
   }

   @Test void resultShapeIsImmutableAndHasNoFlatStoragePayloadOverload(){
      var diagnostics=new ArrayList<>(List.of("ok"));var result=new TellusNativeBiomeVolumeWriter.Result(WriteStatus.WRITTEN,1536,diagnostics);diagnostics.clear();assertEquals(List.of("ok"),result.diagnostics());assertThrows(UnsupportedOperationException.class,()->result.diagnostics().clear());
      assertThrows(IllegalArgumentException.class,()->new TellusNativeBiomeVolumeWriter.Result(WriteStatus.WRITTEN,0,List.of()));assertThrows(IllegalArgumentException.class,()->new TellusNativeBiomeVolumeWriter.Result(WriteStatus.NO_WRITE_INVALID,1,List.of()));assertThrows(IllegalArgumentException.class,()->new TellusNativeBiomeVolumeWriter.Result(WriteStatus.PARTIAL,1,List.of()));
      assertTrue(Arrays.stream(StorageTransactionBoundary.class.getMethods()).noneMatch(method->Arrays.asList(method.getParameterTypes()).contains(TellusNativeBiomeVolumeWriter.Result.class)));
   }
   static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("bounded latch wait expired");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
}
