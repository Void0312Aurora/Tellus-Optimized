package com.yucareux.tellus.worldgen.feature;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Pure ordered-stage contracts; no source reads, writers, engine or durable storage. */
class TellusFeaturePlannerSequenceTest {
   static final SphereWorldModel MODEL=SphereWorldModel.forWorldScale(1000);
   static final TellusCoordinateContext C=TellusCoordinateContextFactory.fromCubeNetCanonical(MODEL,CubeNetLayout.standard(MODEL.config().faceBlocks()),new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X,320.5,480.5));
   static final TellusGenerationContext G=new TellusGenerationContext(42,1000,64,-64,320);
   static final TellusSampleRequest R=TellusSampleRequest.fromContext(C,SamplingPurpose.TERRAIN_COLUMN,SamplingResolution.exact(1000),G.geometry(),42);
   static final TellusSample SAMPLE=new TellusSample(R,SampleStatus.AVAILABLE,new TellusElevationSample(SampleStatus.AVAILABLE,36000),new TellusCoverSample(SampleStatus.AVAILABLE,30,30),TellusWaterSample.dry(),TellusSampleProvenance.snapshot("sequence","r55",true,"fixture"));
   static final ColumnDecision COLUMN=new ColumnDecision(C,DecisionStatus.AVAILABLE,OptionalInt.of(100),SurfaceDecision.defaultStone(),WaterDecision.dry(),BiomeDecision.defaultBiome(),StrataDecision.stone(-64,100),List.of());
   static FeaturePlan run(TellusFeaturePlanner planner){return planner.plan(G,C,COLUMN,SAMPLE);}
   static FeaturePlacement ordinary(){return FeaturePlacement.canonical(FeatureKind.CUSTOM,"badlands",C,PlacementOperation.SET,PlacementConflictPolicy.OWNER_ONLY,SemanticMaterial.TERRACOTTA,0,100,0,1,Map.of());}
   @Test void orderedStagesRetainExactPlacementObjectsAndTypedFlowBeforeBadlands(){
      List<String> calls=new ArrayList<>();var flow=FeaturePlacement.canonicalFlow(C,"flow",110,2,8);var badlands=ordinary();
      TellusFeaturePlanner first=(g,c,column,sample)->{assertSame(G,g);assertSame(C,c);assertSame(COLUMN,column);assertSame(SAMPLE,sample);calls.add("flow");return FeaturePlan.available(flow);};
      TellusFeaturePlanner second=(g,c,column,sample)->{calls.add("badlands");return FeaturePlan.available(badlands);};
      var result=run(TellusFeaturePlanner.sequence(first,second));assertEquals(List.of("flow","badlands"),calls);
      assertEquals(FeaturePlanStatus.AVAILABLE,result.status());assertSame(flow,result.placements().get(0));assertSame(badlands,result.placements().get(1));assertEquals(8,result.placements().get(0).flowState().orElseThrow().level());
      assertThrows(UnsupportedOperationException.class,()->result.placements().clear());
   }
   @Test void blockedLaterStageDiscardsEarlierPlacementsAndDoesNotInvokeFollowingStages(){
      for(var blocked:List.of(FeaturePlan.unknown("unknown"),FeaturePlan.queued("queued"))){
         AtomicInteger calls=new AtomicInteger();TellusFeaturePlanner first=(g,c,column,sample)->{calls.incrementAndGet();return FeaturePlan.available(ordinary());};
         TellusFeaturePlanner stop=(g,c,column,sample)->{calls.incrementAndGet();return blocked;};
         TellusFeaturePlanner last=(g,c,column,sample)->{fail("blocked stage must stop sequence");return FeaturePlan.empty("unreachable");};
         var result=run(TellusFeaturePlanner.sequence(first,stop,last));assertSame(blocked,result);assertTrue(result.placements().isEmpty());assertEquals(2,calls.get());
      }
   }
   @Test void emptyStagesPreserveDiagnosticsAndRemainExplicitlyEmpty(){
      assertEquals(FeaturePlanStatus.EMPTY,run(TellusFeaturePlanner.sequence()).status());
      var result=run(TellusFeaturePlanner.sequence((g,c,col,s)->FeaturePlan.empty("one"),(g,c,col,s)->FeaturePlan.empty("two")));
      assertEquals(FeaturePlanStatus.EMPTY,result.status());assertTrue(result.placements().isEmpty());assertEquals(List.of("one","two"),result.diagnostics());
      assertThrows(UnsupportedOperationException.class,()->result.diagnostics().clear());
   }
   @Test void terminalColumnAndSampleInputsStayStageCold(){
      TellusFeaturePlanner cold=(g,c,column,sample)->{throw new AssertionError("terminal stages must remain cold");};var sequence=TellusFeaturePlanner.sequence(cold);
      assertEquals(FeaturePlanStatus.UNKNOWN,sequence.plan(G,C,ColumnDecision.unknown(C,"unknown"),SAMPLE).status());
      assertEquals(FeaturePlanStatus.QUEUED,sequence.plan(G,C,ColumnDecision.unavailable(C,DecisionStatus.QUEUED,"queued"),SAMPLE).status());
      var queuedRequest=TellusSampleRequest.fromContext(C,SamplingPurpose.TERRAIN_COLUMN,SamplingResolution.nonBlocking(1000),G.geometry(),42);
      assertEquals(FeaturePlanStatus.QUEUED,sequence.plan(G,C,COLUMN,TellusSample.queued(queuedRequest,"sequence","r55","queued")).status());
   }
   @Test void stageArrayIsCopiedAndInvalidStagesOrNullResultsFailBeforeWrites(){
      TellusFeaturePlanner original=(g,c,col,s)->FeaturePlan.empty("original");TellusFeaturePlanner[] stages={original};var sequence=TellusFeaturePlanner.sequence(stages);
      stages[0]=(g,c,col,s)->{throw new AssertionError("mutable caller array leaked");};assertEquals(List.of("original"),run(sequence).diagnostics());
      assertThrows(NullPointerException.class,()->TellusFeaturePlanner.sequence((TellusFeaturePlanner[])null));
      assertThrows(NullPointerException.class,()->TellusFeaturePlanner.sequence((TellusFeaturePlanner)null));
      assertThrows(NullPointerException.class,()->run(TellusFeaturePlanner.sequence((g,c,col,s)->null)));
   }
   @Test void allInvocationArgumentsRemainRequiredEvenForEmptySequence(){
      var empty=TellusFeaturePlanner.sequence();assertThrows(NullPointerException.class,()->empty.plan(null,C,COLUMN,SAMPLE));
      assertThrows(NullPointerException.class,()->empty.plan(G,null,COLUMN,SAMPLE));assertThrows(NullPointerException.class,()->empty.plan(G,C,null,SAMPLE));assertThrows(NullPointerException.class,()->empty.plan(G,C,COLUMN,null));
   }
}
