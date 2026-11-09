package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.write.CanonicalColumnPosition;
import com.yucareux.tellus.worldgen.write.WriteContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Registry-free/source-free volume contracts; this suite does not invoke Minecraft or a target writer. */
class TellusBiomeVolumePlannerTest {
   static final TellusGenerationContext GENERATION = new TellusGenerationContext(42, 1, 64, -64, 320);
   static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1);

   static TellusCoordinateContext earth(int blockX, int blockZ, double scale) {
      var geo = new SphereWorldModel.GeoPoint(EarthProjection.blockZToLat(blockZ + 0.25, scale),
         EarthProjection.blockXToLongitude(blockX + 0.25, scale), 0);
      return TellusCoordinateContextFactory.fromGeo(SphereWorldModel.forWorldScale(scale), geo,
         new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo, scale)));
   }
   static TellusBiomeVolumePlan.Request request(int chunkX, int chunkZ) {
      return request(GENERATION, chunkX, chunkZ, SamplingResolution.exact(1));
   }
   static TellusBiomeVolumePlan.Request request(TellusGenerationContext generation, int chunkX, int chunkZ, SamplingResolution resolution) {
      List<WriteContext> columns = new ArrayList<>();
      for (int z=0;z<4;z++) for (int x=0;x<4;x++) columns.add(WriteContext.canonical(
         earth(chunkX*16+x*4, chunkZ*16+z*4, generation.worldScale()), generation, "volume-test"));
      return new TellusBiomeVolumePlan.Request(generation,chunkX,chunkZ,columns,resolution);
   }
   static TellusBiomeVolumePlan.Request columns(TellusBiomeVolumePlan.Request request, List<WriteContext> columns) {
      return new TellusBiomeVolumePlan.Request(request.generation(),request.chunkX(),request.chunkZ(),columns,request.resolution());
   }
   static List<TellusBiomeVolumePlan.Cell> knownCells(TellusBiomeVolumePlan.Request request) {
      List<TellusBiomeVolumePlan.Cell> cells = new ArrayList<>();
      for (var position : TellusBiomeVolumePlan.nativeCellOrder(request)) cells.add(new TellusBiomeVolumePlan.Cell(
         position.quartX(),position.quartY(),position.quartZ(),BiomeDecision.defaultBiome()));
      return cells;
   }
   static void factoryCold(TellusBiomeVolumePlan.Request request) {
      AtomicInteger factory = new AtomicInteger(), resolves = new AtomicInteger();
      var planner = TellusBiomeVolumePlanner.fromResolver(req -> {
         factory.incrementAndGet();return (c,x,y,z)->{resolves.incrementAndGet();return BiomeDecision.defaultBiome();};
      });
      assertThrows(RuntimeException.class,()->planner.plan(request));
      assertEquals(0,factory.get());assertEquals(0,resolves.get());
   }
   static TellusBiomeVolumePlan.Request sphereRequest(SphereWorldModel model) {
      long offset=model.chunksPerFace()/2L*16L;List<WriteContext> contexts=new ArrayList<>();
      for(int z=0;z<4;z++)for(int x=0;x<4;x++)contexts.add(WriteContext.canonical(
         TellusCoordinateContextFactory.fromSphericalBlock(model,new SphereWorldModel.SphereBlockAddress(0,offset+x*4L,offset+z*4L)),GENERATION,"volume-sphere"));
      return new TellusBiomeVolumePlan.Request(GENERATION,0,0,contexts,SamplingResolution.exact(1));
   }
   static TellusBiomeVolumePlan.Request cubeRequest(SphereWorldModel model) {
      var layout=CubeNetLayout.standard(model.config().faceBlocks());
      var first=TellusCoordinateContextFactory.fromCubeNetCanonical(model,layout,new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X,16.5,16.5));
      var position=CanonicalColumnPosition.resolve(WriteContext.canonical(first,GENERATION,"locate"),model);
      int chunkX=Math.floorDiv(position.x(),16),chunkZ=Math.floorDiv(position.z(),16);List<WriteContext> contexts=new ArrayList<>();
      for(int z=0;z<4;z++)for(int x=0;x<4;x++){
         var face=layout.fromPlanar(new CubeNetLayout.PlanarPoint(chunkX*16+x*4+0.5,chunkZ*16+z*4+0.5));
         contexts.add(WriteContext.canonical(TellusCoordinateContextFactory.fromCubeNetCanonical(model,layout,face),GENERATION,"volume-cube"));}
      return new TellusBiomeVolumePlan.Request(GENERATION,chunkX,chunkZ,contexts,SamplingResolution.exact(1));
   }
   static WriteContext apron() {
      var layout=CubeNetLayout.standard(MODEL.config().faceBlocks());var resolver=new CubeNetApronResolver(layout,64);double middle=layout.faceSize()*0.5;
      for(var face:CubeNetLayout.Face.values())for(var side:CubeNetLayout.Side.values()){
         if(layout.arePlanarNeighbors(face,side))continue;var placement=layout.placement(face);
         var interior=switch(side){case WEST->new CubeNetLayout.FacePoint(face,0.5,middle);case EAST->new CubeNetLayout.FacePoint(face,layout.faceSize()-0.5,middle);
            case NORTH->new CubeNetLayout.FacePoint(face,middle,0.5);case SOUTH->new CubeNetLayout.FacePoint(face,middle,layout.faceSize()-0.5);};
         var center=layout.toPlanar(interior);double dx=switch(side){case WEST->-placement.uAxisX();case EAST->placement.uAxisX();case NORTH->-placement.vAxisX();case SOUTH->placement.vAxisX();};
         double dz=switch(side){case WEST->-placement.uAxisZ();case EAST->placement.uAxisZ();case NORTH->-placement.vAxisZ();case SOUTH->placement.vAxisZ();};
         var candidate=TellusCoordinateContextFactory.fromCubeNetPlanar(MODEL,layout,resolver,center.x()+dx*24,center.z()+dz*24);
         if(candidate.isPresent()&&candidate.orElseThrow().owner() instanceof CubeNetApronOwnerView owner){
            var canonical=TellusCoordinateContextFactory.fromCubeNetCanonical(MODEL,layout,owner.canonicalOwner().facePoint());
            return WriteContext.apronMirror(candidate.orElseThrow(),canonical,GENERATION,"volume-apron");}
      }
      throw new AssertionError("bounded face/side search did not yield a real apron");
   }

   @Test void nativeOrderMatchesSectionXyZAcrossBothChunkSignsAndNegativeYWithoutHoles() {
      for(int[] chunk:new int[][]{{-7,9},{7,-9},{-1,-1},{0,0}}){
         var request=request(chunk[0],chunk[1]);var order=TellusBiomeVolumePlan.nativeCellOrder(request);
         assertEquals(1536,order.size());assertEquals(1536,new HashSet<>(order).size());int qx=chunk[0]*4,qz=chunk[1]*4;
         assertEquals(new TellusBiomeVolumePlan.QuartCoordinate(qx,-16,qz),order.get(0));
         assertEquals(new TellusBiomeVolumePlan.QuartCoordinate(qx,-16,qz+3),order.get(3));
         assertEquals(new TellusBiomeVolumePlan.QuartCoordinate(qx,-15,qz),order.get(4));
         assertEquals(new TellusBiomeVolumePlan.QuartCoordinate(qx+1,-16,qz),order.get(16));
         assertEquals(new TellusBiomeVolumePlan.QuartCoordinate(qx,-12,qz),order.get(64));
         assertEquals(new TellusBiomeVolumePlan.QuartCoordinate(qx+3,79,qz+3),order.get(1535));
         for(int section=0;section<24;section++)for(int x=0;x<4;x++)for(int y=0;y<4;y++)for(int z=0;z<4;z++)
            assertEquals(new TellusBiomeVolumePlan.QuartCoordinate(qx+x,-16+section*4+y,qz+z),order.get(section*64+x*16+y*4+z));
         assertThrows(UnsupportedOperationException.class,()->order.clear());
      }
   }
   @Test void factoryRunsOnceAndResolverGetsEveryExactCoordinateAndMatchingAnchorContext() {
      var request=request(-7,9);AtomicInteger factories=new AtomicInteger(),resolves=new AtomicInteger();List<WriteContext> seen=new ArrayList<>();
      var planner=TellusBiomeVolumePlanner.fromResolver(req->{assertSame(request,req);factories.incrementAndGet();return (context,x,y,z)->{
         resolves.incrementAndGet();seen.add(context);return new BiomeDecision("fixture:vertical_"+y,true);};});
      var result=planner.plan(request);assertSame(request,result.request());assertEquals(DecisionStatus.AVAILABLE,result.status());assertEquals(1,factories.get());assertEquals(1536,resolves.get());
      var order=TellusBiomeVolumePlan.nativeCellOrder(request);
      for(int i=0;i<result.cells().size();i++){var cell=result.cells().get(i);var expected=order.get(i);
         assertEquals(expected,new TellusBiomeVolumePlan.QuartCoordinate(cell.quartX(),cell.quartY(),cell.quartZ()));
         assertSame(request.columns().get(Math.floorMod(cell.quartX(),4)+4*Math.floorMod(cell.quartZ(),4)),seen.get(i));
         assertEquals("fixture:vertical_"+cell.quartY(),cell.biome().id());assertTrue(cell.biome().known());}
      assertThrows(UnsupportedOperationException.class,()->result.cells().clear());assertThrows(UnsupportedOperationException.class,()->result.diagnostics().clear());
   }
   @Test void nonblockingIsFactoryColdAndQuantizedBucketDoesNotReplaceNativeWorldScale() {
      var generation=new TellusGenerationContext(42,1000,64,-64,320);AtomicInteger factories=new AtomicInteger(),resolves=new AtomicInteger();
      var planner=TellusBiomeVolumePlanner.fromResolver(req->{factories.incrementAndGet();assertEquals(1000.0,req.generation().worldScale());return (c,x,y,z)->{
         resolves.incrementAndGet();assertEquals(1000.0,c.generation().worldScale());return BiomeDecision.defaultBiome();};});
      var nonblocking=request(generation,0,0,SamplingResolution.nonBlocking(4096));var queued=planner.plan(nonblocking);
      assertEquals(DecisionStatus.QUEUED,queued.status());assertTrue(queued.cells().isEmpty());assertEquals(0,factories.get());assertEquals(0,resolves.get());
      var exact=request(generation,0,0,SamplingResolution.exact(1000));assertEquals(1024,exact.resolution().metres());
      assertEquals(DecisionStatus.AVAILABLE,planner.plan(exact).status());assertEquals(1,factories.get());assertEquals(1536,resolves.get());
      var invalid=columns(nonblocking,nonblocking.columns().subList(0,15));factoryCold(invalid);
   }
   @Test void unknownAfterKnownPrefixDiscardsAllCellsAndStopsFurtherSourceCalls() {
      AtomicInteger factories=new AtomicInteger(),calls=new AtomicInteger();var result=TellusBiomeVolumePlanner.fromResolver(req->{factories.incrementAndGet();return (c,x,y,z)->
         calls.incrementAndGet()==73?BiomeDecision.unknown():BiomeDecision.defaultBiome();}).plan(request(0,0));
      assertEquals(DecisionStatus.UNKNOWN,result.status());assertTrue(result.cells().isEmpty());assertEquals(1,factories.get());assertEquals(73,calls.get());
      assertThrows(UnsupportedOperationException.class,()->result.cells().add(new TellusBiomeVolumePlan.Cell(0,0,0,BiomeDecision.defaultBiome())));
   }
   @Test void everyFullProfileMismatchAliasesSlotReorderingAndApronFailBeforeFactory() {
      var valid=request(0,0);List<TellusGenerationContext> profiles=List.of(
         new TellusGenerationContext(43,1,64,-64,320),new TellusGenerationContext(42,2,64,-64,320),
         new TellusGenerationContext(42,1,65,-64,320),new TellusGenerationContext(42,1,64,-80,320),new TellusGenerationContext(42,1,64,-64,336),
         new TellusGenerationContext(42,1,64,-64,320,new GeometryIdentity("fixture:other","revision2","projection2","noise2",1)));
      for(var changed:profiles){var contexts=new ArrayList<>(valid.columns());contexts.set(15,WriteContext.canonical(valid.columns().get(15).targetCoordinate(),changed,"other-profile"));factoryCold(columns(valid,contexts));}
      var aliases=new ArrayList<>(valid.columns());aliases.set(15,aliases.get(0));factoryCold(columns(valid,aliases));
      var reordered=new ArrayList<>(valid.columns());Collections.swap(reordered,1,4);factoryCold(columns(valid,reordered));
      factoryCold(columns(valid,valid.columns().subList(0,15)));factoryCold(columns(valid,Collections.nCopies(17,valid.columns().get(0))));
      var mixed=new ArrayList<>(valid.columns());mixed.set(0,sphereRequest(MODEL).columns().get(0));factoryCold(columns(valid,mixed));
      var withApron=new ArrayList<>(valid.columns());withApron.set(0,apron());assertTrue(withApron.get(0).apronMirror());factoryCold(columns(valid,withApron));
   }
   @Test void boundsBudgetAndMinecraftBlockIntDomainAreCheckedBeforeAnyResolverFactory() {
      var valid=request(0,0);for(var generation:List.of(new TellusGenerationContext(42,1,64,-63,321),new TellusGenerationContext(42,1,64,-64,319),
         new TellusGenerationContext(42,1,64,-64,4048),new TellusGenerationContext(42,1,64,Integer.MIN_VALUE,Integer.MAX_VALUE))){
         var invalid=new TellusBiomeVolumePlan.Request(generation,0,0,valid.columns(),valid.resolution());factoryCold(invalid);
         assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.nativeCellOrder(invalid));
         assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.available(invalid,List.of(),List.of()));}
      for(int[] chunk:new int[][]{{Integer.MAX_VALUE,0},{Integer.MIN_VALUE,0},{0,Integer.MAX_VALUE},{0,Integer.MIN_VALUE},{134217728,0},{0,-134217729}}){
         var invalid=new TellusBiomeVolumePlan.Request(GENERATION,chunk[0],chunk[1],valid.columns(),valid.resolution());factoryCold(invalid);
         assertThrows(ArithmeticException.class,()->TellusBiomeVolumePlan.nativeCellOrder(invalid));}
      var maximum=new TellusGenerationContext(42,1,64,-2048,2048);var max=request(maximum,0,0,SamplingResolution.exact(1));
      assertEquals(16384,TellusBiomeVolumePlan.nativeCellOrder(max).size());TellusBiomeVolumePlanner.validateRequest(max);
   }
   @Test void defaultSphereCubeAndExplicitCustomModelsResolveCanonicalAnchorsWithoutGuessingTopology() {
      for(var request:List.of(sphereRequest(MODEL),cubeRequest(MODEL))){TellusBiomeVolumePlanner.validateRequest(request);
         assertEquals(1536,TellusBiomeVolumePlanner.fromResolver(req->(c,x,y,z)->BiomeDecision.defaultBiome()).plan(request).cells().size());}
      var custom=new SphereWorldModel(new SphereWorldModel.Config(1,48,16,3,1.0e9));
      for(var request:List.of(sphereRequest(custom),cubeRequest(custom))){
         TellusBiomeVolumePlanner.validateRequest(request,custom);assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlanner.validateRequest(request));
         assertEquals(1536,TellusBiomeVolumePlanner.fromResolver(req->(c,x,y,z)->BiomeDecision.defaultBiome(),custom).plan(request).cells().size());}
      var wrongScale=new SphereWorldModel(new SphereWorldModel.Config(2,48,16,3,1.0e9));AtomicInteger factories=new AtomicInteger();
      assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlanner.fromResolver(req->{factories.incrementAndGet();return (c,x,y,z)->BiomeDecision.defaultBiome();},wrongScale).plan(request(0,0)));assertEquals(0,factories.get());
   }
   @Test void availableConstructorRejectsMissingDuplicateMisorderedForeignAndUnknownCells() {
      var request=request(0,0);var cells=knownCells(request);assertEquals(1536,TellusBiomeVolumePlan.available(request,cells,List.of()).cells().size());
      assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.available(request,cells.subList(0,1535),List.of()));
      var duplicate=new ArrayList<>(cells);duplicate.set(1,cells.get(0));assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.available(request,duplicate,List.of()));
      var swapped=new ArrayList<>(cells);Collections.swap(swapped,4,16);assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.available(request,swapped,List.of()));
      var foreign=new ArrayList<>(cells);foreign.set(0,new TellusBiomeVolumePlan.Cell(4,-16,0,BiomeDecision.defaultBiome()));assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.available(request,foreign,List.of()));
      var unknown=new ArrayList<>(cells);unknown.set(0,new TellusBiomeVolumePlan.Cell(0,-16,0,BiomeDecision.unknown()));assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.available(request,unknown,List.of()));
      var extra=new ArrayList<>(cells);extra.add(cells.get(0));assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.available(request,extra,List.of()));
      assertThrows(IllegalArgumentException.class,()->new TellusBiomeVolumePlan(request,DecisionStatus.PARTIAL,List.of(),List.of()));
      for(var status:List.of(DecisionStatus.UNKNOWN,DecisionStatus.QUEUED))assertThrows(IllegalArgumentException.class,()->new TellusBiomeVolumePlan(request,status,cells,List.of()));
   }
   @Test void requestAndPlanCopyListsAndStructuralNullAndBlockedContractsStayStrict() {
      var valid=request(0,0);var contexts=new ArrayList<>(valid.columns());var request=columns(valid,contexts);contexts.clear();assertEquals(16,request.columns().size());assertThrows(UnsupportedOperationException.class,()->request.columns().clear());
      var cells=knownCells(request);var diagnostics=new ArrayList<>(List.of("complete"));var available=TellusBiomeVolumePlan.available(request,cells,diagnostics);cells.clear();diagnostics.clear();assertEquals(1536,available.cells().size());assertEquals(List.of("complete"),available.diagnostics());
      var unknown=TellusBiomeVolumePlan.blocked(request,DecisionStatus.UNKNOWN,"unknown");var queued=TellusBiomeVolumePlan.blocked(request,DecisionStatus.QUEUED,"queued");assertTrue(unknown.cells().isEmpty());assertTrue(queued.cells().isEmpty());
      assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.blocked(request,DecisionStatus.AVAILABLE,"bad"));assertThrows(IllegalArgumentException.class,()->TellusBiomeVolumePlan.blocked(request,DecisionStatus.PARTIAL,"bad"));
      assertThrows(NullPointerException.class,()->TellusBiomeVolumePlan.blocked(request,DecisionStatus.UNKNOWN,null));assertThrows(NullPointerException.class,()->new TellusBiomeVolumePlan.Cell(0,0,0,null));
      assertThrows(NullPointerException.class,()->new TellusBiomeVolumePlan.Request(null,0,0,valid.columns(),valid.resolution()));assertThrows(NullPointerException.class,()->new TellusBiomeVolumePlan.Request(GENERATION,0,0,null,valid.resolution()));assertThrows(NullPointerException.class,()->new TellusBiomeVolumePlan.Request(GENERATION,0,0,valid.columns(),null));
      var nullContext=new ArrayList<>(valid.columns());nullContext.set(15,null);assertThrows(NullPointerException.class,()->columns(valid,nullContext));
      assertThrows(NullPointerException.class,()->new TellusBiomeVolumePlan(null,DecisionStatus.UNKNOWN,List.of(),List.of()));assertThrows(NullPointerException.class,()->new TellusBiomeVolumePlan(request,null,List.of(),List.of()));assertThrows(NullPointerException.class,()->new TellusBiomeVolumePlan(request,DecisionStatus.UNKNOWN,null,List.of()));assertThrows(NullPointerException.class,()->new TellusBiomeVolumePlan(request,DecisionStatus.UNKNOWN,List.of(),null));
      var structuralOnly=new TellusBiomeVolumePlan.Request(GENERATION,Integer.MAX_VALUE,0,List.of(),valid.resolution());assertEquals(0,structuralOnly.columns().size());
   }
   @Test void nullResolverOutputsAndSourceFailuresPropagateWithoutInventingPartialResults() {
      var request=request(0,0);assertThrows(NullPointerException.class,()->TellusBiomeVolumePlanner.fromResolver(null));assertThrows(NullPointerException.class,()->TellusBiomeVolumePlanner.validateRequest(null));
      assertThrows(NullPointerException.class,()->TellusBiomeVolumePlanner.fromResolver(req->null).plan(request));AtomicInteger calls=new AtomicInteger();
      assertThrows(NullPointerException.class,()->TellusBiomeVolumePlanner.fromResolver(req->(c,x,y,z)->calls.incrementAndGet()==3?null:BiomeDecision.defaultBiome()).plan(request));assertEquals(3,calls.get());
      var expected=new IllegalStateException("resolver failed");assertSame(expected,assertThrows(IllegalStateException.class,()->TellusBiomeVolumePlanner.fromResolver(req->(c,x,y,z)->{throw expected;}).plan(request)));
      var factoryFailure=new IllegalStateException("factory failed");assertSame(factoryFailure,assertThrows(IllegalStateException.class,()->TellusBiomeVolumePlanner.fromResolver(req->{throw factoryFailure;}).plan(request)));
      assertEquals(DecisionStatus.AVAILABLE,TellusBiomeVolumePlanner.fromResolver(req->(c,x,y,z)->new BiomeDecision("Fixture:Unregistered ID",true)).plan(request).status(),"lexical and registry validation belongs to the MC writer");
   }
}
