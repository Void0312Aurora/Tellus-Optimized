package com.yucareux.tellus.worldgen.caves;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import com.yucareux.tellus.worldgen.decision.TellusCarverDecisionKernel;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.carver.*;
import org.junit.jupiter.api.*;

class TellusCarverProductionTest {
   @BeforeAll static void bootstrap() throws Exception { TellusEarthUndergroundProductionTest.bootstrap(); }
   @AfterAll static void cleanup() throws Exception { TellusEarthUndergroundProductionTest.restoreTags(); }

   @Test void actualConfiguredCarversRetainFrozenBoundsAndNativeOrder() throws Exception {
      for(int depth:new int[]{0,16,512,4096}) {
         var nativeCarvers=TellusConfiguredCarvers.create(BuiltInRegistries.BLOCK,-64,160,depth);
         for(int input:new int[]{Integer.MIN_VALUE,-65,-64,-40,0,60,95,96,Integer.MAX_VALUE}) {
            int surface=Math.max(-64,Math.min(95,input));
            int floor=Math.max(-64,surface-depth+1),ceiling=Math.max(floor,Math.min(95,surface-1));
            int extra=Math.max(floor,ceiling-16),canyon=Math.max(floor,ceiling-96),lava=Math.min(ceiling,floor+8);
            assertEquals(new TellusCarverDecisionKernel.Bounds(floor,ceiling,extra,canyon,lava),
               TellusCarverDecisionKernel.bounds(-64,95,depth,input));
            var ordered=nativeCarvers.orderedCarvers(input);assertEquals(3,ordered.size());
            assertSame(ordered,nativeCarvers.orderedCarvers(surface));
            for(int i=0;i<3;i++) {
               Object config=null;
               for(Field f:ordered.get(i).getClass().getDeclaredFields())if(CarverConfiguration.class.isAssignableFrom(f.getType())) {
                  f.setAccessible(true);config=f.get(ordered.get(i));break;
               }
               assertNotNull(config);
               var encoded=config instanceof CaveCarverConfiguration cave
                  ? CaveCarverConfiguration.CODEC.encodeStart(JsonOps.INSTANCE,cave).result().orElseThrow()
                  : CanyonCarverConfiguration.CODEC.encodeStart(JsonOps.INSTANCE,(CanyonCarverConfiguration)config).result().orElseThrow();
               var json=encoded.getAsJsonObject();var y=json.getAsJsonObject("y");
               assertEquals(i==2?canyon:floor,y.getAsJsonObject("min_inclusive").get("absolute").getAsInt());
               assertEquals(i==1?extra:ceiling,y.getAsJsonObject("max_inclusive").get("absolute").getAsInt());
               assertEquals(lava,json.getAsJsonObject("lava_level").get("absolute").getAsInt());
               assertEquals(i==0?.15f:i==1?.07f:.01f,json.get("probability").getAsFloat());
            }
         }
      }
   }

   @Test void actualNativeMaskRetainsBaseBitsAndAllGuardCombinations() throws Exception {
      Method method=TellusVanillaCarverRunner.class.getDeclaredMethod("getCarvingMask",ChunkAccess.class,
         boolean.class,int[].class,int[].class,int[].class,List.class);method.setAccessible(true);
      int[] surfaces=new int[256],flood=new int[256],floor=new int[256];
      for(int i=0;i<256;i++){surfaces[i]=70+i%4;flood[i]=40+i%7;floor[i]=-62+i%3;}
      var exclusions=List.of(new UndergroundStructureExclusion.Box(-44,2,84,-40,18,90,1,true));
      for(int flags=0;flags<32;flags++) {
         var chunk=TellusEarthUndergroundProductionTest.chunk();var base=MinecraftVersionCompat.getOrCreateCarvingMask(chunk);
         for(int y=-64;y<96;y+=7)base.set(3,y,5);
         boolean reach=(flags&1)!=0;int[] s=(flags&2)!=0?surfaces:null,f=(flags&4)!=0?flood:null,g=(flags&8)!=0?floor:null;
         var boxes=(flags&16)!=0?exclusions:List.<UndergroundStructureExclusion.Box>of();
         var mask=(CarvingMask)method.invoke(null,chunk,reach,s,f,g,boxes);
         for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int y=-64;y<96;y++) {
            int i=z*16+x;
            boolean guard=!reach&&s!=null&&y>=s[i]-4 || f!=null&&y>=f[i] || g!=null&&y<=g[i];
            boolean structure=UndergroundStructureExclusion.blocksCarving(boxes,chunk.getPos().getMinBlockX()+x,y,chunk.getPos().getMinBlockZ()+z);
            assertEquals(guard||structure||base.get(x,y,z),mask.get(x,y,z));
         }
      }
   }
}
