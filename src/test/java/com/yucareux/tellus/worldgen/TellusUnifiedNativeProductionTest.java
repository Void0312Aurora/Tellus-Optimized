package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.runtime.*;
import com.yucareux.tellus.worldgen.write.TellusTargetWriteGuard;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real public entrypoints, complete native cells/biomes/heightmaps/markers and source traces, not GIS evidence. */
class TellusUnifiedNativeProductionTest {
    @TempDir static Path directory;
    @BeforeAll static void bootstrap() throws Exception {
        SphereFaceNativeBiomeKernelBridgeTest.directory = directory;
        SphereFaceNativeBiomeKernelBridgeTest.bootstrap();
    }
    @AfterAll static void restore() { SphereFaceNativeBiomeKernelBridgeTest.restore(); }
    static <T> T mode(String mode, Callable<T> construct) throws Exception {
        String previous = System.getProperty(TellusNativeProductionPipeline.MODE_PROPERTY);
        try { System.setProperty(TellusNativeProductionPipeline.MODE_PROPERTY, mode); return construct.call(); }
        finally { if (previous == null) System.clearProperty(TellusNativeProductionPipeline.MODE_PROPERTY);
            else System.setProperty(TellusNativeProductionPipeline.MODE_PROPERTY, previous); }
    }
    static int compare(ProtoChunk old, ProtoChunk actual) {
        int states = 0;
        var p = new BlockPos.MutableBlockPos();
        for (int y = old.getMinBuildHeight(); y < old.getMaxBuildHeight(); y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            p.set(old.getPos().getMinBlockX()+x, y, old.getPos().getMinBlockZ()+z);
            assertEquals(old.getBlockState(p), actual.getBlockState(p), () -> "native stage cell " + p); states++;
        }
        for (int y = old.getMinBuildHeight()/4; y < old.getMaxBuildHeight()/4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
            assertEquals(old.getNoiseBiome(x,y,z).unwrapKey(), actual.getNoiseBiome(x,y,z).unwrapKey());
        for (var type : Heightmap.Types.values()) assertArrayEquals(old.getOrCreateHeightmapUnprimed(type).getRawData(),
            actual.getOrCreateHeightmapUnprimed(type).getRawData(), type.toString());
        var expectedMarkers = old.getPostProcessing(); var actualMarkers = actual.getPostProcessing();
        assertEquals(expectedMarkers.length, actualMarkers.length);
        for (int i = 0; i < expectedMarkers.length; i++) {
            if (expectedMarkers[i] == null) assertNull(actualMarkers[i]);
            else assertArrayEquals(expectedMarkers[i].toShortArray(), actualMarkers[i].toShortArray());
        }
        assertEquals(old.isUnsaved(), actual.isUnsaved()); return states;
    }
    @Test void cubeActualBiomesTerrainAndCarversUseUnifiedProviderKernelWriterWithUnchangedWholeChunksAndSourceTrace() throws Exception {
        int chunks = 0, states = 0;
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            for (var face : CubeNetLayout.Face.values()) for (boolean modified : new boolean[]{false,true}) for (boolean rich : new boolean[]{false,true}) {
                var model = SphereWorldModel.forWorldScale(1000.0);
                List<String> oldTrace = new ArrayList<>(), actualTrace = new ArrayList<>();
                var oldBiomes = new AtomicInteger(); var actualBiomes = new AtomicInteger();
                var old = mode("legacy", () -> CubeNetNativeBaseProviderPipelineTest.generator(
                    CubeNetNativeBaseProviderPipelineTest.source(CubeNetNativeBaseProviderPipelineTest.settings(modified),oldBiomes),
                    (point,resolution) -> {oldTrace.add(point+":"+resolution); return CubeNetNativeBaseProviderPipelineTest.data(face.index()%3,model,point);},rich,42));
                var actual = mode("unified", () -> CubeNetNativeBaseProviderPipelineTest.generator(
                    CubeNetNativeBaseProviderPipelineTest.source(CubeNetNativeBaseProviderPipelineTest.settings(modified),actualBiomes),
                    (point,resolution) -> {actualTrace.add(point+":"+resolution); return CubeNetNativeBaseProviderPipelineTest.data(face.index()%3,model,point);},rich,42));
                var coordinate = CubeNetNativeBaseProviderPipelineTest.coordinate(old,face,1,2);
                var position = com.yucareux.tellus.worldgen.write.CanonicalColumnPosition.resolve(
                    com.yucareux.tellus.worldgen.write.WriteContext.canonical(coordinate,
                        CubeNetNativeBaseProviderPipelineTest.generation(old,42),"whole-native-stage"));
                var pos = new ChunkPos(Math.floorDiv(position.x(),16),Math.floorDiv(position.z(),16));
                var a = SphereFaceNativeBiomeKernelBridgeTest.chunk(pos); var b = SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);
                old.createBiomes(Runnable::run,null,null,null,a).join(); actual.createBiomes(Runnable::run,null,null,null,b).join();
                SphereFaceNativeBiomeKernelBridgeTest.biomeStatus(a); SphereFaceNativeBiomeKernelBridgeTest.biomeStatus(b);
                old.fillFromNoise(Runnable::run,null,null,null,a).join(); actual.fillFromNoise(Runnable::run,null,null,null,b).join();
                old.applyCarvers(null,42,null,null,null,a,GenerationStep.Carving.AIR);
                actual.applyCarvers(null,42,null,null,null,b,GenerationStep.Carving.AIR);
                states += compare(a,b); chunks++;
                assertEquals(oldTrace,actualTrace,"unified base must not resample GIS or reorder native surface queries");
                assertEquals(oldBiomes.get(),actualBiomes.get(),"terrain kernel reads already-published biome volume, not an extra source query");
                var observation = actual.nativeProductionPipeline().observation(pos);
                assertEquals(List.of("BIOMES","TERRAIN","CARVERS"),observation.stages());
                assertTrue(observation.backends().get(1).contains("shared chunk writer")); assertTrue(observation.failures().isEmpty());
                assertTrue(old.nativeProductionPipeline().observation(pos).stages().isEmpty());
            }
        }
        assertEquals(24,chunks); assertEquals(2_359_296,states);
        System.out.println("UNIFIED_CUBE_NATIVE chunks="+chunks+" blockStates="+states+" sourceTraceEqual=true biomeReadsEqual=true heightmapsMarkersEqual=true");
    }
    @Test void sphereActualFourStageChainKeepsEmptyCarversAndDecorationAndExactNativeOutput() throws Exception {
        int chunks = 0, states = 0;
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            for (int face = 0; face < 6; face++) for (int scene = 0; scene < 3; scene++) {
                int f=face,s=scene; var aTrace=new ArrayList<String>(); var bTrace=new ArrayList<String>();
                var aGen=mode("legacy",()->SphereFaceNativeProviderPipelineTest.generator(f,(p,r)->{aTrace.add(p+":"+r);return SphereFaceNativeProviderPipelineTest.data(s,p);}));
                var bGen=mode("unified",()->SphereFaceNativeProviderPipelineTest.generator(f,(p,r)->{bTrace.add(p+":"+r);return SphereFaceNativeProviderPipelineTest.data(s,p);}));
                aGen.setWorldSeed(42); bGen.setWorldSeed(42);
                var pos=new ChunkPos(-17,23); var a=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);var b=SphereFaceNativeBiomeKernelBridgeTest.chunk(pos);
                aGen.createBiomes(Runnable::run,null,null,null,a).join(); bGen.createBiomes(Runnable::run,null,null,null,b).join();
                SphereFaceNativeBiomeKernelBridgeTest.biomeStatus(a); SphereFaceNativeBiomeKernelBridgeTest.biomeStatus(b);
                aGen.fillFromNoise(Runnable::run,null,null,null,a).join(); bGen.fillFromNoise(Runnable::run,null,null,null,b).join();
                aGen.applyCarvers(null,42,null,null,null,a,GenerationStep.Carving.AIR); bGen.applyCarvers(null,42,null,null,null,b,GenerationStep.Carving.AIR);
                aGen.applyBiomeDecoration(null,a,null); bGen.applyBiomeDecoration(null,b,null);
                states+=compare(a,b);chunks++; assertEquals(aTrace,bTrace);assertEquals(256,bTrace.size());
                var observation=bGen.nativeProductionPipeline().observation(pos);
                assertEquals(List.of("BIOMES","TERRAIN","CARVERS","DECORATION"),observation.stages());
                assertTrue(observation.backends().get(2).contains("empty"));assertTrue(observation.backends().get(3).contains("empty"));
                assertTrue(observation.failures().isEmpty());
            }
        }
        assertEquals(18,chunks);assertEquals(1_769_472,states);
        System.out.println("UNIFIED_SPHERE_NATIVE chunks="+chunks+" blockStates="+states+" stages=4 emptyPostStages=true sourceTraceEqual=true");
    }
    @Test void realStageOwnershipStopsCompetingWritersBeforeSourceAccessAndReleasesAfterFatalFailure() throws Exception {
        var reads=new AtomicInteger();
        var gen=mode("unified",()->SphereFaceNativeProviderPipelineTest.generator(0,(point,resolution)->{reads.incrementAndGet();throw new AssertionError("native source fault");}));
        var target=SphereFaceNativeBiomeKernelBridgeTest.chunk(new ChunkPos(0,0));
        try(var lease=TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) {
            assertThrows(RuntimeException.class,()->gen.fillFromNoise(Runnable::run,null,null,null,target).join());assertEquals(0,reads.get());
        }
        assertThrows(RuntimeException.class,()->gen.fillFromNoise(Runnable::run,null,null,null,target).join());
        assertEquals(1,reads.get());SphereFaceNativeProviderPipelineTest.allAir(target);
        try(var released=TellusTargetWriteGuard.tryAcquire(target).orElseThrow()){released.requireOwned(target);}
        assertFalse(gen.nativeProductionPipeline().observation(target.getPos()).failures().isEmpty());
    }
}
