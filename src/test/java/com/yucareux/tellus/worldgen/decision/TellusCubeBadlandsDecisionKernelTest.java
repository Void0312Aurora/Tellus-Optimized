package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.worldgen.BadlandsTerrainPolicy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Frozen resolved-input native badlands rules; no Minecraft bootstrap, source reads or target storage. */
class TellusCubeBadlandsDecisionKernelTest {
   static final int[][] POINTS = {{0,0},{31,-17},{-4097,8193},{1_000_000,-1_000_000},
      {Integer.MAX_VALUE,Integer.MIN_VALUE},{-31,47}};
   static final SemanticMaterial[] BANDS = {SemanticMaterial.TERRACOTTA,SemanticMaterial.ORANGE_TERRACOTTA,
      SemanticMaterial.YELLOW_TERRACOTTA,SemanticMaterial.BROWN_TERRACOTTA,SemanticMaterial.RED_TERRACOTTA,
      SemanticMaterial.LIGHT_GRAY_TERRACOTTA,SemanticMaterial.WHITE_TERRACOTTA};

   @Test void resolvedKernelMatchesFrozenNativeAlgorithmAcrossCoordinatesThresholdsScalesAndHeights() {
      int cases=0,cells=0;
      for(int[] point:POINTS) for(int height:new int[]{-65,-64,-63,0,65,320})
         for(double scale:new double[]{1,10,1000}) for(double relief:new double[]{Double.NaN,-1,0,2,2.01,3,23,1000})
            for(double slope:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,-9,0,4.49,4.5,45}) {
               var actual=TellusCubeBadlandsDecisionKernel.resolve(-64,height,scale,relief,slope,point[0],point[1]);
               assertEquals(frozen(-64,height,scale,relief,slope,point[0],point[1]),actual,
                  "x="+point[0]+" z="+point[1]+" height="+height+" scale="+scale+" relief="+relief+" slope="+slope);
               cases++;cells+=actual.size();
            }
      assertEquals(6912,cases);
      System.out.println("CUBE_BADLANDS_PURE_KERNEL cases="+cases+" comparedCells="+cells+" mismatchCells=0");
   }

   @Test void allSevenBandMaterialsAndCurrentPlateauOutputsRetainNativeMapping() {
      EnumSet<SemanticMaterial> seenBands=EnumSet.noneOf(SemanticMaterial.class);
      for(int[] point:POINTS)for(int y=-128;y<128;y++) {
         var cells=TellusCubeBadlandsDecisionKernel.resolve(-1024,y,1,3,Double.NaN,point[0],point[1]);
         assertEquals(expectedBand(point[0],point[1],y),cells.get(0).material());seenBands.add(cells.get(0).material());
      }
      assertEquals(EnumSet.copyOf(List.of(BANDS)),seenBands);
      EnumSet<SemanticMaterial> seenPlateaus=EnumSet.noneOf(SemanticMaterial.class);
      for(int x=0;x<64;x++)for(int z=0;z<64;z++) {
         int px=-4096+x*13,pz=-4096+z*17;
         var cells=TellusCubeBadlandsDecisionKernel.resolve(-64,100,1,0,0,px,pz);
         assertEquals(List.of(new TellusCubeBadlandsDecisionKernel.Cell(100,expectedPlateau(px,pz))),cells);
         seenPlateaus.add(cells.get(0).material());
      }
      assertEquals(EnumSet.of(SemanticMaterial.RED_SAND,SemanticMaterial.TERRACOTTA),seenPlateaus,
         "Current policy emits red sand or ordinary terracotta; legacy brown/coarse switch cases stay future-compatible");
   }

   @Test void outputRemainsTopFirstAndRetainsTopEvenAtOrBelowLowerClipBoundary() {
      for(int min:new int[]{-64,0})for(int height:new int[]{-66,-64,-63,-1,0,1,65})for(double relief:new double[]{0,3,100}) {
         var cells=TellusCubeBadlandsDecisionKernel.resolve(min,height,1,relief,0,31,-17);
         assertFalse(cells.isEmpty());assertEquals(height,cells.get(0).y());
         for(int i=1;i<cells.size();i++)assertEquals(cells.get(i-1).y()-1,cells.get(i).y());
         if(height<=min)assertEquals(1,cells.size(),"Native top write precedes the descending clipping loop");
         else if(relief>=3)assertTrue(cells.get(cells.size()-1).y()>=min+1);
      }
   }

   @Test void ceilReliefThresholdAndFiniteSlopeFallbackKeepOriginalPrecedence() {
      assertEquals(1,TellusCubeBadlandsDecisionKernel.resolve(-1000,100,10,20,0,0,0).size());
      assertEquals(24,TellusCubeBadlandsDecisionKernel.resolve(-1000,100,10,Math.nextUp(20.0),0,0,0).size(),
         "ceil makes relief just above two blocks become the third block");
      assertEquals(1,TellusCubeBadlandsDecisionKernel.resolve(-1000,100,10,20,4.49,0,0).size());
      assertEquals(24,TellusCubeBadlandsDecisionKernel.resolve(-1000,100,10,20,4.5,0,0).size());
      for(double absent:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
         assertEquals(1,TellusCubeBadlandsDecisionKernel.resolve(-1000,100,10,Double.NaN,absent,0,0).size());
         assertEquals(24,TellusCubeBadlandsDecisionKernel.resolve(-1000,100,10,Double.NaN,4.5,0,0).size());
         assertEquals(24,TellusCubeBadlandsDecisionKernel.resolve(-1000,100,10,30,absent,0,0).size());
      }
      assertEquals(24,TellusCubeBadlandsDecisionKernel.resolve(-1000,100,10,30,-90,0,0).size(),
         "Already-three-block relief must not be replaced by negative slope");
      assertEquals(frozen(-1000,100,10,Double.NaN,Double.MAX_VALUE,0,0),
         TellusCubeBadlandsDecisionKernel.resolve(-1000,100,10,Double.NaN,Double.MAX_VALUE,0,0),
         "Preserve round-to-long, narrow-to-int, multiply-three order for finite extreme inputs");
   }

   @Test void cliffMinimumDepthCapAndBedrockSideClippingStayUnchanged() {
      assertEquals(24,TellusCubeBadlandsDecisionKernel.resolve(-100_000,1000,1,3,0,0,0).size());
      assertEquals(68,TellusCubeBadlandsDecisionKernel.resolve(-100_000,1000,1,60,0,0,0).size());
      var capped=TellusCubeBadlandsDecisionKernel.resolve(-100_000,1000,1,Double.MAX_VALUE,0,0,0);
      assertEquals(65536,capped.size());assertEquals(1000,capped.get(0).y());assertEquals(1000-65536+1,capped.get(capped.size()-1).y());
      var clipped=TellusCubeBadlandsDecisionKernel.resolve(985,1000,1,100,0,0,0);
      assertEquals(15,clipped.size());assertEquals(986,clipped.get(clipped.size()-1).y());
   }

   @Test void resultListsAreImmutableAndCellsRejectMissingOrUnknownMaterial() {
      var cells=TellusCubeBadlandsDecisionKernel.resolve(-64,100,1,3,0,0,0);
      assertThrows(UnsupportedOperationException.class,()->cells.add(new TellusCubeBadlandsDecisionKernel.Cell(0,SemanticMaterial.TERRACOTTA)));
      assertThrows(UnsupportedOperationException.class,()->cells.set(0,new TellusCubeBadlandsDecisionKernel.Cell(0,SemanticMaterial.RED_SAND)));
      assertThrows(UnsupportedOperationException.class,cells::clear);
      assertThrows(NullPointerException.class,()->new TellusCubeBadlandsDecisionKernel.Cell(0,null));
      assertThrows(IllegalArgumentException.class,()->new TellusCubeBadlandsDecisionKernel.Cell(0,SemanticMaterial.UNKNOWN));
   }

   @Test void inheritedMinimumScaleDenominatorAndNonfiniteReliefBehaviorAreNotReinterpreted() {
      var minimum=TellusCubeBadlandsDecisionKernel.resolve(-64,100,0.0001,0.0003,0,31,-17);
      for(double scale:new double[]{-1,0,0.00001,0.0001})assertEquals(minimum,
         TellusCubeBadlandsDecisionKernel.resolve(-64,100,scale,0.0003,0,31,-17));
      for(double relief:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
         assertEquals(frozen(-64,100,1000,relief,0,31,-17),TellusCubeBadlandsDecisionKernel.resolve(-64,100,1000,relief,0,31,-17));
      }
      // Production worldScale is finite/positive; this only freezes existing resolved arithmetic, not world-profile admission.
      assertEquals(frozen(-64,100,Double.NaN,1000,0,31,-17),TellusCubeBadlandsDecisionKernel.resolve(-64,100,Double.NaN,1000,0,31,-17));
   }

   /** Frozen pre-Round53 native algorithm on supported build-height ranges. */
   static List<TellusCubeBadlandsDecisionKernel.Cell> frozen(int minY,int surfaceY,double scale,double relief,double slope,int x,int z) {
      int local=Double.isFinite(relief)?Math.max(0,(int)Math.ceil(relief/Math.max(0.0001,scale))):0;
      if(local<3&&Double.isFinite(slope))local=Math.max(0,(int)Math.round(slope/9.0)*3);
      List<TellusCubeBadlandsDecisionKernel.Cell> cells=new ArrayList<>();
      if(local<3){cells.add(new TellusCubeBadlandsDecisionKernel.Cell(surfaceY,expectedPlateau(x,z)));return List.copyOf(cells);}
      int depth=BadlandsTerrainPolicy.cliffBandDepth(3,local),bottom=Math.max(minY+1,surfaceY-depth+1);
      cells.add(new TellusCubeBadlandsDecisionKernel.Cell(surfaceY,expectedBand(x,z,surfaceY)));
      for(int y=surfaceY-1;y>=bottom;y--)cells.add(new TellusCubeBadlandsDecisionKernel.Cell(y,expectedBand(x,z,y)));
      return List.copyOf(cells);
   }
   static SemanticMaterial expectedBand(int x,int z,int y){return BANDS[BadlandsTerrainPolicy.bandMaterialIndex(x,z,y)];}
   static SemanticMaterial expectedPlateau(int x,int z){
      return switch(BadlandsTerrainPolicy.plateauMaterialIndex(x,z)) {
         case BadlandsTerrainPolicy.PLATEAU_TERRACOTTA,BadlandsTerrainPolicy.PLATEAU_BROWN_TERRACOTTA->SemanticMaterial.TERRACOTTA;
         case BadlandsTerrainPolicy.PLATEAU_COARSE_DIRT->SemanticMaterial.COARSE_DIRT;
         default->SemanticMaterial.RED_SAND;
      };
   }
}
