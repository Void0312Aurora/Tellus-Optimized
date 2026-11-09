package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.geology.*;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Owned provider -> real version pipeline -> real chunk; deterministic native terrain, not real-world storage acceptance. */
class SphereFaceNativeProviderPipelineTest {
   @TempDir static Path root;
   static final double SCALE = SphereFaceNativeBiomeKernelBridgeTest.SCALE;
   static final int MIN = SphereFaceNativeBiomeKernelBridgeTest.MIN, HEIGHT = SphereFaceNativeBiomeKernelBridgeTest.HEIGHT;
   @BeforeAll static void initialize() throws Exception {
      SphereFaceNativeBiomeKernelBridgeTest.directory = root;
      SphereFaceNativeBiomeKernelBridgeTest.bootstrap();
   }
   @AfterAll static void cleanup() { SphereFaceNativeBiomeKernelBridgeTest.restore(); }
   static SphereFaceChunkGenerator generator(int face, SphereChunkSampler.PointSampler source) {
      return new SphereFaceChunkGenerator(new FixedBiomeSource(SphereFaceNativeBiomeKernelBridgeTest.biomes.getOrThrow(Biomes.DESERT)),
         face, SCALE,64,MIN,HEIGHT,source);
   }
   static TellusSampleRequest request(SphereFaceChunkGenerator gen, TellusGenerationContext g, TellusCoordinateContext c, SamplingResolution r) {
      return TellusSampleRequest.fromContext(c,SamplingPurpose.TERRAIN_COLUMN,r,g.geometry(),g.worldSeed());
   }
   static TellusCoordinateContext coordinate(SphereFaceChunkGenerator gen,int x,int z) {
      return SphereFaceNativeBiomeKernelBridgeTest.coordinate(gen,x,z);
   }
   static TellusGenerationContext generation(SphereFaceChunkGenerator gen,long seed) {
      return SphereFaceNativeBiomeKernelBridgeTest.generation(gen,seed);
   }
   static Object pipeline(boolean enabled,SphereFaceChunkGenerator gen) throws Exception {
      String version = SharedConstants.getCurrentVersion().getName().startsWith("1.20") ? "1201" : "1211";
      String prefix = "com.yucareux.tellus.worldgen.write.TellusMinecraft"+version;
      var mapper = Class.forName(prefix+"SemanticMapper").getConstructor().newInstance();
      return Class.forName(prefix+"CanonicalPipeline").getConstructor(boolean.class,TellusSampleProvider.class,
         TellusColumnDecisionEngine.class,TellusFeaturePlanner.class,TellusColumnMaterializer.class,SemanticMapper.class)
         .newInstance(enabled,gen.semanticSampleProvider(),TellusColumnDecisionEngine.withSphericalProductionRules(gen.semanticColumnKernel()),
            TellusFeaturePlanner.empty(),new TellusColumnMaterializer(),mapper);
   }
   static WriteResult<?> write(Object pipeline,ProtoChunk target,WriteContext context,TellusGenerationContext g,
                               TellusCoordinateContext c,TellusSampleRequest request) throws Exception {
      try {
         return (WriteResult<?>)pipeline.getClass().getMethod("write",ChunkAccess.class,WriteContext.class,
            TellusGenerationContext.class,TellusCoordinateContext.class,TellusSampleRequest.class).invoke(pipeline,target,context,g,c,request);
      } catch (InvocationTargetException error) {
         if (error.getCause() instanceof RuntimeException runtime) throw runtime;
         if (error.getCause() instanceof Error serious) throw serious;
         throw error;
      }
   }
   static SphereChunkSampler.SurfaceData data(int scenario,SphereWorldModel.GeoPoint point) {
      var geology = new SphereChunkSampler.SurfaceData.GeologyData(Lithology.SILICICLASTIC_SEDIMENTARY,
         new MapUnit(Rock.SANDSTONE,Rock.SHALE,35));
      return switch (scenario) {
         case 0 -> new SphereChunkSampler.SurfaceData(1010.1+Math.sin(Math.toRadians(point.latitude()))*300,30);
         case 1 -> new SphereChunkSampler.SurfaceData(-1010.1,60,20,SphereChunkSampler.SurfaceData.WaterKind.INLAND,geology);
         default -> new SphereChunkSampler.SurfaceData(-5010.1,70,0,SphereChunkSampler.SurfaceData.WaterKind.OCEAN,geology);
      };
   }
   static void allAir(ProtoChunk chunk) { for(var section:chunk.getSections()) assertTrue(section.hasOnlyAir()); }

