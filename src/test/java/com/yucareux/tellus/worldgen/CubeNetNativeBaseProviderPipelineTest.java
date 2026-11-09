package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.*;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.geology.*;
import com.yucareux.tellus.world.data.osm.*;
import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Explicit native Cube base stage; controlled inputs do not establish GIS, post-flow or persistent-world acceptance. */
class CubeNetNativeBaseProviderPipelineTest {
   @TempDir static Path directory;
   static final double SCALE=1000.0;
   static final int MIN=-64,HEIGHT=384;
   @BeforeAll static void initialize() throws Exception {
      SphereFaceNativeBiomeKernelBridgeTest.directory=directory;SphereFaceNativeBiomeKernelBridgeTest.bootstrap();
   }
   @AfterAll static void restore(){SphereFaceNativeBiomeKernelBridgeTest.restore();}
   static Object field(Object object,String name) throws Exception {
      var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
   }
   static void set(Object object,String name,Object value) throws Exception {
      var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
   }
   static Object invoke(Object object,String name,Class<?>[] types,Object...args) throws Exception {
      var method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
      try{return method.invoke(object,args);}catch(InvocationTargetException error){
         if(error.getCause() instanceof RuntimeException runtime)throw runtime;
         if(error.getCause() instanceof Error serious)throw serious;throw error;
      }
   }
   static CubeNetBiomeSource.Settings settings(boolean modified){
      var json=CubeNetBiomeSource.Settings.CODEC.encodeStart(JsonOps.INSTANCE,CubeNetBiomeSource.Settings.DEFAULT).result().orElseThrow().getAsJsonObject();
      json.addProperty("random_biomes",false);json.addProperty("cave_generation",false);json.addProperty("deep_dark",false);json.addProperty("buildings",false);
      if(modified){json.addProperty("enable_water",false);json.addProperty("terrestrial_height_scale",1.7);json.addProperty("oceanic_height_scale",2.5);
         json.addProperty("height_offset",EarthGeneratorSettings.DEFAULT.heightOffset()+12);}
      return CubeNetBiomeSource.Settings.CODEC.parse(JsonOps.INSTANCE,json).result().orElseThrow();
   }
   static CubeNetBiomeSource source(CubeNetBiomeSource.Settings settings,AtomicInteger reads){
      return new CubeNetBiomeSource(SphereFaceNativeBiomeKernelBridgeTest.biomes,SCALE,5,SphereSingularityPolicy.DEFAULT_CONFIG,
         new FixedBiomeSource(SphereFaceNativeBiomeKernelBridgeTest.biomes.getOrThrow(Biomes.PLAINS)),
         (point,resolution)->{reads.incrementAndGet();return new SphereChunkSampler.SurfaceData(100,10);},
         (point,resolution,water)->new CubeNetBiomeSource.ClimateData("Cfa",null),
         (point,scale)->CubeNetBiomeSource.WaterData.dry(),settings);
   }
   static CubeNetChunkGenerator generator(BiomeSource source,SphereChunkSampler.PointSampler sampler,boolean rich,long seed) throws Exception {
      var fixed=source instanceof CubeNetBiomeSource cube?cube.fallback():(FixedBiomeSource)source;
      var ctor=CubeNetChunkGenerator.class.getDeclaredConstructor(BiomeSource.class,FixedBiomeSource.class,double.class,int.class,int.class,int.class,
         SphereSingularityPolicy.Config.class,SphereChunkSampler.PointSampler.class,int.class,int.class,int.class,boolean.class);ctor.setAccessible(true);
      var gen=ctor.newInstance(source,fixed,SCALE,64,MIN,HEIGHT,SphereSingularityPolicy.DEFAULT_CONFIG,sampler,5,1,1,true);
      set(gen,"worldSeed",seed);((SphereTerrainMaterializer)field(gen,"materializer")).setWorldSeed(seed);
      for(var face:(SphereFaceChunkGenerator[])field(gen,"faces"))face.setWorldSeed(seed);
      if(rich){
         var terrain=new SphereChunkSampler(gen.model(),sampler);
         CubeNetInlandWaterResolver.GeodeticWaterInput empty=new CubeNetInlandWaterResolver.GeodeticWaterInput(){
            @Override public TellusOsmWaterSource.WaterQueryResult waterForGeoAreaWithStatus(GeodeticBounds bounds,double scale,OsmQueryMode mode){return result();}
            @Override public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature feature,double scale){return result();}
            @Override public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature feature,double scale){return result();}
            TellusOsmWaterSource.WaterQueryResult result(){return new TellusOsmWaterSource.WaterQueryResult(List.of(),TellusOsmWaterSource.CoverageStatus.COMPLETE,1);}
         };
         set(gen,"inlandWaterResolver",new CubeNetInlandWaterResolver(gen.model(),gen.getSeaLevel(),terrain,empty));
      }
      return gen;
   }
   static TellusGenerationContext generation(CubeNetChunkGenerator gen,long seed){
      return new TellusGenerationContext(seed,gen.worldScale(),gen.getSeaLevel(),gen.getMinY(),gen.maxY(),
         GeometryIdentity.forProvider("tellus:cube-net","native-base-r52",gen.model().mappingId(),"spherical-noise-v1",gen.worldScale()));
   }
   static TellusCoordinateContext coordinate(CubeNetChunkGenerator gen,CubeNetLayout.Face face,int localX,int localZ){
      int offset=gen.model().config().faceBlocks()/2;
      return TellusCoordinateContextFactory.fromCubeNetCanonical(gen.model(),gen.adapter().layout(),
         new CubeNetLayout.FacePoint(face,offset+16*17+localX+0.5,offset-16*23+localZ+0.5));
   }
   static TellusSampleRequest request(TellusGenerationContext g,TellusCoordinateContext c,SamplingResolution r){
      return TellusSampleRequest.fromContext(c,SamplingPurpose.TERRAIN_COLUMN,r,g.geometry(),g.worldSeed());
   }
   static Object pipeline(boolean enabled,CubeNetChunkGenerator gen) throws Exception{
      String v=SharedConstants.getCurrentVersion().getName().startsWith("1.20")?"1201":"1211";
      String prefix="com.yucareux.tellus.worldgen.write.TellusMinecraft"+v;
      return Class.forName(prefix+"CanonicalPipeline").getConstructor(boolean.class,TellusSampleProvider.class,TellusColumnDecisionEngine.class,
         TellusFeaturePlanner.class,TellusColumnMaterializer.class,SemanticMapper.class).newInstance(enabled,gen.semanticBaseSampleProvider(),
         TellusColumnDecisionEngine.withSphericalProductionRules(gen.semanticBaseColumnKernel()),TellusFeaturePlanner.empty(),new TellusColumnMaterializer(),
         Class.forName(prefix+"SemanticMapper").getConstructor().newInstance());
   }
   static SphereChunkSampler.SurfaceData data(int scene,SphereWorldModel model,SphereWorldModel.GeoPoint point){
      var geology=new SphereChunkSampler.SurfaceData.GeologyData(Lithology.SILICICLASTIC_SEDIMENTARY,new MapUnit(Rock.SANDSTONE,Rock.SHALE,35));
      if(scene==0)return new SphereChunkSampler.SurfaceData(1010.1+Math.sin(Math.toRadians(point.latitude()))*300,30,Double.NaN,
         SphereChunkSampler.SurfaceData.WaterKind.NONE,geology);
      if(scene==1){var address=model.blockForGeo(point);if(Math.floorMod(address.blockX(),4)==0)
         return new SphereChunkSampler.SurfaceData(-2010.1,80,0,SphereChunkSampler.SurfaceData.WaterKind.OCEAN,geology);
         return new SphereChunkSampler.SurfaceData(9010.1,30,Double.NaN,SphereChunkSampler.SurfaceData.WaterKind.NONE,geology);}
      return new SphereChunkSampler.SurfaceData(-5010.1,80,20,SphereChunkSampler.SurfaceData.WaterKind.INLAND,geology);
   }
   @Test void nativeBaseProviderPipelineMatchesActualFillAcrossFacesProfilesAndRichSurfacePaths() throws Exception {
      int columns=0,states=0,adjusted=0,richDifferences=0,negativePositions=0;Set<String> ids=new HashSet<>();
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         for(var face:CubeNetLayout.Face.values())for(boolean modified:new boolean[]{false,true})for(boolean rich:new boolean[]{false,true})for(int scene=0;scene<3;scene++){
            int scenario=scene;AtomicInteger terrainReads=new AtomicInteger(),biomeReads=new AtomicInteger();var model=SphereWorldModel.forWorldScale(SCALE);
            var source=source(settings(modified),biomeReads);
            var gen=generator(source,(point,resolution)->{assertEquals(SCALE,resolution);terrainReads.incrementAndGet();return data(scenario,model,point);},rich,42);
            var g=generation(gen,42);assertEquals(modified?76:64,g.seaLevel());
            var c0=coordinate(gen,face,1,2);var p0=CanonicalColumnPosition.resolve(WriteContext.canonical(c0,g,"locate"));
            var pos=new ChunkPos(Math.floorDiv(p0.x(),16),Math.floorDiv(p0.z(),16));
            var nativeChunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var semantic=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);
            var provider=gen.semanticBaseSampleProvider();var kernel=gen.semanticBaseColumnKernel();var pipeline=pipeline(true,gen);
            assertEquals(0,terrainReads.get());assertEquals(0,biomeReads.get());
            for(int[] local:SphereFaceNativeBiomeKernelBridgeTest.LOCALS){
               var c=coordinate(gen,face,local[0],local[1]);var r=request(g,c,SamplingResolution.exact(SCALE));var sample=provider.sample(r);
               assertTrue(sample.provenance().note().contains("POST_GROUND_ADJUSTMENT_PRE_INLAND_FLOW_BADLANDS"));
               var snap=sample.sphericalSurface().orElseThrow();var column=snap.nativeColumn().orElseThrow();var owner=(CubeNetCanonicalOwnerView)c.owner();
               assertEquals(owner.block(),column.address());assertEquals(gen.model().surfacePoint(owner.block()),column.point());
               var raw=data(scenario,model,column.point());if(!column.data().equals(raw))adjusted++;
               var simple=CubeNetSurfacePolicy.resolve(column.point(),column.data(),SCALE,42);if(rich&&!simple.equals(snap.surfaceDecision().orElseThrow()))richDifferences++;
               var result=kernel.decide(g,c,sample);ids.add(result.biome().id());
               var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"write"));
               if(p.x()<0||p.z()<0)negativePositions++;
               String expected=CubeNetNativeBiomeKernelBridgeTest.id(source.getNoiseBiome(QuartPos.fromBlock(p.x()),
                  QuartPos.fromBlock(result.terrainY().orElseThrow()),QuartPos.fromBlock(p.z()),Climate.empty()));
               assertEquals(expected,result.biome().id());assertTrue(biomeReads.get()>0,"Biome source must still read its own terrain, not provider inputs");
               var write=SphereFaceNativeProviderPipelineTest.write(pipeline,semantic,WriteContext.canonical(c,g,"native-cube-base"),g,c,r);
               assertEquals(WriteStatus.WRITTEN,write.status());columns++;
            }
            assertSame(nativeChunk,gen.fillFromNoise(Runnable::run,null,null,null,nativeChunk).join());
            for(int[] local:SphereFaceNativeBiomeKernelBridgeTest.LOCALS){
               var c=coordinate(gen,face,local[0],local[1]);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"compare"));
               assertEquals(pos.x,Math.floorDiv(p.x(),16));assertEquals(pos.z,Math.floorDiv(p.z(),16));
               for(int y=MIN;y<MIN+HEIGHT;y++){
                  var block=new BlockPos(p.x(),y,p.z());assertEquals(nativeChunk.getBlockState(block),semantic.getBlockState(block),
                     "face="+face+" modified="+modified+" rich="+rich+" scene="+scene+" block="+block);states++;
               }
            }
         }
      }
      assertEquals(288,columns);assertEquals(110592,states);assertTrue(adjusted>0,"Exercise actual bank adjustment");assertTrue(richDifferences>0,"Exercise rich policy beyond simple surface rules");
      assertTrue(negativePositions>0);assertFalse(ids.isEmpty());
      System.out.println("CUBE_NATIVE_BASE_PIPELINE columns="+columns+" nativeBlockStates="+states+" bankAdjustedColumns="+adjusted
         +" richSurfaceDifferences="+richDifferences+" mismatchBlocks=0");
   }
   @Test void fixedSourceTerminalAndInvalidProfilesDoNotInventOrResampleTerrain() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         AtomicInteger reads=new AtomicInteger();var gen=generator(new FixedBiomeSource(SphereFaceNativeBiomeKernelBridgeTest.biomes.getOrThrow(Biomes.DESERT)),
            (point,resolution)->{reads.incrementAndGet();return new SphereChunkSampler.SurfaceData(10.1,30);},false,0);
         var g=generation(gen,0);var c=coordinate(gen,CubeNetLayout.Face.NEG_X,1,2);var r=request(g,c,SamplingResolution.exact(SCALE));
         var provider=gen.semanticBaseSampleProvider();assertEquals(SampleStatus.QUEUED,provider.sample(request(g,c,SamplingResolution.nonBlocking(SCALE))).status());
         for(var resolution:List.of(SamplingResolution.preview(SCALE),SamplingResolution.lod(SCALE),SamplingResolution.exact(SCALE*2)))
            assertEquals(SampleStatus.UNKNOWN,provider.sample(request(g,c,resolution)).status());
         var wrongSeed=new TellusGenerationContext(99,SCALE,g.seaLevel(),MIN,MIN+HEIGHT,g.geometry());
         assertThrows(IllegalArgumentException.class,()->provider.sample(request(wrongSeed,c,SamplingResolution.exact(SCALE))));
         assertEquals(0,reads.get());var sample=provider.sample(r);assertEquals(256,reads.get());
         assertEquals("minecraft:desert",gen.semanticBaseColumnKernel().decide(g,c,sample).biome().id());assertEquals(256,reads.get());
         var badBounds=new TellusGenerationContext(0,SCALE,g.seaLevel()+1,MIN,MIN+HEIGHT,g.geometry());
         assertThrows(IllegalArgumentException.class,()->gen.semanticBaseColumnKernel().decide(badBounds,c,sample));
         assertEquals(256,reads.get());
      }
   }
   @Test void canonicalGridLineCoordinatesUseOwnerCellCenterRatherThanReflectedPlanarNeighbour() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         var source=source(settings(false),new AtomicInteger());var gen=generator(source,(point,resolution)->new SphereChunkSampler.SurfaceData(1010.1,30),false,0);
         var g=generation(gen,0);int checked=0,reflected=0;
         for(var face:CubeNetLayout.Face.values()){
            int n=gen.model().config().faceBlocks()/2;
            TellusCoordinateContext c=null;
            for(int i=0;i<64;i++){
               try{c=TellusCoordinateContextFactory.fromCubeNetCanonical(gen.model(),gen.adapter().layout(),
                  new CubeNetLayout.FacePoint(face,n+37+i,n-59+i));break;}
               catch(IllegalArgumentException inverseBoundary){assertEquals("canonical owner does not match GeoPoint",inverseBoundary.getMessage());}
            }
            assertNotNull(c,"Find an existing factory-valid exact gridline within a bounded candidate list");
            var owner=(CubeNetCanonicalOwnerView)c.owner();
            assertEquals(owner.block(),gen.model().blockForGeo(c.geo()));
            var sample=gen.semanticBaseSampleProvider().sample(request(g,c,SamplingResolution.exact(SCALE)));
            var result=gen.semanticBaseColumnKernel().decide(g,c,sample);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(c,g,"gridline"));
            assertEquals(CubeNetNativeBiomeKernelBridgeTest.id(source.getNoiseBiome(QuartPos.fromBlock(p.x()),QuartPos.fromBlock(result.terrainY().orElseThrow()),
               QuartPos.fromBlock(p.z()),Climate.empty())),result.biome().id());
            if(p.x()!=(int)Math.floor(owner.planarPoint().x())||p.z()!=(int)Math.floor(owner.planarPoint().z()))reflected++;
            checked++;
         }
         assertTrue(checked>0);assertTrue(reflected>0,"Exercise reflected gridline canonical-center normalization");
      }
   }
   @Test void genericNativeSnapshotRouteValidatesContextsAndReturnsTerminalBeforeCallback() {
      var model=SphereWorldModel.forWorldScale(SCALE);var layout=CubeNetLayout.standard(model.config().faceBlocks());int offset=model.config().faceBlocks()/2;
      var c=TellusCoordinateContextFactory.fromCubeNetCanonical(model,layout,new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X,offset+1.5,offset+2.5));
      var g=new TellusGenerationContext(0,SCALE,64,MIN,MIN+HEIGHT);var owner=(CubeNetCanonicalOwnerView)c.owner();
      AtomicInteger calls=new AtomicInteger();var column=new SphereChunkSampler.Column(owner.block(),model.surfacePoint(owner.block()),new SphereChunkSampler.SurfaceData(10,30));
      var provider=TellusSphericalSampleProvider.forCubeNetNativeSnapshots(r->{calls.incrementAndGet();return TellusSphericalSurfaceSnapshot.resolvedNativeColumn(column,CubeNetSurfacePolicy.Decision.DEFAULT);});
      assertEquals(SampleStatus.QUEUED,provider.sample(request(g,c,SamplingResolution.nonBlocking(SCALE))).status());
      assertEquals(SampleStatus.UNKNOWN,provider.sample(request(g,c,SamplingResolution.preview(SCALE))).status());assertEquals(0,calls.get());
      assertTrue(provider.sample(request(g,c,SamplingResolution.exact(SCALE))).complete());assertEquals(1,calls.get());
      var missing=TellusSphericalSampleProvider.forCubeNetNativeSnapshots(r->TellusSphericalSurfaceSnapshot.resolved(column.data(),CubeNetSurfacePolicy.Decision.DEFAULT));
      assertThrows(IllegalArgumentException.class,()->missing.sample(request(g,c,SamplingResolution.exact(SCALE))));
      var raw=TellusSphericalSampleProvider.forCubeNetNativeSnapshots(r->new TellusSphericalSurfaceSnapshot(column.data(),Optional.empty(),Optional.of(column)));
      assertThrows(IllegalArgumentException.class,()->raw.sample(request(g,c,SamplingResolution.exact(SCALE))));
      assertThrows(NullPointerException.class,()->TellusSphericalSampleProvider.forCubeNetNativeSnapshots(null));
   }
   /** Frozen pre-Round52 bank loop; only reflective calls bridge private native source seams. */
   static List<SphereChunkSampler.Column> frozenBank(CubeNetChunkGenerator gen,ChunkAccess chunk,List<SphereChunkSampler.Column> columns) throws Exception{
      boolean cliff=(boolean)field(gen,"shorelineBlendCliffLimit");int inland=(int)field(gen,"inlandShorelineRadius"),ocean=(int)field(gen,"oceanShorelineRadius");
      if(!cliff||Math.max(inland,ocean)<=0)return columns;
      var materializer=(SphereTerrainMaterializer)field(gen,"materializer");var water=(CubeNetInlandWaterResolver)field(gen,"inlandWaterResolver");
      Map<Long,SphereChunkSampler.SurfaceData> cache=new HashMap<>();List<SphereChunkSampler.Column> adjusted=new ArrayList<>(columns);
      int minX=chunk.getPos().getMinBlockX(),minZ=chunk.getPos().getMinBlockZ();
      for(int localZ=0;localZ<16;localZ++)for(int localX=0;localX<16;localX++){
         int index=localZ*16+localX;var column=columns.get(index);var surface=column.data();
         if(!surface.elevationAvailable()||surface.waterAvailable())continue;
         Object resolved=((Optional<?>)invoke(gen,"resolveBlock",new Class<?>[]{int.class,int.class},minX+localX,minZ+localZ)).orElse(null);
         if(resolved!=null){
            var address=(SphereWorldModel.SphereChunkAddress)invoke(resolved,"address",new Class<?>[0]);
            int x=(int)invoke(resolved,"x",new Class<?>[0]),z=(int)invoke(resolved,"z",new Class<?>[0]);
            var point=gen.model().exactSurfacePoint(address.face(),x+0.5,z+0.5);if(water!=null&&water.isInlandBank(point))continue;
         }
         int terrainY=materializer.height(surface.elevationMeters()),allowedY=Integer.MAX_VALUE,searchRadius=Math.max(inland,ocean);
         for(int dz=-searchRadius;dz<=searchRadius;dz++)for(int dx=-searchRadius;dx<=searchRadius;dx++){
            if(dx==0&&dz==0)continue;int distance=Math.max(Math.abs(dx),Math.abs(dz));if(distance>searchRadius)continue;
            var neighbour=(SphereChunkSampler.SurfaceData)invoke(gen,"surfaceDataAtPlanar",new Class<?>[]{int.class,int.class,Map.class},minX+localX+dx,minZ+localZ+dz,cache);
            if(neighbour==null||!neighbour.waterAvailable())continue;int radius=neighbour.inlandWater()?inland:ocean;if(distance>radius)continue;
            int waterY=materializer.height(neighbour.waterSurfaceMeters());allowedY=Math.min(allowedY,waterY+distance);
         }
         if(allowedY==Integer.MAX_VALUE||terrainY-allowedY<3||terrainY<=allowedY)continue;
         int rampedY=Math.max(gen.getMinY()+1,allowedY);double elevation=(rampedY-gen.getSeaLevel())*gen.worldScale();
         adjusted.set(index,new SphereChunkSampler.Column(column.address(),column.point(),surface.withElevation(elevation)));
      }
      return List.copyOf(adjusted);
   }
   @SuppressWarnings("unchecked")
   @Test void extractedBankLoopMatchesFrozenColumnsAndRawSourceLookupOrder() throws Exception{
      int columns=0,changed=0;
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         for(var face:CubeNetLayout.Face.values())for(boolean modified:new boolean[]{false,true}){
            List<SphereWorldModel.GeoPoint> reads=new ArrayList<>();var model=SphereWorldModel.forWorldScale(SCALE);
            var gen=generator(source(settings(modified),new AtomicInteger()),(point,resolution)->{reads.add(point);return data(1,model,point);},true,42);
            var g=generation(gen,42);var center=coordinate(gen,face,1,2);var p=CanonicalColumnPosition.resolve(WriteContext.canonical(center,g,"bank"));
            var pos=new ChunkPos(Math.floorDiv(p.x(),16),Math.floorDiv(p.z(),16));var chunk=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);
            var address=gen.address(pos).orElseThrow();var faces=(SphereFaceChunkGenerator[])field(gen,"faces");var grid=faces[address.face()].terrainGrid(address);
            List<SphereChunkSampler.Column> raw=new ArrayList<>();
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){
               double[] local=gen.adapter().layout().localVector(face,x+0.5,z+0.5);
               raw.add(grid.column(Math.floorMod((int)Math.floor(local[0]),16),Math.floorMod((int)Math.floor(local[1]),16)));
            }
            reads.clear();var expected=frozenBank(gen,chunk,raw);var expectedReads=List.copyOf(reads);reads.clear();
            var actual=(List<SphereChunkSampler.Column>)invoke(gen,"applyCubeNetShorelineBankRamp",new Class<?>[]{ChunkAccess.class,List.class},chunk,raw);
            assertEquals(expected,actual);assertEquals(expectedReads,reads,"Keep original per-fill lookup order and caching");
            for(int i=0;i<256;i++){if(!raw.get(i).equals(actual.get(i)))changed++;columns++;}
            set(gen,"shorelineBlendCliffLimit",false);
            assertSame(raw,invoke(gen,"applyCubeNetShorelineBankRamp",new Class<?>[]{ChunkAccess.class,List.class},chunk,raw));
            set(gen,"shorelineBlendCliffLimit",true);set(gen,"inlandShorelineRadius",0);set(gen,"oceanShorelineRadius",0);
            assertSame(raw,invoke(gen,"applyCubeNetShorelineBankRamp",new Class<?>[]{ChunkAccess.class,List.class},chunk,raw));
         }
      }
      assertEquals(3072,columns);assertTrue(changed>0);
      System.out.println("CUBE_NATIVE_BANK_EXTRACTION columns="+columns+" changedColumns="+changed+" mismatchColumns=0 lookupOrderMismatches=0");
   }

   @SuppressWarnings("unchecked") static Codec<CubeNetChunkGenerator> codec() throws Exception{
      Object value=CubeNetChunkGenerator.class.getField("CODEC").get(null);return value instanceof MapCodec<?> map?(Codec<CubeNetChunkGenerator>)map.codec():(Codec<CubeNetChunkGenerator>)value;
   }
   @Test void wholeGeneratorCodecAndNativeClassifierRemainUnchangedByExplicitFactories() throws Exception {
      try(var ignored=ManagedTerrainNetworkPolicy.cacheOnly()){
         AtomicInteger reads=new AtomicInteger();var gen=generator(source(settings(true),new AtomicInteger()),(point,resolution)->{reads.incrementAndGet();return new SphereChunkSampler.SurfaceData(10,30);},false,0);
         var codec=codec();var ops=SphereFaceNativeBiomeKernelBridgeTest.ops();var original=codec.encodeStart(ops,gen).result().orElseThrow();
         gen.semanticBaseSampleProvider();gen.semanticBaseColumnKernel();assertEquals(original,codec.encodeStart(ops,gen).result().orElseThrow());assertEquals(0,reads.get());
         var restored=codec.parse(ops,original).result().orElseThrow();assertInstanceOf(CubeNetBiomeSource.class,restored.getBiomeSource());
         assertEquals(gen.worldScale(),restored.worldScale());assertEquals(gen.getMinY(),restored.getMinY());assertEquals(gen.getGenDepth(),restored.getGenDepth());
         assertEquals(gen.heightOffset(),restored.heightOffset());
         assertEquals(gen.getSeaLevel()+12,restored.getSeaLevel(),"Characterize the existing non-default-offset Codec drift without changing it");
         System.out.println("CUBE_NATIVE_CODEC_EXISTING_OFFSET encodedSea="+gen.getSeaLevel()+" decodedSea="+restored.getSeaLevel()+" drift=12");
         // Existing native Codec getter stores effectiveSea while its constructor applies the offset again.
         // This test records factory invariance, not a newly promised non-default-heightOffset save roundtrip.
      }
   }
}
