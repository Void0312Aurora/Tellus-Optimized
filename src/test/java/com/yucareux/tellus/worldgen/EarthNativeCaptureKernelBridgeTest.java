package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual registration and resolved native surface/capture/kernel bridge, not GIS/full-fill acceptance. */
class EarthNativeCaptureKernelBridgeTest {
   @TempDir static Path directory;
   static String oldGame, oldConfig;
   static ClassLoader oldLoader;
   static Registry<Biome> biomes;
   static net.minecraft.core.Holder<Biome> plains;
   static Constructor<?> proto;
   @BeforeAll static void bootstrap() throws Exception {
      oldGame = System.getProperty("tellus.gameDir"); oldConfig = System.getProperty("tellus.configDir");
      oldLoader = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(EarthNativeCaptureKernelBridgeTest.class.getClassLoader());
      System.setProperty("tellus.gameDir", directory.toString());
      System.setProperty("tellus.configDir", directory.resolve("config").toString());
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Class.forName(EarthChunkGenerator.class.getName(), true, EarthNativeCaptureKernelBridgeTest.class.getClassLoader());
      }
      plains = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
      biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
      Registry.register(biomes, Biomes.PLAINS, plains.value());
      proto = ProtoChunk.class.getDeclaredConstructor(ChunkPos.class, UpgradeData.class, LevelHeightAccessor.class,
         Registry.class, Class.forName("net.minecraft.world.level.levelgen.blending.BlendingData"));
      proto.setAccessible(true);
   }
   @AfterAll static void restore() {
      if (oldGame == null) System.clearProperty("tellus.gameDir"); else System.setProperty("tellus.gameDir", oldGame);
      if (oldConfig == null) System.clearProperty("tellus.configDir"); else System.setProperty("tellus.configDir", oldConfig);
      Thread.currentThread().setContextClassLoader(oldLoader);
   }
   record Fixture(EarthChunkGenerator generator, TellusGenerationContext generation,
                  TellusCoordinateContext coordinate, ProtoChunk chunk) {
      int x() { return Math.toIntExact(((EarthOwnerView)coordinate.owner()).point().floorBlockX()); }
      int z() { return Math.toIntExact(((EarthOwnerView)coordinate.owner()).point().floorBlockZ()); }
   }
   static Fixture fixture() throws Exception {
      EarthChunkGenerator generator;
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         generator = new EarthChunkGenerator(new FixedBiomeSource(plains), EarthGeneratorSettings.DEFAULT);
      }
      double scale = generator.settings().worldScale();
      var geo = new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0);
      var earth = EarthCompatibilityPoint.fromGeoPoint(geo, scale);
      var coordinate = TellusCoordinateContextFactory.fromGeo(SphereWorldModel.forWorldScale(scale), geo, new EarthOwnerView(earth));
      var geometry = GeometryIdentity.forProvider("tellus:earth", "native-capture-bridge-r44",
         "earth-projection-v1", "earth-noise-v1", scale);
      var generation = new TellusGenerationContext(generator.worldSeed(), scale, generator.getSeaLevel(),
         generator.getMinY(), Math.addExact(generator.getMinY(), generator.getGenDepth()), geometry);
      return new Fixture(generator, generation, coordinate, chunk(generation, new ChunkPos(Math.toIntExact(earth.floorBlockX()) >> 4, Math.toIntExact(earth.floorBlockZ()) >> 4)));
   }
   static ProtoChunk chunk(TellusGenerationContext generation, ChunkPos position) throws Exception {
      return (ProtoChunk) proto.newInstance(position, UpgradeData.EMPTY,
         LevelHeightAccessor.create(generation.minY(), generation.maxYExclusive() - generation.minY()), biomes, null);
   }
   @Test void publicRegistrationIsExclusiveExactAndCloseDoesNotEraseReplacement() throws Exception {
      var f = fixture();
      var first = f.generator().captureNativeColumn(f.chunk(), f.generation(), f.coordinate());
      assertNull(first.claim(chunk(f.generation(), f.chunk().getPos())));
      assertThrows(IllegalStateException.class,
         () -> f.generator().captureNativeColumn(f.chunk(), f.generation(), f.coordinate()));
      first.close();
      var second = f.generator().captureNativeColumn(f.chunk(), f.generation(), f.coordinate());
      try {
         first.close();
         assertThrows(IllegalStateException.class,
            () -> f.generator().captureNativeColumn(f.chunk(), f.generation(), f.coordinate()));
         assertNotNull(second.claim(f.chunk()));
         assertSame(f.generation(), second.generation());
      } finally { second.close(); }
   }
   @Test void invalidNativeProfilesAndTargetsFailBeforeLeaseReservation() throws Exception {
      var f = fixture(); var g = f.generation();
      var invalid = List.of(
         new TellusGenerationContext(g.worldSeed() + 1, g.worldScale(), g.seaLevel(), g.minY(), g.maxYExclusive()),
         new TellusGenerationContext(g.worldSeed(), g.worldScale() * 2, g.seaLevel(), g.minY(), g.maxYExclusive()),
         new TellusGenerationContext(g.worldSeed(), g.worldScale(), g.seaLevel() + 1, g.minY(), g.maxYExclusive()),
         new TellusGenerationContext(g.worldSeed(), g.worldScale(), g.seaLevel(), g.minY() - 16, g.maxYExclusive()));
      for (var wrong : invalid) assertThrows(IllegalArgumentException.class,
         () -> f.generator().captureNativeColumn(f.chunk(), wrong, f.coordinate()));
      assertThrows(IllegalArgumentException.class,
         () -> f.generator().captureNativeColumn(chunk(g, new ChunkPos(MinecraftVersionCompat.chunkX(f.chunk().getPos()) + 1, MinecraftVersionCompat.chunkZ(f.chunk().getPos()))), g, f.coordinate()));
      var cropped = new TellusGenerationContext(g.worldSeed(), g.worldScale(), g.seaLevel(),
         g.minY(), g.maxYExclusive() - 16, g.geometry());
      assertThrows(IllegalArgumentException.class,
         () -> f.generator().captureNativeColumn(chunk(cropped, f.chunk().getPos()), g, f.coordinate()));
      try (var valid = f.generator().captureNativeColumn(f.chunk(), g, f.coordinate())) {
         assertFalse(valid.result().toCompletableFuture().isDone());
      }
   }
   @Test void capturedActualNativeSurfaceInputsReplayThroughKernelWithoutBaseQuerySubstitution() throws Exception {
      int columns = 0, cells = 0;
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         for (boolean thin : new boolean[]{false, true}) for (boolean fast : new boolean[]{false, true}) {
            var f = fixture(); var g = f.generation(); int terrain = g.minY() + 20, waterY = terrain + 3;
            int x = f.x(), z = f.z();
            assertTrue(waterY < g.maxYExclusive());
            var position = new BlockPos.MutableBlockPos();
            for (int y = g.minY(); y <= waterY; y++) {
               var material = y > terrain ? Blocks.WATER : Blocks.STONE;
               MinecraftVersionCompat.setBlockState(f.chunk(), position.set(x, y, z), material.defaultBlockState());
            }
            MinecraftVersionCompat.setBlockState(f.chunk(), position.set(x, g.minY(), z), Blocks.BEDROCK.defaultBlockState());
            MinecraftVersionCompat.setBlockState(f.chunk(), position.set(x, g.minY() + 3, z), Blocks.AIR.defaultBlockState());
            MinecraftVersionCompat.setBlockState(f.chunk(), position.set(x, g.minY() + 8, z), Blocks.DRIPSTONE_BLOCK.defaultBlockState());
            if (thin) MinecraftVersionCompat.setBlockState(f.chunk(), position.set(x, terrain, z), Blocks.AIR.defaultBlockState());
            var lease = f.generator().captureNativeColumn(f.chunk(), g, f.coordinate());
            var builder = lease.claim(f.chunk()); assertNotNull(builder);
            var writer = fast ? new EarthChunkGenerator.ChunkSectionWriter(f.chunk().getSections(), g.minY()) : null;
            try {
               builder.before(f.chunk(), writer, x, z, terrain,
                  new WaterDecision(WaterDecision.Kind.OCEAN, OptionalInt.of(waterY)), new BiomeDecision("minecraft:plains", true));
               invokeSurface(f.generator(), f.chunk(), writer, builder, thin, terrain, g.minY(), x, z);
               builder.after(f.chunk(), writer, x, z, terrain);
            } finally { if (writer != null) writer.close(); }
            // A later native phase changes the world, not the immutable captured stage.
            MinecraftVersionCompat.setBlockState(f.chunk(), position.set(x, terrain, z), Blocks.SNOW_BLOCK.defaultBlockState());
            lease.complete(builder);
            var snapshot = lease.result().toCompletableFuture().join();
            assertEquals(TellusEarthNativeColumnCapture.Stage.POST_SURFACE_PRE_SNOW_AND_SHELL_SKIN, snapshot.stage());
            assertEquals(thin ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), snapshot.before().get(20));
            assertNotEquals(Blocks.SNOW_BLOCK.defaultBlockState(), snapshot.after().get(20));
            var kernel = f.generator().semanticSurfaceColumnKernel((generation, coordinate, sample, koppen) -> snapshot.semanticBefore(),
               (generation, coordinate, sample, body) -> snapshot.surfaceContext());
            var request = TellusSampleRequest.fromContext(f.coordinate(), SamplingPurpose.TERRAIN_COLUMN,
               SamplingResolution.exact(g.worldScale()), g.geometry(), g.worldSeed());
            var sample = new TellusSample(request, SampleStatus.AVAILABLE,
               new TellusElevationSample(SampleStatus.AVAILABLE, 123456.0),
               new TellusCoverSample(SampleStatus.AVAILABLE, 10, 10),
               new TellusWaterSample(SampleStatus.AVAILABLE, true, true, 123459.0),
               TellusSampleProvenance.snapshot("native-capture-fixture", "r44", true, "resolved fixture; no GIS/full-fill claim"));
            var decision = kernel.decide(g, f.coordinate(), sample, null);
            assertEquals(DecisionStatus.AVAILABLE, decision.status());
            var writes = new TellusColumnMaterializer().materializeCells(
               WriteContext.canonical(f.coordinate(), g, "native-capture-bridge"), decision);
            assertEquals(WriteStatus.WRITTEN, writes.status());
            assertEquals(snapshot.after().size(), writes.mappedWrites().size());
            for (var cell : writes.mappedWrites()) {
               assertEquals(snapshot.after().get(cell.y() - g.minY()), TellusNativeSemanticMaterials.state(cell.material()));
               cells++;
            }
            assertEquals(snapshot.semanticAfter().orElseThrow().strata(), decision.strata());
            columns++;
         }
      }
      System.out.println("EARTH_NATIVE_CAPTURE_BRIDGE columns=" + columns + " cells=" + cells + " mismatchBlocks=0");
   }
   static void invokeSurface(EarthChunkGenerator generator, ProtoChunk target,
      EarthChunkGenerator.ChunkSectionWriter writer, TellusEarthNativeColumnCapture.Builder builder,
      boolean thin, int terrain, int minY, int x, int z) throws Exception {
      java.lang.reflect.Method method = null;
      for (var candidate : EarthChunkGenerator.class.getDeclaredMethods()) {
         var types = candidate.getParameterTypes();
         if (candidate.getName().equals(thin ? "applyThinShellSurface" : "applySurface")
            && types[types.length - 1] == TellusEarthNativeColumnCapture.Builder.class
            && types[0] == (writer == null ? ChunkAccess.class : EarthChunkGenerator.ChunkSectionWriter.class)) method = candidate;
      }
      assertNotNull(method); method.setAccessible(true);
      Class<?> profiler = Arrays.stream(EarthChunkGenerator.class.getDeclaredClasses())
         .filter(type -> type.getSimpleName().equals("SurfaceApplyProfiler")).findFirst().orElseThrow();
      var constructor = profiler.getDeclaredConstructor(); constructor.setAccessible(true);
      List<Object> args = new ArrayList<>();
      if (writer == null) { args.add(target); args.add(new BlockPos.MutableBlockPos()); args.add(x); args.add(z); }
      else { args.add(writer); args.add(x); args.add(z); args.add(x & 15); args.add(z & 15); }
      args.add(terrain); args.add(minY); args.add(true); if (thin) args.add(false);
      args.add(plains); args.add(2); args.add(0); args.add(10); args.add(null); args.add(constructor.newInstance());
      args.add(true); args.add(builder); method.invoke(generator, args.toArray());
   }
}