   @Test void ownedNativeProviderAndVersionPipelineMatchActualFillWithoutResampling() throws Exception {
      int columns=0,states=0,pointDifferences=0,sourceCalls=0;
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()) {
         for(int face=0;face<6;face++) for(long seed:new long[]{0,42}) for(int scenario=0;scenario<3;scenario++) {
            int scene=scenario; List<SphereWorldModel.GeoPoint> observed=new ArrayList<>();
            var gen=generator(face,(point,resolution)->{assertEquals(SCALE,resolution,"Native grid must not use the rounded1024 request bucket");
               observed.add(point);return data(scene,point);});
            gen.setWorldSeed(seed);var provider=gen.semanticSampleProvider();var kernel=gen.semanticColumnKernel();
            var g=generation(gen,seed);var pos=face%2==0?new ChunkPos(-17,23):new ChunkPos(19,-29);
            var nativeChunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var semanticChunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);
            var pipeline=pipeline(true,gen);assertTrue(observed.isEmpty(),"Factory composition must stay lazy");
            for(int[] local:SphereFaceNativeBiomeKernelBridgeTest.LOCALS) {
               int x=pos.getMinBlockX()+local[0],z=pos.getMinBlockZ()+local[1];var c=coordinate(gen,x,z);
               var r=request(gen,g,c,SamplingResolution.exact(SCALE));assertEquals(1024,r.resolution().metres());
               var sampled=provider.sample(r);var snapshot=sampled.sphericalSurface().orElseThrow();
               var column=snapshot.nativeColumn().orElseThrow();var owner=(SphericalOwnerView)c.owner();
               assertSame(gen.terrainGrid(owner.chunk()).column(local[0],local[1]),column,"Preserve the actual immutable cached native column");
               assertEquals(observed.get(local[1]*16+local[0]),column.point());assertEquals(gen.model().surfacePoint(owner.block()),column.point());
               if(!column.point().equals(c.geo())) pointDifferences++;
               var decision=kernel.decide(g,c,sampled);assertEquals("minecraft:desert",decision.biome().id());
               assertEquals(256,observed.size(),"One native grid fetch serves all exact columns and pipeline replays");
               var result=write(pipeline,semanticChunk,WriteContext.canonical(c,g,"owned-native-provider"),g,c,r);
               assertEquals(WriteStatus.WRITTEN,result.status());assertEquals(256,observed.size());columns++;
            }
            assertSame(nativeChunk,gen.fillFromNoise(Runnable::run,null,null,null,nativeChunk).join());
            assertEquals(256,observed.size(),"Native fill reuses the exact same cached data");sourceCalls+=observed.size();
            for(int[] local:SphereFaceNativeBiomeKernelBridgeTest.LOCALS) for(int y=MIN;y<MIN+HEIGHT;y++) {
               var p=new BlockPos(pos.getMinBlockX()+local[0],y,pos.getMinBlockZ()+local[1]);
               assertEquals(nativeChunk.getBlockState(p),semanticChunk.getBlockState(p),"face="+face+" seed="+seed+" scene="+scenario+" at="+p);states++;
            }
         }
      }
      assertEquals(144,columns);assertEquals(55296,states);assertEquals(9216,sourceCalls);assertTrue(pointDifferences>0,
         "The matrix must exercise actual truncated-map source points differing from request exact-map centers");
      System.out.println("SPHERE_NATIVE_PROVIDER_PIPELINE columns="+columns+" nativeBlockStates="+states+" sourceCalls="+sourceCalls
         +" sourcePointDifferences="+pointDifferences+" mismatchBlocks=0");
   }

   @Test void factoriesTerminalUnsupportedAndInvalidRequestsLeaveNativeSourcesCold() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()) {
         AtomicInteger calls=new AtomicInteger();var gen=generator(0,(point,resolution)->{calls.incrementAndGet();throw new AssertionError("source must stay cold");});
         var provider=gen.semanticSampleProvider();var g=generation(gen,0);var c=coordinate(gen,1,2);
         var exact=request(gen,g,c,SamplingResolution.exact(SCALE));var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(0,0));
         assertEquals(WriteStatus.NO_WRITE_INVALID,write(pipeline(false,gen),target,WriteContext.canonical(c,g,"disabled"),g,c,exact).status());
         assertEquals(SampleStatus.QUEUED,provider.sample(request(gen,g,c,SamplingResolution.nonBlocking(SCALE))).status());
         for(var resolution:List.of(SamplingResolution.preview(SCALE),SamplingResolution.lod(SCALE),SamplingResolution.exact(SCALE*2)))
            assertEquals(SampleStatus.UNKNOWN,provider.sample(request(gen,g,c,resolution)).status());
         var other=TellusCoordinateContextFactory.fromSphericalBlock(gen.model(),
            new SphereWorldModel.SphereBlockAddress(1,gen.model().config().faceBlocks()/2,gen.model().config().faceBlocks()/2));
         assertThrows(IllegalArgumentException.class,()->provider.sample(request(gen,g,other,SamplingResolution.exact(SCALE))));
         var wrongSeed=generation(gen,1);assertThrows(IllegalArgumentException.class,()->provider.sample(request(gen,wrongSeed,c,SamplingResolution.exact(SCALE))));
         var wrongScale=new TellusGenerationContext(0,SCALE*2,64,MIN,MIN+HEIGHT);
         assertThrows(IllegalArgumentException.class,()->provider.sample(request(gen,wrongScale,c,SamplingResolution.exact(SCALE*2))));
         var corner=TellusCoordinateContextFactory.fromSphericalBlock(gen.model(),new SphereWorldModel.SphereBlockAddress(0,0,0));
         assertThrows(IllegalStateException.class,()->provider.sample(request(gen,g,corner,SamplingResolution.exact(SCALE))));
         assertEquals(0,calls.get());allAir(target);
      }
   }

   @Test void nativeGridFailureEscapesWithoutWritesAndRetryReusesOnlySuccessfulSnapshot() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()) {
         AtomicInteger calls=new AtomicInteger();AtomicBoolean ready=new AtomicBoolean();var sentinel=new IllegalStateException("native source unavailable");
         var gen=generator(0,(point,resolution)->{int n=calls.getAndIncrement();if(!ready.get()&&n==17)throw sentinel;return data(1,point);});
         var g=generation(gen,0);var c=coordinate(gen,1,2);var r=request(gen,g,c,SamplingResolution.exact(SCALE));
         var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(0,0));var pipeline=pipeline(true,gen);var context=WriteContext.canonical(c,g,"retry");
         var failure=assertThrows(RuntimeException.class,()->write(pipeline,target,context,g,c,r));
         Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();assertSame(sentinel,cause);
         assertEquals(18,calls.get());allAir(target);ready.set(true);
         assertEquals(WriteStatus.WRITTEN,write(pipeline,target,context,g,c,r).status());assertEquals(274,calls.get());
         gen.semanticSampleProvider().sample(r);assertEquals(274,calls.get(),"Failed grid is not cached, successful retry is cached");
      }
   }

   @Test void legacyInjectedProviderStillUsesItsDeclaredRoundedPointAndResolution() {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()) {
         var gen=generator(0,(point,resolution)->{throw new AssertionError("owned source not used");});var g=generation(gen,0);var c=coordinate(gen,1,2);
         var seen=new AtomicReference<SphereWorldModel.GeoPoint>();var resolution=new AtomicReference<Double>();
         var legacy=TellusSphericalSampleProvider.forSphereFaceProduction(new SphereChunkSampler(gen.model(),(point,metres)->{
            seen.set(point);resolution.set(metres);return data(0,point);}));
         var sample=legacy.sample(request(gen,g,c,SamplingResolution.exact(SCALE)));
         assertEquals(c.geo(),seen.get());assertEquals(1024.0,resolution.get());assertTrue(sample.sphericalSurface().orElseThrow().nativeColumn().isEmpty());
      }
   }
}
