package com.yucareux.tellus.worldgen.caves;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import com.yucareux.tellus.worldgen.decision.TellusEarthUndergroundDecisionKernel;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.IntBinaryOperator;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;

/** Exercises the actual production projection over resolved native fields and native ProtoChunks. */
class TellusEarthUndergroundProductionTest {
   static final int MIN = -64, HEIGHT = 160, SEA = 64;
   static final ChunkPos POS = new ChunkPos(-3, 5);
   static final Map<Holder.Reference<Block>, List<TagKey<Block>>> OLD_TAGS = new HashMap<>();
   static Registry<Biome> registry;
   static Constructor<?> constructor;
   static final List<BlockState> SAMPLES = List.of(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(),
      Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.COPPER_ORE.defaultBlockState(),
      Blocks.RAW_COPPER_BLOCK.defaultBlockState(), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
      Blocks.RAW_IRON_BLOCK.defaultBlockState(), Blocks.GRANITE.defaultBlockState(), Blocks.TUFF.defaultBlockState(),
      Blocks.IRON_ORE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState());

   @BeforeAll static void bootstrap() throws Exception {
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      var lookup = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME);
      var mapped = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
      Registry.register(mapped, Biomes.PLAINS, lookup.getOrThrow(Biomes.PLAINS).value()); registry = mapped.freeze();
      constructor = ProtoChunk.class.getDeclaredConstructor(ChunkPos.class, UpgradeData.class, LevelHeightAccessor.class,
         Registry.class, Class.forName("net.minecraft.world.level.levelgen.blending.BlendingData")); constructor.setAccessible(true);
      Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class); bind.setAccessible(true);
      for (Block block : List.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT)) {
         var holder = BuiltInRegistries.BLOCK.getHolderOrThrow(BuiltInRegistries.BLOCK.getResourceKey(block).orElseThrow());
         OLD_TAGS.put(holder, holder.tags().toList()); var tags = new HashSet<>(OLD_TAGS.get(holder));
         tags.add(BlockTags.OVERWORLD_CARVER_REPLACEABLES);
         if (block != Blocks.DIRT) tags.add(BlockTags.BASE_STONE_OVERWORLD);
         bind.invoke(holder, tags);
      }
   }

   @AfterAll static void restoreTags() throws Exception {
      Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class); bind.setAccessible(true);
      for (var entry : OLD_TAGS.entrySet()) bind.invoke(entry.getKey(), entry.getValue());
   }

   @Test void nativeMaterialDecisionsMatchFrozenProductionRules() {
      for (BlockState state : SAMPLES) for (int flags = 0; flags < 32; flags++)
         for (int y : new int[]{-64, 0, 63, 64, 65, Integer.MAX_VALUE})
            for (int nearby : new int[]{Integer.MIN_VALUE, 64, 70, Integer.MAX_VALUE}) {
               boolean caves=(flags&1)!=0, blocked=(flags&2)!=0, flooded=(flags&4)!=0, ores=(flags&8)!=0, geology=(flags&16)!=0;
               var decision = TellusEarthUndergroundDecisionKernel.resolve(TellusVanillaNoiseCaveSampler.classify(state),
                  y, SEA, caves, blocked, flooded, ores, geology, nearby);
               assertSame(legacyReplacement(state, y, nearby, caves && !blocked && !flooded, ores, geology),
                  TellusVanillaNoiseCaveSampler.materialize(decision, state));
            }
   }

   @Test void wholeNativeProjectionMatchesAllCellsSourceOrderWritesAndFluidMarkers() throws Exception {
      var field = field(); int[] surfaces = new int[256], flood = new int[256], floor = new int[256];
      for (int z=0;z<16;z++) for(int x=0;x<16;x++) {
         surfaces[z*16+x] = surface(x,z); flood[z*16+x] = 40 + x%5; floor[z*16+x] = MIN+z%4;
      }
      var exclusions = List.of(new UndergroundStructureExclusion.Box(POS.getMinBlockX()+4,0,POS.getMinBlockZ()+4,
         POS.getMinBlockX()+8,30,POS.getMinBlockZ()+8,1,true));
      int writes = 0;
      for (int flags=1;flags<16;flags++) {
         if ((flags & 13) == 0) continue;
         boolean caves=(flags&1)!=0, entrances=(flags&2)!=0, ores=(flags&4)!=0, geology=(flags&8)!=0;
         ProtoChunk old=chunk(), actual=chunk(); var oldSources=new ArrayList<Long>(); var sources=new ArrayList<Long>();
         var oldWrites=new ArrayList<String>(); var actualWrites=new ArrayList<String>();
         int[] guards=(flags&2)==0?flood:null, floors=(flags&4)==0?floor:null;
         legacyProjection(field,old,caves,entrances,ores,geology,surfaces,sampler(oldSources),guards,floors,exclusions,writer(oldWrites));
         TellusVanillaNoiseCaveSampler.applyField(field,actual,MIN,SEA,caves,entrances,ores,geology,surfaces,
            sampler(sources),guards,floors,exclusions,writer(actualWrites));
         assertEquals(oldSources,sources,"source trace flags="+flags);
         assertEquals(oldWrites,actualWrites,"write/marker trace flags="+flags); writes+=actualWrites.size();
         BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
         for(int y=MIN;y<MIN+HEIGHT;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            cursor.set(POS.getMinBlockX()+x,y,POS.getMinBlockZ()+z);
            assertSame(old.getBlockState(cursor),actual.getBlockState(cursor));
         }
         for(int section=0;section<old.getPostProcessing().length;section++)
            assertEquals(old.getPostProcessing()[section],actual.getPostProcessing()[section]);
         assertEquals(old.isUnsaved(),actual.isUnsaved());
      }
      assertTrue(writes>1000,"matrix must actually carve/place native cells");
   }

   static int surface(int x,int z) { return 70+Math.floorMod(x,4)*3-Math.floorMod(z,3)*2; }
   static IntBinaryOperator sampler(List<Long> trace) { return (x,z)->{trace.add(((long)x<<32)^(z&0xffffffffL));return surface(x-POS.getMinBlockX(),z-POS.getMinBlockZ());}; }
   static ProtoChunk chunk() throws Exception {
      var chunk=(ProtoChunk)constructor.newInstance(POS,UpgradeData.EMPTY,LevelHeightAccessor.create(MIN,HEIGHT),registry,null);
      var cursor=new BlockPos.MutableBlockPos();
      for(int y=MIN;y<MIN+HEIGHT;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
         cursor.set(POS.getMinBlockX()+x,y,POS.getMinBlockZ()+z);
         var state = switch(Math.floorMod(x+z+y,11)) { case 0->Blocks.DIRT;case 1->Blocks.BEDROCK;default->Blocks.STONE; };
         MinecraftVersionCompat.setBlockState(chunk,cursor,state.defaultBlockState());
      }
      MinecraftVersionCompat.markPosForPostProcessing(chunk,new BlockPos(POS.getMinBlockX(),0,POS.getMinBlockZ()));
      chunk.setUnsaved(false);return chunk;
   }
   static TellusVanillaNoiseCaveSampler.VanillaField field() {
      var states=new BlockState[128*256];var preliminary=new int[256];Arrays.fill(preliminary,63);
      for(int y=MIN;y<64;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
         states[(y-MIN)*256+z*16+x]=y>52?Blocks.AIR.defaultBlockState():SAMPLES.get(Math.floorMod(x+z*3+y*7,SAMPLES.size()));
      return new TellusVanillaNoiseCaveSampler.VanillaField(MIN,128,states,preliminary);
   }
   static TellusVanillaNoiseCaveSampler.CaveBlockWriter writer(List<String> trace) {
      return (chunk,pos,state,fluid)->{
         trace.add(pos.immutable()+":"+state+":"+fluid);MinecraftVersionCompat.setBlockState(chunk,pos,state);
         if(fluid)MinecraftVersionCompat.markPosForPostProcessing((ProtoChunk)chunk,pos);
      };
   }

   // Original material choice, independent of the new classification and kernel.
   static BlockState legacyReplacement(BlockState state,int y,int nearby,boolean caves,boolean ores,boolean geology) {
      BlockState replacement=null;
      if(caves) {
         if(state.isAir())replacement=Blocks.CAVE_AIR.defaultBlockState();
         else if(state.is(Blocks.LAVA))replacement=Blocks.LAVA.defaultBlockState();
         else if(!state.getFluidState().isEmpty())replacement=y<=SEA?Blocks.WATER.defaultBlockState():Blocks.CAVE_AIR.defaultBlockState();
      }
      if(replacement==null) {
         if(ores && (state.is(Blocks.COPPER_ORE)||state.is(Blocks.RAW_COPPER_BLOCK)||state.is(Blocks.DEEPSLATE_IRON_ORE)||state.is(Blocks.RAW_IRON_BLOCK)))replacement=state;
         if(geology && (state.is(Blocks.GRANITE)||state.is(Blocks.TUFF)) && y<=nearby-2)replacement=state;
      }
      return replacement;
   }
   static void legacyProjection(TellusVanillaNoiseCaveSampler.VanillaField field,ProtoChunk chunk,
      boolean caves,boolean entrances,boolean ores,boolean geology,int[] surfaces,IntBinaryOperator source,
      int[] flood,int[] floor,List<UndergroundStructureExclusion.Box> exclusions,TellusVanillaNoiseCaveSampler.CaveBlockWriter writer) {
      int[] halo=new int[400];
      if(geology)for(int z=0;z<20;z++)for(int x=0;x<20;x++)halo[z*20+x]=source.applyAsInt(POS.getMinBlockX()+x-2,POS.getMinBlockZ()+z-2);
      boolean expose=caves&&entrances;var cursor=new BlockPos.MutableBlockPos();
      for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
         int i=z*16+x,surface=surfaces[i],virtualSurface=field.surfaceReferenceY(x,z,expose);
         if(virtualSurface<=MIN)continue;
         int bottom=floor==null?MIN+1:Math.max(MIN+1,floor[i]+1),nearby=Integer.MAX_VALUE;
         if(geology)for(int dz=0;dz<5;dz++)for(int dx=0;dx<5;dx++)nearby=Math.min(nearby,halo[(z+dz)*20+x+dx]);
         for(int y=expose?surface:surface-5;y>=bottom;y--) {
            int wx=POS.getMinBlockX()+x,wz=POS.getMinBlockZ()+z;
            boolean allowed=caves&&!UndergroundStructureExclusion.blocksCarving(exclusions,wx,y,wz)&&(flood==null||y<flood[i]);
            BlockState sampled=field.state(x,TellusCaveDepthMapper.virtualYForActualY(y,surface,bottom,virtualSurface,MIN),z);
            BlockState replacement=legacyReplacement(sampled,y,nearby,allowed,ores,geology);
            if(replacement==null)continue;
            boolean feature=!replacement.isAir()&&replacement.getFluidState().isEmpty();cursor.set(wx,y,wz);
            BlockState current=chunk.getBlockState(cursor);
            if(feature?!current.is(BlockTags.BASE_STONE_OVERWORLD):!current.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES))continue;
            writer.set(chunk,cursor,replacement,!replacement.getFluidState().isEmpty());
         }
      }
   }
}
