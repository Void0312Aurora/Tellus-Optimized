package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.caves.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CubeUndergroundKernelNativeParityTest {
   @TempDir static Path directory;
   record Write(BlockPos pos,BlockState state,boolean fluid) { }
   @BeforeAll static void bootstrap() throws Exception { CubeNetNativeUndergroundStageTest.root=directory;CubeNetNativeUndergroundStageTest.init(); }
   @AfterAll static void restore() throws Exception { CubeNetNativeUndergroundStageTest.cleanup(); }

   @Test void actualSphericalSamplerMatchesFrozenOldLoopAcrossEighteenNativeChunks() {
      int writes=0,fluids=0;
      for(int face=0;face<6;face++)for(int profile=0;profile<3;profile++) {
         var r=CubeNetNativeUndergroundStageTest.request(face,profile,SamplingResolution.exact(1));
         var old=CubeNetNativeUndergroundStageTest.chunk(r);var actual=CubeNetNativeUndergroundStageTest.chunk(r);
         var oldWrites=new ArrayList<Write>();var actualWrites=new ArrayList<Write>();
         var oldReads=new ArrayList<Long>();var actualReads=new ArrayList<Long>();
         frozen(r,old,oldWrites,oldReads);
         var halo=r.surfaceHaloY();var flags=r.flags();
         var boxes=r.structureExclusions().stream().map(b->new UndergroundStructureExclusion.Box(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),b.carvingMargin(),b.blocksFeaturePlacement())).toList();
         new CubeNetSphericalCaveSampler().apply(r.model(),r.address(),new CubeNetSphericalCaveField(r.generation().worldSeed()),actual,
            r.generation().minY(),r.generation().seaLevel(),flags.applyCaves(),flags.cavesReachSurface(),flags.applyOreVeins(),flags.applyGeologicalStonePatches(),r.surfaceY(),
            flags.applyGeologicalStonePatches()?(x,z)->{actualReads.add(((long)x<<32)^(z&0xffffffffL));return halo[(z-r.chunkZ()*16+2)*20+x-r.chunkX()*16+2];}:null,
            r.floodGuardY(),r.generationFloorY(),boxes,(chunk,pos,state,fluid)->write((ProtoChunk)chunk,pos,state,fluid,actualWrites));
         assertEquals(oldReads,actualReads);assertEquals(oldWrites,actualWrites);
         assertEquals(CubeNetNativeUndergroundStageTest.before(old).blocks(),CubeNetNativeUndergroundStageTest.before(actual).blocks());
         for(int i=0;i<old.getPostProcessing().length;i++)assertEquals(old.getPostProcessing()[i],actual.getPostProcessing()[i]);
         writes+=actualWrites.size();fluids+=(int)actualWrites.stream().filter(Write::fluid).count();
      }
      assertTrue(writes>0);assertTrue(fluids>0);
      System.out.println("CUBE_UNDERGROUND_KERNEL_NATIVE chunks=18 comparedBlockStates=737280 writes="+writes+" fluidMarkers="+fluids+" mismatches=0 sourceAndWriteOrderEqual=true");
   }

   static void write(ProtoChunk chunk,BlockPos pos,BlockState state,boolean fluid,List<Write> trace) {
      trace.add(new Write(pos.immutable(),state,fluid));MinecraftVersionCompat.setBlockState(chunk,pos,state);
      if(fluid)MinecraftVersionCompat.markPosForPostProcessing(chunk,pos);
   }

   // Frozen pre-extraction native loop; it deliberately does not use the new kernel.
   static void frozen(UndergroundStagePlan.Request r,ProtoChunk chunk,List<Write> trace,List<Long> reads) {
      var flags=r.flags();var surfaces=r.surfaceY();var halo=r.surfaceHaloY();var flood=r.floodGuardY();var floor=r.generationFloorY();
      var field=new CubeNetSphericalCaveField(r.generation().worldSeed());int min=r.generation().minY(),sea=r.generation().seaLevel();
      int bx=r.chunkX()*16,bz=r.chunkZ()*16;var place=CubeNetLayout.standard(r.model().config().faceBlocks()).placement(CubeNetLayout.Face.of(r.address().face()));
      var exclusions=r.structureExclusions().stream().map(b->new UndergroundStructureExclusion.Box(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),b.carvingMargin(),b.blocksFeaturePlacement())).toList();
      for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
         int i=z*16+x,surface=surfaces[i],bottom=floor==null?min+1:Math.max(min+1,floor[i]+1);
         int first=Math.min(r.generation().maxYExclusive()-1,flags.applyCaves()&&flags.cavesReachSurface()?surface:surface-5);
         if(first<bottom)continue;
         int nearby=surface;
         if(flags.applyGeologicalStonePatches())for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++) {
            int hx=x+dx,hz=z+dz,value;
            if(hx>=0&&hx<16&&hz>=0&&hz<16)value=surfaces[hz*16+hx];
            else {int wx=bx+hx,wz=bz+hz;reads.add(((long)wx<<32)^(wz&0xffffffffL));value=halo[(hz+2)*20+hx+2];}
            nearby=Math.min(nearby,value);
         }
         var point=r.model().exactSurfacePoint(r.address().face(),r.address().blockX()+8.0+place.uAxisX()*(x-7.5)+place.uAxisZ()*(z-7.5),
            r.address().blockZ()+8.0+place.vAxisX()*(x-7.5)+place.vAxisZ()*(z-7.5));
         var column=field.column(point,r.model().config().worldScale());
         for(int y=first;y>=bottom;y--) {
            int depth=Math.max(0,surface-y);var position=column.atHeight(y-sea);
            boolean allowed=flags.applyCaves()&&!UndergroundStructureExclusion.blocksCarving(exclusions,bx+x,y,bz+z)&&(flood==null||y<flood[i]);
            BlockState replacement=null;boolean feature=false;
            if(allowed)replacement=switch(field.cavity(position,y-sea,depth,y,min)) {
               case AIR->Blocks.CAVE_AIR.defaultBlockState();case WATER->Blocks.WATER.defaultBlockState();case LAVA->Blocks.LAVA.defaultBlockState();case SOLID->null;
            };
            if(replacement==null&&(flags.applyOreVeins()||flags.applyGeologicalStonePatches())) {
               var rock=field.rockFeature(position,depth,flags.applyOreVeins(),flags.applyGeologicalStonePatches());
               replacement=switch(rock) {
                  case COPPER_ORE->flags.applyOreVeins()?Blocks.COPPER_ORE.defaultBlockState():null;
                  case DEEPSLATE_IRON_ORE->flags.applyOreVeins()?Blocks.DEEPSLATE_IRON_ORE.defaultBlockState():null;
                  case GRANITE->flags.applyGeologicalStonePatches()?Blocks.GRANITE.defaultBlockState():null;
                  case TUFF->flags.applyGeologicalStonePatches()?Blocks.TUFF.defaultBlockState():null;default->null;
               };
               if(replacement!=null&&(rock==CubeNetSphericalCaveField.Feature.GRANITE||rock==CubeNetSphericalCaveField.Feature.TUFF)&&y>nearby-2)replacement=null;
               feature=replacement!=null;
            }
            if(replacement==null)continue;
            var pos=new BlockPos(bx+x,y,bz+z);var current=chunk.getBlockState(pos);
            if(feature?!current.is(BlockTags.BASE_STONE_OVERWORLD):!current.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES))continue;
            write(chunk,pos,replacement,!replacement.getFluidState().isEmpty(),trace);
         }
      }
   }
}
