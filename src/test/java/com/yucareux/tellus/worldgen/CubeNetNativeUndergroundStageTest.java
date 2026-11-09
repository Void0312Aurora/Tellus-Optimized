package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.caves.*;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import it.unimi.dsi.fastutil.shorts.*;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual native sampler/ProtoChunk replay over controlled surface and tag inputs; no live GIS/save claim. */
class CubeNetNativeUndergroundStageTest {
    @TempDir static Path root;
    static final int MIN=-64, MAX=96;
    static final SphereWorldModel MODEL=SphereWorldModel.forWorldScale(1000);
    static final Map<Holder.Reference<Block>,List<TagKey<Block>>> OLD_TAGS=new HashMap<>();
    @BeforeAll static void init() throws Exception {
        SphereFaceNativeBiomeKernelBridgeTest.directory=root; SphereFaceNativeBiomeKernelBridgeTest.bootstrap();
        Method bind=Holder.Reference.class.getDeclaredMethod("bindTags",Collection.class); bind.setAccessible(true);
        for(Block block:List.of(Blocks.STONE,Blocks.DEEPSLATE,Blocks.DIRT)) {
            Holder.Reference<Block> holder=BuiltInRegistries.BLOCK.getHolderOrThrow(BuiltInRegistries.BLOCK.getResourceKey(block).orElseThrow()); var tags=holder.tags().toList(); OLD_TAGS.put(holder,tags);
            var fixture=new HashSet<>(tags); fixture.add(BlockTags.OVERWORLD_CARVER_REPLACEABLES);
            if(block!=Blocks.DIRT) fixture.add(BlockTags.BASE_STONE_OVERWORLD); bind.invoke(holder,fixture);
        }
    }
    @AfterAll static void cleanup() throws Exception {
        Method bind=Holder.Reference.class.getDeclaredMethod("bindTags",Collection.class); bind.setAccessible(true);
        for(var e:OLD_TAGS.entrySet()) bind.invoke(e.getKey(),e.getValue()); SphereFaceNativeBiomeKernelBridgeTest.restore();
    }
    static int surface(int x,int z) { return 70+Math.floorMod(x,4)*3-Math.floorMod(z,3)*2; }
    static UndergroundStagePlan.Request request(int face,int profile,SamplingResolution resolution) {
        var layout=CubeNetLayout.standard(MODEL.config().faceBlocks());
        var point=layout.toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.of(face),MODEL.config().faceBlocks()/2.0+8,MODEL.config().faceBlocks()/2.0+8));
        int cx=(int)Math.floor(point.x()/16),cz=(int)Math.floor(point.z()/16);
        var address=new CubeNetAddressAdapter(MODEL).chunkAddress(cx,cz).orElseThrow();
        int[] surfaces=new int[256],flood=new int[256],floor=new int[256],halo=new int[400];
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){surfaces[z*16+x]=surface(x,z);flood[z*16+x]=40+(x%2);floor[z*16+x]=MIN+z%3;}
        for(int z=-2;z<18;z++)for(int x=-2;x<18;x++)halo[(z+2)*20+x+2]=surface(x,z);
        var flags=switch(profile){case 0->new UndergroundStagePlan.Flags(true,false,false,false);case 1->new UndergroundStagePlan.Flags(true,true,true,true);default->new UndergroundStagePlan.Flags(false,false,true,true);};
        return new UndergroundStagePlan.Request(new TellusGenerationContext(42+profile,1000,64,MIN,MAX),MODEL,address,cx,cz,surfaces,flood,floor,
            List.of(new UndergroundStagePlan.Exclusion(cx*16+3,-40,cz*16+3,cx*16+7,30,cz*16+7,2,true)),halo,flags,"controlled-surface-r58",resolution);
    }
    static ProtoChunk chunk(UndergroundStagePlan.Request r) {
        ProtoChunk chunk=new ProtoChunk(new ChunkPos(r.chunkX(),r.chunkZ()),UpgradeData.EMPTY,LevelHeightAccessor.create(MIN,MAX-MIN),SphereFaceNativeBiomeKernelBridgeTest.registry,null);
        int[] heights=r.surfaceY();
        for(int y=MIN;y<MAX;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            BlockState state=y==MIN?Blocks.BEDROCK.defaultBlockState():y>heights[z*16+x]?Blocks.AIR.defaultBlockState():y<0?Blocks.DEEPSLATE.defaultBlockState():Blocks.STONE.defaultBlockState();
            if(x==2&&z==2&&y>0&&y<20)state=Blocks.GLASS.defaultBlockState();
            if(x==4&&z==1&&y>0&&y<20)state=Blocks.DIRT.defaultBlockState();
            chunk.setBlockState(new BlockPos(r.chunkX()*16+x,y,r.chunkZ()*16+z),state,false);
        }
        chunk.getPostProcessing()[0]=new ShortArrayList(new short[]{7,7,99}); chunk.setUnsaved(false); return chunk;
    }
    record Before(List<BlockState> blocks,ShortList[] refs,short[][] values,boolean dirty) { }
    static Before before(ProtoChunk c) {
        List<BlockState> blocks=new ArrayList<>();for(int y=MIN;y<MAX;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)blocks.add(c.getBlockState(new BlockPos(c.getPos().getMinBlockX()+x,y,c.getPos().getMinBlockZ()+z)));
        var refs=c.getPostProcessing().clone();short[][] vals=new short[refs.length][];for(int i=0;i<refs.length;i++)if(refs[i]!=null)vals[i]=refs[i].toShortArray();return new Before(blocks,refs,vals,c.isUnsaved());
    }
    static void restored(ProtoChunk c,Before old) {
        assertEquals(old.blocks(),before(c).blocks()); assertEquals(old.dirty(),c.isUnsaved());
        for(int i=0;i<old.refs().length;i++){assertSame(old.refs()[i],c.getPostProcessing()[i]);if(old.refs()[i]!=null)assertArrayEquals(old.values()[i],c.getPostProcessing()[i].toShortArray());}
    }
    static CubeNetNativeUndergroundStage.Prepared prepare(CubeNetNativeUndergroundStage adapter,ProtoChunk c,UndergroundStagePlan.Request r) {
        var result=adapter.prepare(true,c,r,SphereFaceNativeBiomeKernelBridgeTest.registry);assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.READY,result.status(),result.diagnostics().toString());return result.prepared().orElseThrow();
    }
    static void nativeApply(ProtoChunk c,UndergroundStagePlan.Request r,AtomicInteger haloReads) {
        int[] halo=r.surfaceHaloY();var flags=r.flags();
        var exclusions=r.structureExclusions().stream().map(b->new UndergroundStructureExclusion.Box(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),b.carvingMargin(),b.blocksFeaturePlacement())).toList();
        new CubeNetSphericalCaveSampler().apply(r.model(),r.address(),new CubeNetSphericalCaveField(r.generation().worldSeed()),c,MIN,64,flags.applyCaves(),flags.cavesReachSurface(),flags.applyOreVeins(),flags.applyGeologicalStonePatches(),r.surfaceY(),
            flags.applyGeologicalStonePatches()?(x,z)->{haloReads.incrementAndGet();return halo[(z-r.chunkZ()*16+2)*20+x-r.chunkX()*16+2];}:null,r.floodGuardY(),r.generationFloorY(),exclusions,
            (written,pos,state,fluid)->{MinecraftVersionCompat.setBlockState(written,pos,state);if(fluid)MinecraftVersionCompat.markPosForPostProcessing((ProtoChunk)written,pos);});
    }
    @Test void eighteenCompleteChunksMatchUnchangedNativeSamplerAcrossSixFacesAndThreeProfiles() {
        var adapter=new CubeNetNativeUndergroundStage();int deltas=0,fluid=0,haloReads=0;var observed=EnumSet.noneOf(UndergroundStagePlan.UndergroundMaterial.class);
        for(int face=0;face<6;face++)for(int profile=0;profile<3;profile++) {
            var r=request(face,profile,SamplingResolution.exact(1));var actual=chunk(r);var expected=chunk(r);var old=before(actual);
            var prepared=prepare(adapter,actual,r);var repeated=prepare(adapter,actual,r);assertEquals(prepared.plan(),repeated.plan());restored(actual,old);
            for(var delta:prepared.plan().deltas()){observed.add(delta.material());if(delta.markPostProcessing())fluid++;}
            AtomicInteger reads=new AtomicInteger();nativeApply(expected,r,reads);int capturedReads=reads.get();
            var result=adapter.replay(true,actual,prepared);assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.WRITTEN,result.status(),result.diagnostics().toString());
            assertEquals(prepared.plan().deltas().size(),result.appliedDeltas());assertEquals(capturedReads,reads.get());
            assertEquals(before(expected).blocks(),before(actual).blocks());
            var a=actual.getPostProcessing();var e=expected.getPostProcessing();for(int i=0;i<a.length;i++){if(e[i]==null)assertNull(a[i]);else assertArrayEquals(e[i].toShortArray(),a[i].toShortArray());}
            assertEquals(!prepared.plan().deltas().isEmpty(),actual.isUnsaved());deltas+=result.appliedDeltas();haloReads+=capturedReads;
        }
        assertTrue(deltas>0);assertTrue(fluid>0);assertTrue(haloReads>0);
        System.out.println("NATIVE_UNDERGROUND_STAGE chunks=18 comparedBlockStates=737280 mismatchBlocks=0 exactMarkerArrays=true deltas="+deltas+" fluidMarkers="+fluid+" nativeHaloReads="+haloReads+" materials="+observed+" sourceColdReplay=true");
    }
    @Test void fullTargetBaselineAndActualObjectBindingRejectStaleAndForeignReplay() {
        var adapter=new CubeNetNativeUndergroundStage();var r=request(0,1,SamplingResolution.exact(1));var c=chunk(r);var prepared=prepare(adapter,c,r);var other=chunk(r);var oldOther=before(other);
        assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.INVALID,adapter.replay(true,other,prepared).status());restored(other,oldOther);
        c.setBlockState(new BlockPos(c.getPos().getMinBlockX(),MAX-1,c.getPos().getMinBlockZ()),Blocks.GLASS.defaultBlockState(),false);c.setUnsaved(false);var stale=before(c);
        assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.INVALID,adapter.replay(true,c,prepared).status());restored(c,stale);
    }
    @Test void disabledNonblockingAndContendedPreparationRemainTargetAndRegistryCold() {
        var adapter=new CubeNetNativeUndergroundStage();assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID,adapter.prepare(false,null,null,null).status());
        var r=request(0,1,SamplingResolution.nonBlocking(1));assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.QUEUED,adapter.prepare(true,null,r,null).status());
        var exact=request(0,1,SamplingResolution.exact(1));var c=chunk(exact);var old=before(c);try(var lease=TellusTargetWriteGuard.tryAcquire(c).orElseThrow()) {
            assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.QUEUED,adapter.prepare(true,c,exact,SphereFaceNativeBiomeKernelBridgeTest.registry).status());
        }restored(c,old);
    }
    @Test void markerMutationRuntimeAndErrorRecoverNativeBlocksDuplicateListsAndDirty() {
        var adapter=new CubeNetNativeUndergroundStage();
        for(boolean fatal:new boolean[]{false,true}) {
            ProtoChunk selected=null; CubeNetNativeUndergroundStage.Prepared captured=null;
            for(int face=0;face<6 && selected==null;face++)for(int profile=0;profile<2 && selected==null;profile++) {
                var request=request(face,profile,SamplingResolution.exact(1));var candidate=chunk(request);var plan=prepare(adapter,candidate,request);
                if(plan.plan().deltas().stream().anyMatch(UndergroundStagePlan.Delta::markPostProcessing)){selected=candidate;captured=plan;}
            }
            assertNotNull(selected,"bounded native matrix must provide a real fluid marker");
            final var c=selected; final var prepared=captured;
            var fluid=prepared.plan().deltas().stream().filter(UndergroundStagePlan.Delta::markPostProcessing).findFirst().orElseThrow();
            RuntimeException runtime=new RuntimeException(){public String getMessage(){throw this;}};Error error=new AssertionError(){public String getMessage(){throw this;}};
            class FaultList extends ShortArrayList { boolean armed=true; FaultList(){super(new short[]{7,7,99});} @Override public boolean add(short value){boolean changed=super.add(value);if(armed){armed=false;if(fatal)throw error;throw runtime;}return changed;} }
            c.getPostProcessing()[c.getSectionIndex(fluid.y())]=new FaultList();var old=before(c);
            if(fatal)assertSame(error,assertThrows(AssertionError.class,()->adapter.replay(true,c,prepared)));
            else assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.INVALID,adapter.replay(true,c,prepared).status());
            restored(c,old);try(var lease=TellusTargetWriteGuard.tryAcquire(c).orElseThrow()){lease.requireOwned(c);}
        }
    }
    @Test void aliasSectionsAndStatePalettesFailBeforeDetachedSampling() {
        var adapter=new CubeNetNativeUndergroundStage();var r=request(0,1,SamplingResolution.exact(1));
        for(boolean palette:new boolean[]{false,true}) {
            var c=chunk(r);var first=c.getSections()[0];c.getSections()[1]=palette?new LevelChunkSection(first.getStates(),c.getSections()[1].getBiomes()):first;var old=before(c);
            var result=adapter.prepare(true,c,r,SphereFaceNativeBiomeKernelBridgeTest.registry);assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID,result.status());assertTrue(result.prepared().isEmpty());restored(c,old);
        }
    }
    @Test void arithmeticOverflowAndExcludedCapStopBeforeTargetMutation() {
        var adapter=new CubeNetNativeUndergroundStage();
        var valid=request(0,1,SamplingResolution.exact(1));var target=chunk(valid);var old=before(target);
        for(int mode=0;mode<4;mode++) {
            int[] surface=valid.surfaceY(),floor=valid.generationFloorY(),halo=valid.surfaceHaloY();
            var generation=mode==2?new TellusGenerationContext(42,1000,Integer.MIN_VALUE,MIN,MAX):valid.generation();
            if(mode==0){surface[0]=Integer.MIN_VALUE;halo[2*20+2]=Integer.MIN_VALUE;}
            if(mode==1)floor[0]=Integer.MAX_VALUE;
            if(mode==3)halo[0]=Integer.MIN_VALUE;
            assertThrows(ArithmeticException.class,()->new UndergroundStagePlan.Request(generation,valid.model(),valid.address(),valid.chunkX(),valid.chunkZ(),surface,valid.floodGuardY(),floor,valid.structureExclusions(),halo,valid.flags(),valid.sourceRevision(),valid.resolution()));
        }
        assertThrows(IllegalArgumentException.class,()->new UndergroundStagePlan.Request(valid.generation(),valid.model(),valid.address(),valid.chunkX(),valid.chunkZ(),valid.surfaceY(),valid.floodGuardY(),valid.generationFloorY(),Collections.nCopies(UndergroundStagePlan.MAX_EXCLUSIONS+1,valid.structureExclusions().get(0)),valid.surfaceHaloY(),valid.flags(),valid.sourceRevision(),valid.resolution()));
        restored(target,old);
        var layout=CubeNetLayout.standard(MODEL.config().faceBlocks());
        var point=layout.toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X,8,8));
        int cx=(int)Math.floor(point.x()/16),cz=(int)Math.floor(point.z()/16);
        var address=new CubeNetAddressAdapter(MODEL).chunkAddress(cx,cz).orElseThrow();
        assertEquals(SphereSingularityPolicy.Status.EXCLUDED,new SphereSingularityPolicy(MODEL,SphereSingularityPolicy.DEFAULT_CONFIG).status(address));
        var cap=new UndergroundStagePlan.Request(valid.generation(),MODEL,address,cx,cz,valid.surfaceY(),valid.floodGuardY(),valid.generationFloorY(),List.of(),valid.surfaceHaloY(),valid.flags(),valid.sourceRevision(),valid.resolution());
        var capTarget=chunk(cap);var capBefore=before(capTarget);
        assertEquals(CubeNetNativeUndergroundStage.PreparationStatus.INVALID,adapter.prepare(true,capTarget,cap,SphereFaceNativeBiomeKernelBridgeTest.registry).status());
        restored(capTarget,capBefore);
    }
    @Test void replayQueuesUnderNativeLeaseWithoutApplyingPreparedDeltas() {
        var adapter=new CubeNetNativeUndergroundStage();var r=request(0,1,SamplingResolution.exact(1));var c=chunk(r);var prepared=prepare(adapter,c,r);var old=before(c);
        try(var lease=TellusTargetWriteGuard.tryAcquire(c).orElseThrow()){assertEquals(CubeNetUndergroundStageAdapter.ReplayStatus.QUEUED,adapter.replay(true,c,prepared).status());}restored(c,old);
    }
}
