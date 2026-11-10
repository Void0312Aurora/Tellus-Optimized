package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.yucareux.tellus.worldgen.decision.TellusDecorationDecisionKernel.Stage;

class TellusDecorationDecisionKernelTest {
   @Test void earthPlansRetainAllSixteenOriginalConditionalOrdersAndProfilingSteps() {
      for(int bits=0;bits<16;bits++) {
         boolean delay=(bits&1)!=0, trees=(bits&2)!=0, buildings=(bits&4)!=0, roads=(bits&8)!=0;
         var expected=new ArrayList<Stage>();
         if(!delay&&!trees)expected.add(Stage.TREES);
         if(!delay&&!buildings)expected.add(Stage.BUILDINGS);
         if(!delay)expected.add(Stage.REALTIME_SNOW);
         if(!delay&&!roads)expected.add(Stage.ROAD_LIGHTS);
         if(!delay&&(roads||buildings||trees))expected.add(Stage.DEFERRED_DETAILS);
         if(!delay)expected.add(Stage.LANDMARKS);
         var actualExecuted=new ArrayList<Stage>();
         for(var stage:TellusDecorationDecisionKernel.earthOrder()) {
            boolean nativeFlag=switch(stage) {case TREES->trees;case BUILDINGS->buildings;case ROAD_LIGHTS->roads;
               case DEFERRED_DETAILS->roads||buildings||trees;default->false;};
            if(TellusDecorationDecisionKernel.executeEarth(stage,delay,nativeFlag))actualExecuted.add(stage);
         }
         assertEquals(expected,actualExecuted);
         if(!delay)assertEquals(Stage.LANDMARKS,actualExecuted.get(actualExecuted.size()-1));
         assertThrows(UnsupportedOperationException.class,()->TellusDecorationDecisionKernel.earthOrder().clear());
      }
   }

   @Test void cubePlansRetainCanonicalOwnerStagesAndApronMirrorOrder() {
      assertEquals(List.of(Stage.VANILLA,Stage.AXOLOTLS,Stage.TREES,Stage.REALTIME_SNOW,
         Stage.CUBE_FEATURES,Stage.MIRROR_CANONICAL_VIEW),
         TellusDecorationDecisionKernel.cube(true));
      assertEquals(List.of(Stage.CUBE_FEATURES,Stage.MIRROR_CANONICAL_VIEW),
         TellusDecorationDecisionKernel.cube(false));
      assertThrows(UnsupportedOperationException.class,()->TellusDecorationDecisionKernel.cube(false).clear());
   }
}
