package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.yucareux.tellus.world.data.osm.OsmWaterTile;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.preload.*;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real fill control flow over synthetic disk elevation/cover and empty vector-water inputs; not GIS or save acceptance. */
class EarthNativeFillCaptureIntegrationTest {
   private static final int TERRAIN = 80;
   @TempDir static Path directory;
   static String oldGame, oldConfig;
   static ClassLoader oldLoader;
   static HolderLookup.Provider lookup;
   static Holder<Biome> plains;
   static Registry<Biome> biomes;
   static Constructor<?> proto;
   static EarthGeneratorSettings settings;
   static RandomState random;
   static TellusOsmWaterSource waterSource;
   static final Map<String, Object> originalWaterFields = new LinkedHashMap<>();

   @BeforeAll static void bootstrap() throws Exception {
      oldGame = System.getProperty("tellus.gameDir"); oldConfig = System.getProperty("tellus.configDir");
      oldLoader = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(EarthNativeFillCaptureIntegrationTest.class.getClassLoader());
      System.setProperty("tellus.gameDir", directory.toString());
      System.setProperty("tellus.configDir", directory.resolve("config").toString());
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Class.forName(EarthChunkGenerator.class.getName(), true, EarthNativeFillCaptureIntegrationTest.class.getClassLoader());
      }
      lookup = MinecraftTestRegistries.create();
      plains = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
      biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
      Registry.register(biomes, Biomes.PLAINS, plains.value());
      proto = ProtoChunk.class.getDeclaredConstructor(ChunkPos.class, UpgradeData.class,
         LevelHeightAccessor.class, Registry.class, Class.forName("net.minecraft.world.level.levelgen.blending.BlendingData"));
      proto.setAccessible(true);
      JsonObject encoded = EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, EarthGeneratorSettings.DEFAULT)
         .result().orElseThrow().getAsJsonObject();
      encoded.addProperty("enable_roads", false); encoded.addProperty("enable_buildings", false);
      encoded.addProperty("ore_distribution", false);
      settings = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();
      assertFalse(settings.enableRoads()); assertFalse(settings.enableBuildings());
      assertTrue(settings.enableWater(), "Current native water remains enabled; do not assume legacy false disables it");
      random = RandomState.create(lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
         .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value(), lookup.lookupOrThrow(Registries.NOISE), 0L);
      installPreload();
      waterSource = TellusWorldgenSources.osmWater();
      replaceWaterField("initialized", true); replaceWaterField("available", true);
      replaceWaterField("minZoom", 0); replaceWaterField("maxZoom", 0);
      replaceWaterField("cache", CacheBuilder.newBuilder().build(new CacheLoader<Object, OsmWaterTile>() {
         @Override public OsmWaterTile load(Object key) { return OsmWaterTile.empty(); }
      }));
      TellusWorldgenSources.waterResolver(settings).clearCache();
   }
   private static void replaceWaterField(String name, Object value) throws Exception {
      var field = TellusOsmWaterSource.class.getDeclaredField(name); field.setAccessible(true);
      originalWaterFields.put(name, field.get(waterSource)); field.set(waterSource, value);
   }
   @AfterAll static void restore() throws Exception {
      try {
         TerrainPreloadStorage storage = TerrainPreloadStorage.instance();
         assertTrue(storage.root().toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()));
         storage.deletePackage("native-fill-capture-r47");
         registryBarrier("REFRESH_EXECUTOR"); registryBarrier("LOAD_EXECUTOR");
      } finally {
         try {
            if (waterSource != null) for (var original : originalWaterFields.entrySet()) {
               var field = TellusOsmWaterSource.class.getDeclaredField(original.getKey()); field.setAccessible(true);
               field.set(waterSource, original.getValue());
            }
            if (settings != null) TellusWorldgenSources.waterResolver(settings).clearCache();
         } finally {
            if (oldGame == null) System.clearProperty("tellus.gameDir"); else System.setProperty("tellus.gameDir", oldGame);
            if (oldConfig == null) System.clearProperty("tellus.configDir"); else System.setProperty("tellus.configDir", oldConfig);
            Thread.currentThread().setContextClassLoader(oldLoader);
         }
      }
   }

   private static void installPreload() throws Exception {
      TerrainPreloadStorage storage = TerrainPreloadStorage.instance();
      var encoded = storage.encodeSettings(settings);
      String fingerprint = storage.settingsFingerprint(encoded);
      String id = "native-fill-capture-r47";
      var area = TerrainPreloadArea.fromChunkBounds(0.0, 0.0, 65, settings.worldScale(), -32, -32, 32, 32);
      int width = (area.maxChunkX() - area.minChunkX() + 1) * 16;
      int depth = (area.maxChunkZ() - area.minChunkZ() + 1) * 16;
      int size = Math.multiplyExact(width, depth);
      int[] heights = new int[size]; Arrays.fill(heights, TERRAIN);
      byte[] covers = new byte[size]; Arrays.fill(covers, (byte)30);
      byte[] land = new byte[size]; Arrays.fill(land, (byte)1);
      Path output = storage.publishedDirectory(id); Files.createDirectories(output);
      Path file = output.resolve(TerrainPreloadPackage.FILE_NAME);
      var write = TerrainPreloadPackage.class.getDeclaredMethod("write", Path.class, String.class, String.class,
         TerrainPreloadArea.class, int.class, int.class, int.class, int[].class, byte[].class, byte[].class, byte[].class);
      write.setAccessible(true);
      long bytes = (Long)write.invoke(null, file, id, fingerprint, area, 1, width, depth, heights, covers, land, new byte[size]);
      storage.writeManifest(output, new TerrainPreloadManifest(id, TerrainPreloadManifest.FORMAT_VERSION,
         System.currentTimeMillis(), area, fingerprint, encoded, 0, 0, "completed", TerrainPreloadPackage.FILE_NAME,
         TerrainPreloadPackage.FORMAT_VERSION, bytes, 1, width, depth));
      TerrainPreloadPackageRegistry registry = TerrainPreloadPackageRegistry.instance();
      registry.invalidate(); registryBarrier("REFRESH_EXECUTOR");
      var view = registry.viewFor(settings);
      view.sample(0, 0, settings.worldScale()); // Admit the one asynchronous package load.
      registryBarrier("LOAD_EXECUTOR");
      for (int z : new int[]{-64, -4, 0, 15, 19, 64}) for (int x : new int[]{-64, -4, 0, 15, 19, 64}) {
         var sample = view.sample(x, z, settings.worldScale());
         assertNotNull(sample, "Preload source must be ready before fill starts");
         assertEquals(TERRAIN, sample.terrainHeight()); assertTrue(sample.landMaskKnown()); assertTrue(sample.land());
      }
   }
   private static void registryBarrier(String name) throws Exception {
      var field = TerrainPreloadPackageRegistry.class.getDeclaredField(name); field.setAccessible(true);
      ((ExecutorService)field.get(null)).submit(() -> { }).get(15, TimeUnit.SECONDS);
   }
   record Fixture(EarthChunkGenerator generator, TellusGenerationContext generation,
                  TellusCoordinateContext coordinate, ProtoChunk chunk) { }
   private static Fixture fixture() throws Exception {
      EarthChunkGenerator generator;
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         generator = new EarthChunkGenerator(new FixedBiomeSource(plains), settings);
      }
      double scale = settings.worldScale();
      var geo = new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0);
      var coordinate = TellusCoordinateContextFactory.fromGeo(SphereWorldModel.forWorldScale(scale), geo,
         new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo, scale)));
      var generation = new TellusGenerationContext(generator.worldSeed(), scale, generator.getSeaLevel(), generator.getMinY(),
         Math.addExact(generator.getMinY(), generator.getGenDepth()),
         GeometryIdentity.forProvider("tellus:earth", "native-full-fill-r47", "earth-projection-v1", "earth-noise-v1", scale));
      assertTrue(TERRAIN >= generation.minY() && TERRAIN < generation.maxYExclusive());
      return new Fixture(generator, generation, coordinate, chunk(generation));
   }
   private static ProtoChunk chunk(TellusGenerationContext generation) throws Exception {
      return (ProtoChunk)proto.newInstance(new ChunkPos(0, 0), UpgradeData.EMPTY,
         LevelHeightAccessor.create(generation.minY(), generation.maxYExclusive() - generation.minY()), biomes, null);
   }
   private static class EmptyStructures extends StructureManager {
      int queries;
      EmptyStructures() { super(null, new WorldOptions(0L, false, false), null); }
      @Override public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> predicate) {
         queries++; return List.of();
      }
   }
   private static ChunkAccess fill(Fixture f, ProtoChunk chunk, RandomState randomState, StructureManager structures) {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         return f.generator().fillFromNoise(Blender.empty(), randomState, structures, chunk).join();
      }
   }

   @Test void completeNativeFillPreservesEveryBlockWithCaptureEnabledAndReleasesBeforeDelivery() throws Exception {
      var f = fixture(); var g = f.generation(); var off = f.chunk(); var on = chunk(g);
      var structures = new EmptyStructures();
      int missingBefore = TellusElevationSource.unavailableTileReads();
      assertSame(off, fill(f, off, random, structures));
      assertTrue(structures.queries > 0, "Actual native structure protection/clearance must run");
      AtomicInteger deliveries = new AtomicInteger();
      try (var lease = f.generator().captureNativeColumn(on, g, f.coordinate())) {
         var callback = lease.result().thenAccept(snapshot -> {
            assertEquals(TERRAIN, snapshot.terrainY());
            assertEquals(TellusEarthNativeColumnCapture.Stage.POST_SURFACE_PRE_SNOW_AND_SHELL_SKIN, snapshot.stage());
            // Reentrant successor registration proves old slot was released before observer delivery.
            try (var successor = f.generator().captureNativeColumn(on, g, f.coordinate())) {
               assertFalse(successor.result().toCompletableFuture().isDone());
            }
            // Access every section on another thread; a retained writer lock fails within a bound.
            var executor = Executors.newSingleThreadExecutor();
            try {
               executor.submit(() -> {
                  for (var section : on.getSections()) {
                     section.acquire(); try { section.getStates().get(0, 0, 0); } finally { section.release(); }
                  }
               }).get(5, TimeUnit.SECONDS);
            } catch (Exception error) { throw new AssertionError("Native sections still owned during delivery", error); }
            finally { executor.shutdownNow(); }
            deliveries.incrementAndGet();
         });
         assertSame(on, fill(f, on, random, structures));
         callback.toCompletableFuture().get(5, TimeUnit.SECONDS);
         var snapshot = lease.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
         assertEquals(1, deliveries.get()); assertTrue(snapshot.surfaceContext().isPresent());
         assertTrue(snapshot.semanticBefore().isPresent()); assertTrue(snapshot.semanticAfter().isPresent());
         assertFalse(snapshot.surfaceContext().orElseThrow().underwater());
         assertEquals(WaterDecision.Kind.NONE, snapshot.water().kind());
         assertTrue(snapshot.before().get(TERRAIN - g.minY()).isAir(), "Real thin-shell pre-surface top is AIR");
         var kernel = f.generator().semanticSurfaceColumnKernel((generation, coordinate, sample, koppen) -> snapshot.semanticBefore(),
            (generation, coordinate, sample, body) -> snapshot.surfaceContext());
         var request = TellusSampleRequest.fromContext(f.coordinate(), SamplingPurpose.TERRAIN_COLUMN,
            SamplingResolution.exact(g.worldScale()), g.geometry(), g.worldSeed());
         var sample = new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, (TERRAIN - g.seaLevel()) * g.worldScale()),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30), TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("synthetic-native-fill", "r47", true, "known synthetic package, not GIS"));
         var decision = kernel.decide(g, f.coordinate(), sample, null);
         assertEquals(DecisionStatus.AVAILABLE, decision.status());
         var replay = new TellusColumnMaterializer().materializeCells(
            WriteContext.canonical(f.coordinate(), g, "native-full-fill-capture"), decision);
         assertEquals(WriteStatus.WRITTEN, replay.status());
         assertEquals(snapshot.after().size(), replay.mappedWrites().size());
         for (var cell : replay.mappedWrites()) {
            assertEquals(snapshot.after().get(cell.y() - g.minY()), TellusNativeSemanticMaterials.state(cell.material()));
         }
         assertEquals(snapshot.semanticAfter().orElseThrow().strata(), decision.strata());
         System.out.println("EARTH_NATIVE_FULL_FILL_REPLAY columns=1 cells=" + replay.mappedWrites().size() + " mismatchBlocks=0");
         int compared = 0;
         var position = new BlockPos.MutableBlockPos();
         for (int y = g.minY(); y < g.maxYExclusive(); y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(off.getBlockState(position.set(x, y, z)), on.getBlockState(position), "capture changed native output");
            compared++;
         }
         assertEquals(missingBefore, TellusElevationSource.unavailableTileReads(), "Synthetic preload must cover elevation queries");
         var pending = EarthChunkGenerator.class.getDeclaredMethod("hasPendingTerrainRefinement", ChunkPos.class);
         pending.setAccessible(true);
         assertEquals(false, pending.invoke(f.generator(), on.getPos()), "No native refinement may remain pending in the controlled fixture");
         System.out.println("EARTH_NATIVE_FULL_FILL chunks=2 comparedBlocks=" + compared + " mismatchBlocks=0 captures=" + deliveries.get());
      }
   }

   @Test void earlyNativeFailureFailsCaptureWithSameCauseAndReleasesItsSlot() throws Exception {
      var f = fixture();
      try (var lease = f.generator().captureNativeColumn(f.chunk(), f.generation(), f.coordinate())) {
         var error = assertThrows(NullPointerException.class, () -> fill(f, f.chunk(), null, new EmptyStructures()));
         var completion = assertThrows(CompletionException.class, () -> lease.result().toCompletableFuture().join());
         assertSame(error, completion.getCause());
         try (var replacement = f.generator().captureNativeColumn(f.chunk(), f.generation(), f.coordinate())) {
            assertFalse(replacement.result().toCompletableFuture().isDone());
         }
      }
   }

   @Test void lateNativeFailureAfterColumnCopyDoesNotPublishSuccessAndReleasesSections() throws Exception {
      var f = fixture(); var expected = new IllegalStateException("late native structure fixture");
      var structures = new EmptyStructures() {
         @Override public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> predicate) { throw expected; }
      };
      AtomicInteger successes = new AtomicInteger();
      try (var lease = f.generator().captureNativeColumn(f.chunk(), f.generation(), f.coordinate())) {
         lease.result().thenAccept(snapshot -> successes.incrementAndGet());
         assertSame(expected, assertThrows(IllegalStateException.class, () -> fill(f, f.chunk(), random, structures)));
         var completion = assertThrows(CompletionException.class, () -> lease.result().toCompletableFuture().join());
         assertSame(expected, completion.getCause()); assertEquals(0, successes.get());
         // Body/surface writes have happened before protection's injected failure.
         assertFalse(f.chunk().getBlockState(new BlockPos(0, TERRAIN, 0)).isAir());
         for (var section : f.chunk().getSections()) {
            section.acquire(); try { section.getStates().get(0, 0, 0); } finally { section.release(); }
         }
         try (var replacement = f.generator().captureNativeColumn(f.chunk(), f.generation(), f.coordinate())) {
            assertFalse(replacement.result().toCompletableFuture().isDone());
         }
      }
   }
}
