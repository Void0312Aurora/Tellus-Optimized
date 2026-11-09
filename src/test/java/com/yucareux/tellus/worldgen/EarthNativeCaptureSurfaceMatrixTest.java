package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Resolved-source native entrypoint and explicit null-palette seam matrix, not GIS/full-world evidence. */
class EarthNativeCaptureSurfaceMatrixTest {
   static final int[][] POINTS = {{0, 0}, {31, -17}, {-4097, 8193}};
   @TempDir static Path directory;
   static String oldGame, oldConfig;
   static ClassLoader oldLoader;
   static Registry<Biome> biomes;
   static Map<ResourceKey<Biome>, Holder<Biome>> holders = new HashMap<>();
   static Constructor<?> proto, profiler;
   static Class<?> paletteType, resolvedPlan;
   static Method record, prepare, resolveThinTop, applyCursor, applySections;
   static EarthChunkGenerator generator;
   static TellusGenerationContext generation;

   @BeforeAll static void bootstrap() throws Exception {
      oldGame = System.getProperty("tellus.gameDir"); oldConfig = System.getProperty("tellus.configDir");
      oldLoader = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(EarthNativeCaptureSurfaceMatrixTest.class.getClassLoader());
      System.setProperty("tellus.gameDir", directory.toString());
      System.setProperty("tellus.configDir", directory.resolve("config").toString());
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Class.forName(EarthChunkGenerator.class.getName(), true, EarthNativeCaptureSurfaceMatrixTest.class.getClassLoader());
      }
      var lookup = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME);
      Method bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class); bindTags.setAccessible(true);
      for (var key : List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.BADLANDS, Biomes.STONY_PEAKS)) {
         var holder = lookup.getOrThrow(key);
         bindTags.invoke(holder, key.equals(Biomes.BADLANDS) ? List.of(BiomeTags.IS_BADLANDS) : List.of());
         holders.put(key, holder);
      }
      biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
      Registry.register(biomes, Biomes.PLAINS, holders.get(Biomes.PLAINS).value());
      proto = ProtoChunk.class.getDeclaredConstructor(ChunkPos.class, UpgradeData.class, LevelHeightAccessor.class,
         Registry.class, Class.forName("net.minecraft.world.level.levelgen.blending.BlendingData")); proto.setAccessible(true);
      paletteType = nested("SurfacePalette"); resolvedPlan = nested("ResolvedSurfacePlan");
      profiler = nested("SurfaceApplyProfiler").getDeclaredConstructor(); profiler.setAccessible(true);
      record = method("recordNativeSurfaceCapture", TellusEarthNativeColumnCapture.Builder.class, int.class, int.class,
         paletteType, StrataPolicy.Column.class, BlockState.class, boolean.class, boolean.class, boolean.class, int.class, Holder.class);
      prepare = method("prepareResolvedSurfacePlan", paletteType, StrataPolicy.Column.class, BlockState.class,
         boolean.class, boolean.class, boolean.class, int.class, int.class, int.class, int.class, int.class);
      resolveThinTop = method("resolveThinShellTopBlock", paletteType, int.class, boolean.class);
      applyCursor = method("applyResolvedSurfacePlan", ChunkAccess.class, BlockPos.MutableBlockPos.class,
         int.class, int.class, resolvedPlan);
      applySections = method("applyResolvedSurfacePlan", EarthChunkGenerator.ChunkSectionWriter.class,
         int.class, int.class, resolvedPlan);
      var encoded = EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, EarthGeneratorSettings.DEFAULT)
         .result().orElseThrow().getAsJsonObject(); encoded.addProperty("ore_distribution", false);
      var settings = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         generator = new EarthChunkGenerator(new FixedBiomeSource(holders.get(Biomes.PLAINS)), settings);
      }
      double scale = settings.worldScale();
      generation = new TellusGenerationContext(generator.worldSeed(), scale, generator.getSeaLevel(), generator.getMinY(),
         Math.addExact(generator.getMinY(), generator.getGenDepth()),
         GeometryIdentity.forProvider("tellus:earth", "capture-surface-matrix-r48", "earth-projection-v1", "earth-noise-v1", scale));
   }
   @AfterAll static void restore() {
      if (oldGame == null) System.clearProperty("tellus.gameDir"); else System.setProperty("tellus.gameDir", oldGame);
      if (oldConfig == null) System.clearProperty("tellus.configDir"); else System.setProperty("tellus.configDir", oldConfig);
      Thread.currentThread().setContextClassLoader(oldLoader);
   }
   static Class<?> nested(String name) {
      return Arrays.stream(EarthChunkGenerator.class.getDeclaredClasses()).filter(type -> type.getSimpleName().equals(name)).findFirst().orElseThrow();
   }
   static Method method(String name, Class<?>... types) throws Exception {
      var method = EarthChunkGenerator.class.getDeclaredMethod(name, types); method.setAccessible(true); return method;
   }
   record Fixture(TellusCoordinateContext coordinate, ProtoChunk target, int x, int z, int terrain) { }
   static Fixture fixture(int x, int z, boolean thin) throws Exception {
      double scale = generation.worldScale();
      var geo = new SphereWorldModel.GeoPoint(EarthProjection.blockZToLat(z + 0.25, scale), EarthProjection.blockXToLongitude(x + 0.25, scale), 0.0);
      var earth = EarthCompatibilityPoint.fromGeoPoint(geo, scale);
      assertEquals(x, earth.floorBlockX()); assertEquals(z, earth.floorBlockZ());
      var coordinate = TellusCoordinateContextFactory.fromGeo(SphereWorldModel.forWorldScale(scale), geo, new EarthOwnerView(earth));
      var target = (ProtoChunk)proto.newInstance(new ChunkPos(x >> 4, z >> 4), UpgradeData.EMPTY,
         LevelHeightAccessor.create(generation.minY(), generation.maxYExclusive() - generation.minY()), biomes, null);
      int terrain = generation.minY() + 24;
      var cursor = new BlockPos.MutableBlockPos();
      for (int y = generation.minY(); y <= terrain; y++) MinecraftVersionCompat.setBlockState(target, cursor.set(x, y, z), Blocks.STONE.defaultBlockState());
      MinecraftVersionCompat.setBlockState(target, cursor.set(x, generation.minY(), z), Blocks.BEDROCK.defaultBlockState());
      MinecraftVersionCompat.setBlockState(target, cursor.set(x, generation.minY() + 3, z), Blocks.AIR.defaultBlockState());
      MinecraftVersionCompat.setBlockState(target, cursor.set(x, generation.minY() + 7, z), Blocks.BEDROCK.defaultBlockState());
      if (thin) MinecraftVersionCompat.setBlockState(target, cursor.set(x, terrain, z), Blocks.AIR.defaultBlockState());
      return new Fixture(coordinate, target, x, z, terrain);
   }
   static List<BlockState> column(Fixture f) {
      List<BlockState> states = new ArrayList<>();
      for (int y = generation.minY(); y <= f.terrain(); y++) states.add(f.target().getBlockState(new BlockPos(f.x(), y, f.z())));
      return List.copyOf(states);
   }
   record Scenario(String name, ResourceKey<Biome> biome, int cover, int relief) { }
   static void invokeSurface(Fixture f, EarthChunkGenerator.ChunkSectionWriter writer,
      TellusEarthNativeColumnCapture.Builder builder, boolean thin, Scenario scenario) throws Exception {
      Method selected = null;
      for (var candidate : EarthChunkGenerator.class.getDeclaredMethods()) {
         var types = candidate.getParameterTypes();
         if (candidate.getName().equals(thin ? "applyThinShellSurface" : "applySurface")
            && types[types.length - 1] == TellusEarthNativeColumnCapture.Builder.class
            && types[0] == (writer == null ? ChunkAccess.class : EarthChunkGenerator.ChunkSectionWriter.class)) {
            assertNull(selected); selected = candidate;
         }
      }
      assertNotNull(selected); selected.setAccessible(true);
      List<Object> args = new ArrayList<>();
      if (writer == null) { args.add(f.target()); args.add(new BlockPos.MutableBlockPos()); args.add(f.x()); args.add(f.z()); }
      else { args.add(writer); args.add(f.x()); args.add(f.z()); args.add(f.x() & 15); args.add(f.z() & 15); }
      args.add(f.terrain()); args.add(generation.minY()); args.add(false); if (thin) args.add(false);
      args.add(holders.get(scenario.biome())); args.add(scenario.relief()); args.add(0); args.add(scenario.cover());
      args.add(null); args.add(profiler.newInstance()); args.add(true); args.add(builder);
      selected.invoke(generator, args.toArray());
   }
   static TellusEarthNativeColumnCapture.Snapshot capture(Fixture f, boolean thin, boolean fast,
      Scenario scenario, boolean nullPaletteSeam) throws Exception {
      try (var lease = generator.captureNativeColumn(f.target(), generation, f.coordinate())) {
         var builder = lease.claim(f.target()); assertNotNull(builder);
         var writer = fast ? new EarthChunkGenerator.ChunkSectionWriter(f.target().getSections(), generation.minY()) : null;
         try {
            builder.before(f.target(), writer, f.x(), f.z(), f.terrain(), WaterDecision.dry(), new BiomeDecision(scenario.biome().location().toString(), true));
            if (nullPaletteSeam) applyNullPalette(f, writer, builder, thin);
            else invokeSurface(f, writer, builder, thin, scenario);
            builder.after(f.target(), writer, f.x(), f.z(), f.terrain());
         } finally { if (writer != null) writer.close(); }
         lease.complete(builder);
         return lease.result().toCompletableFuture().join();
      }
   }
   static void applyNullPalette(Fixture f, EarthChunkGenerator.ChunkSectionWriter writer,
      TellusEarthNativeColumnCapture.Builder builder, boolean thin) throws Exception {
      // Explicit resolved-source seam: current native palette selectors need not produce null.
      BlockState top = thin ? (BlockState)resolveThinTop.invoke(generator, null, f.terrain(), false) : null;
      record.invoke(generator, builder, f.x(), f.z(), null, null, top, thin, false, false, 0, holders.get(Biomes.PLAINS));
      Object plan = prepare.invoke(generator, null, null, top, thin, false, false, 0, f.x(), f.z(), f.terrain(), generation.minY());
      if (thin) assertNotNull(plan); else assertNull(plan);
      if (writer == null) applyCursor.invoke(generator, f.target(), new BlockPos.MutableBlockPos(), f.x(), f.z(), plan);
      else applySections.invoke(generator, writer, f.x() & 15, f.z() & 15, plan);
   }
   static ColumnDecision decision(Fixture f, TellusEarthNativeColumnCapture.Snapshot snapshot) {
      var kernel = generator.semanticSurfaceColumnKernel((g,c,s,k) -> snapshot.semanticBefore(), (g,c,s,b) -> snapshot.surfaceContext());
      var request = TellusSampleRequest.fromContext(f.coordinate(), SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.exact(generation.worldScale()), generation.geometry(), generation.worldSeed());
      var sample = new TellusSample(request, SampleStatus.AVAILABLE, new TellusElevationSample(SampleStatus.AVAILABLE, 0.0),
         new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30), TellusWaterSample.dry(),
         TellusSampleProvenance.snapshot("resolved-native-source-matrix", "r48", true, "source-boundary fixture, not GIS"));
      return kernel.decide(generation, f.coordinate(), sample, null);
   }
   static int assertReplay(Fixture f, TellusEarthNativeColumnCapture.Snapshot snapshot) {
      assertTrue(snapshot.semanticBefore().isPresent()); assertTrue(snapshot.semanticAfter().isPresent());
      assertFalse(snapshot.surfaceContext().orElseThrow().underwater());
      assertEquals(TellusEarthNativeColumnCapture.Stage.POST_SURFACE_PRE_SNOW_AND_SHELL_SKIN, snapshot.stage());
      assertEquals(snapshot.after(), column(f));
      var decision = decision(f, snapshot); assertEquals(DecisionStatus.AVAILABLE, decision.status());
      var replay = new TellusColumnMaterializer().materializeCells(WriteContext.canonical(f.coordinate(), generation, "capture-surface-matrix"), decision);
      assertEquals(WriteStatus.WRITTEN, replay.status()); assertEquals(snapshot.after().size(), replay.mappedWrites().size());
      for (var cell : replay.mappedWrites()) assertEquals(snapshot.after().get(cell.y() - generation.minY()), TellusNativeSemanticMaterials.state(cell.material()));
      assertEquals(snapshot.semanticAfter().orElseThrow().strata(), decision.strata());
      return replay.mappedWrites().size();
   }

   @Test void actualDryDesertBadlandsAndStrataCaptureMatchNullCollectorAndSemanticReplay() throws Exception {
      int columns = 0, cells = 0, badlands = 0, strata = 0;
      List<Scenario> scenarios = List.of(new Scenario("dry-grass", Biomes.PLAINS, 30, 0),
         new Scenario("dry-desert", Biomes.DESERT, 60, 0), new Scenario("badlands", Biomes.BADLANDS, 60, 16),
         new Scenario("strata", Biomes.STONY_PEAKS, 60, 0));
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         for (int[] point : POINTS) for (var scenario : scenarios) for (boolean thin : new boolean[]{false, true}) for (boolean fast : new boolean[]{false, true}) {
            var off = fixture(point[0], point[1], thin); var on = fixture(point[0], point[1], thin);
            var writer = fast ? new EarthChunkGenerator.ChunkSectionWriter(off.target().getSections(), generation.minY()) : null;
            try { invokeSurface(off, writer, null, thin, scenario); } finally { if (writer != null) writer.close(); }
            var snapshot = capture(on, thin, fast, scenario, false);
            assertEquals(column(off), snapshot.after(), scenario.name() + ": collector must not alter native output");
            var context = snapshot.surfaceContext().orElseThrow();
            assertEquals(thin, context.thinShell()); assertNotNull(context.palette());
            assertEquals(scenario.biome().location().toString(), context.biome().id());
            if (scenario.name().equals("badlands")) { assertTrue(context.badlands()); badlands++; }
            if (scenario.name().equals("strata")) { assertTrue(context.palette().strata()); assertNotNull(context.strata()); strata++; }
            cells += assertReplay(on, snapshot); columns++;
         }
      }
      assertEquals(48, columns); assertEquals(1200, cells); assertEquals(12, badlands); assertEquals(12, strata);
      System.out.println("EARTH_NATIVE_CAPTURE_SURFACE_MATRIX columns=" + columns + " cells=" + cells + " mismatchBlocks=0 badlands=" + badlands + " strata=" + strata);
   }
   @Test void explicitNullPaletteSeamDistinguishesThinTopOnlyAndFullKnownNoOpOnBothBackends() throws Exception {
      int columns = 0, cells = 0, tops = 0, noOps = 0;
      var scenario = new Scenario("resolved-null-palette", Biomes.PLAINS, 30, 0);
      for (int[] point : POINTS) for (boolean thin : new boolean[]{false, true}) for (boolean fast : new boolean[]{false, true}) {
         var f = fixture(point[0], point[1], thin);
         var snapshot = capture(f, thin, fast, scenario, true); var context = snapshot.surfaceContext().orElseThrow();
         assertNull(context.palette()); assertNull(context.strata()); assertEquals(thin, context.thinShell());
         int changed = 0;
         for (int i = 0; i < snapshot.before().size(); i++) if (!snapshot.before().get(i).equals(snapshot.after().get(i))) changed++;
         if (thin) { assertEquals(1, changed); assertEquals(Blocks.DEEPSLATE.defaultBlockState(), context.top()); tops++; }
         else { assertEquals(0, changed); assertNull(context.top()); assertEquals(snapshot.before(), snapshot.after()); noOps++; }
         cells += assertReplay(f, snapshot); columns++;
      }
      assertEquals(12, columns); assertEquals(300, cells); assertEquals(6, tops); assertEquals(6, noOps);
      System.out.println("EARTH_NATIVE_CAPTURE_NULL_PALETTE columns=" + columns + " cells=" + cells + " mismatchBlocks=0 topOnly=" + tops + " knownNoOp=" + noOps);
   }
   @Test void absentNativeContextIsUnknownRatherThanKnownNoOp() throws Exception {
      var f = fixture(0, 0, false);
      try (var lease = generator.captureNativeColumn(f.target(), generation, f.coordinate())) {
         var builder = lease.claim(f.target());
         builder.before(f.target(), null, f.x(), f.z(), f.terrain(), WaterDecision.dry(), new BiomeDecision("minecraft:plains", true));
         builder.after(f.target(), null, f.x(), f.z(), f.terrain()); lease.complete(builder);
         var snapshot = lease.result().toCompletableFuture().join();
         assertTrue(snapshot.surfaceContext().isEmpty()); assertTrue(snapshot.semanticBefore().isPresent());
         assertEquals(snapshot.before(), snapshot.after()); assertEquals(DecisionStatus.UNKNOWN, decision(f, snapshot).status());
      }
   }
}
