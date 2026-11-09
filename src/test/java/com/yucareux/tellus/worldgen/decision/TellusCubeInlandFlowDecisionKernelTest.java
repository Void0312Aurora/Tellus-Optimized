package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Frozen native range arithmetic; genuine hydrology and metadata transactions belong to native integration tests. */
class TellusCubeInlandFlowDecisionKernelTest {
   @Test void resolvedBandsMatchTheFrozenNativeLoopAcrossBuildRangesHeightsStatusesAndLevels() {
      int cases=0,bands=0;long comparedCells=0;
      for(int[] bounds:new int[][]{{-64,320},{0,256},{-2032,2032}})
         for(int water:new int[]{-2050,-65,-64,-63,0,1,63,64,255,256,319,320,2050})
            for(int terrain:new int[]{-2051,-65,-64,-1,0,63,255,319,2048})
               for(boolean wet:new boolean[]{false,true})for(int level:new int[]{-8,0,1,2,7,8,9,Integer.MAX_VALUE})
                  for(boolean falling:new boolean[]{false,true}) {
                     var expected=frozen(bounds[0],bounds[1],wet,level,falling,water,terrain);
                     var actual=TellusCubeInlandFlowDecisionKernel.resolve(bounds[0],bounds[1],wet,level,falling,water,terrain);
                     assertEquals(expected,actual,"bounds="+bounds[0]+".."+bounds[1]+" wet="+wet+" level="+level+" falling="+falling+" water="+water+" terrain="+terrain);
                     cases++;if(expected.isPresent()){bands++;comparedCells+=(long)expected.orElseThrow().topY()-expected.orElseThrow().bottomY()+1;}
                  }
      assertEquals(11232,cases);assertTrue(bands>0);assertTrue(comparedCells>0);
      System.out.println("CUBE_INLAND_FLOW_PURE_KERNEL cases="+cases+" bands="+bands+" comparedCells="+comparedCells+" mismatches=0");
   }
   @Test void allEightPositiveLevelsAreRetainedAndLargerNativeLevelsClampToEight() {
      for(int level=1;level<=8;level++)assertEquals(Optional.of(new TellusCubeInlandFlowDecisionKernel.Band(64,64,level)),
         TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,level,false,64,-900));
      for(int level:new int[]{9,255,Integer.MAX_VALUE})assertEquals(8,
         TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,level,false,64,0).orElseThrow().level());
   }
   @Test void fallingStartsAboveResolvedTerrainAndClipsBothInclusiveEnds() {
      assertEquals(Optional.of(new TellusCubeInlandFlowDecisionKernel.Band(121,179,8)),
         TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,8,true,179,120));
      assertEquals(Optional.of(new TellusCubeInlandFlowDecisionKernel.Band(-64,319,2)),
         TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,2,true,400,-100));
      assertEquals(Optional.of(new TellusCubeInlandFlowDecisionKernel.Band(64,64,1)),
         TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,1,true,64,63));
      assertTrue(TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,1,true,64,64).isEmpty());
      // Preserve the native int addition order; valid engine build bounds still limit the resulting band.
      assertEquals(Optional.of(new TellusCubeInlandFlowDecisionKernel.Band(-64,64,8)),
         TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,8,true,64,Integer.MAX_VALUE));
   }
   @Test void NoWaterZeroNegativeLevelAndFullyClippedBandsStayEmptyWithoutInventedWrites() {
      for(int level:new int[]{Integer.MIN_VALUE,-1,0,1,8,Integer.MAX_VALUE})assertTrue(
         TellusCubeInlandFlowDecisionKernel.resolve(-64,320,false,level,true,64,0).isEmpty());
      for(int level:new int[]{Integer.MIN_VALUE,-1,0})assertTrue(
         TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,level,true,64,0).isEmpty());
      assertTrue(TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,8,false,320,-100).isEmpty());
      assertTrue(TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,8,false,-65,-100).isEmpty());
      assertTrue(TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,8,true,64,320).isEmpty());
      assertTrue(TellusCubeInlandFlowDecisionKernel.resolve(64,64,true,8,true,64,0).isEmpty());
      assertTrue(TellusCubeInlandFlowDecisionKernel.resolve(64,-64,true,8,true,64,0).isEmpty());
      assertEquals(TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,3,false,64,Integer.MIN_VALUE),
         TellusCubeInlandFlowDecisionKernel.resolve(-64,320,true,3,false,64,Integer.MAX_VALUE));
   }
   @Test void immutableBandRejectsEmptyRangesAndLevelsOutsideTheTypedContract() {
      var band=new TellusCubeInlandFlowDecisionKernel.Band(-64,319,8);
      assertEquals(-64,band.bottomY());assertEquals(319,band.topY());assertEquals(8,band.level());
      assertThrows(IllegalArgumentException.class,()->new TellusCubeInlandFlowDecisionKernel.Band(65,64,1));
      for(int level:new int[]{Integer.MIN_VALUE,-1,0,9,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,
         ()->new TellusCubeInlandFlowDecisionKernel.Band(64,64,level));
   }
   private static Optional<TellusCubeInlandFlowDecisionKernel.Band> frozen(int minY,int maxY,boolean hasWater,
      int flowLevel,boolean falling,int waterY,int terrainY) {
      if(!hasWater||flowLevel<=0)return Optional.empty();
      int bottom=falling?terrainY+1:waterY;
      int first=Integer.MAX_VALUE,last=Integer.MIN_VALUE;
      for(int y=Math.max(minY,bottom);y<=Math.min(maxY-1,waterY);y++) {if(first==Integer.MAX_VALUE)first=y;last=y;}
      return first==Integer.MAX_VALUE?Optional.empty():Optional.of(new TellusCubeInlandFlowDecisionKernel.Band(first,last,
         Math.max(1,Math.min(8,flowLevel))));
   }
}
