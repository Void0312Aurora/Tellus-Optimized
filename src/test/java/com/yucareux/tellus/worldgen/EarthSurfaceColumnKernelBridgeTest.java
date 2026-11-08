package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrassBlock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the actual explicit native surface-kernel factory with resolved source facts. */
class EarthSurfaceColumnKernelBridgeTest {
   @TempDir static Path directory;
   static String oldGame, oldConfig;
   static ClassLoader oldLoader;
   @BeforeAll static void bootstrap() throws Exception {
      oldGame = System.getProperty("tellus.gameDir"); oldConfig = System.getProperty("tellus.configDir");
      oldLoader = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(EarthSurfaceColumnKernelBridgeTest.class.getClassLoader());
      System.setProperty("tellus.gameDir", directory.toString());
      System.setProperty("tellus.configDir", directory.resolve("config").toString());
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         Class.forName(EarthChunkGenerator.class.getName(), true, EarthSurfaceColumnKernelBridgeTest.class.getClassLoader());
      }
   }
   @AfterAll static void restore() {
      if (oldGame == null) System.clearProperty("tellus.gameDir"); else System.setProperty("tellus.gameDir", oldGame);
      if (oldConfig == null) System.clearProperty("tellus.configDir"); else System.setProperty("tellus.configDir", oldConfig);
      Thread.currentThread().setContextClassLoader(oldLoader);
   }
   record Fixture(EarthChunkGenerator nativeGenerator, TellusGenerationContext generation,
                  TellusCoordinateContext coordinate, TellusSample sample, TellusEarthColumnSnapshot body) { }
   private static Fixture fixture() throws Exception {
      var plains = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
      EarthChunkGenerator generator;
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         generator = new EarthChunkGenerator(new FixedBiomeSource(plains), EarthGeneratorSettings.DEFAULT);
      }
      var seedField = EarthChunkGenerator.class.getDeclaredField("worldSeed"); seedField.setAccessible(true);
      long seed = seedField.getLong(generator);
      double scale = generator.settings().worldScale();
      var geometry = GeometryIdentity.forProvider("tellus:earth", "surface-bridge-r38",
         "earth-projection-v1", "earth-noise-v1", scale);
      var geo = new SphereWorldModel.GeoPoint(46.0, 12.0, 0.0);
      var coordinate = TellusCoordinateContextFactory.fromGeo(SphereWorldModel.forWorldScale(scale), geo,
         new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo, scale)));
      var generation = new TellusGenerationContext(seed, scale, generator.getSeaLevel(), generator.getMinY(),
         generator.getMinY() + generator.getGenDepth(), geometry);
      var request = TellusSampleRequest.fromContext(coordinate, SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.exact(scale), geometry, seed);
      var sample = new TellusSample(request, SampleStatus.AVAILABLE,
         new TellusElevationSample(SampleStatus.AVAILABLE, 1000000.0),
         new TellusCoverSample(SampleStatus.AVAILABLE, 10, 10), TellusWaterSample.dry(),
         TellusSampleProvenance.snapshot("surface-fixture", "r38", true, "resolved source facts"));
      int min = generation.minY(), top = min + 8;
      var body = new TellusEarthColumnSnapshot(generation, coordinate, top,
         new SurfaceDecision(SemanticMaterial.STONE, SemanticMaterial.STONE, 0), WaterDecision.dry(), BiomeDecision.unknown(),
         new StrataDecision(List.of(new StrataDecision.Layer(min, min, SemanticMaterial.BEDROCK),
            new StrataDecision.Layer(min + 1, top, SemanticMaterial.STONE)), true), List.of("actual-body fixture"));
      return new Fixture(generator, generation, coordinate, sample, body);
   }
   private static EarthChunkGenerator.ResolvedSurfaceContext sand() {
      return new EarthChunkGenerator.ResolvedSurfaceContext(new EarthChunkGenerator.ResolvedSurfacePalette(
         Blocks.SAND.defaultBlockState(), Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), 4, false, false),
         null, Blocks.SAND.defaultBlockState(), true, false, false, 0, new BiomeDecision("minecraft:desert", true));
   }
   private static ColumnDecision decide(Fixture f, TellusEarthColumnDecisionKernel kernel) {
      return kernel.decide(f.generation(), f.coordinate(), f.sample(), null);
   }
   private static SemanticMaterial at(ColumnDecision d, int y) {
      return d.strata().layers().stream().filter(l -> y >= l.minimumY() && y <= l.maximumY()).findFirst().orElseThrow().material();
   }
   @Test void realFactoryCombinesResolvedBodyAndExactSurfaceWithoutResamplingRawMetres() throws Exception {
      var f = fixture(); var bodies = new AtomicInteger(); var surfaces = new AtomicInteger();
      var kernel = f.nativeGenerator().semanticSurfaceColumnKernel((g,c,s,k) -> {
         bodies.incrementAndGet(); assertSame(f.sample(), s); return Optional.of(f.body());
      }, (g,c,s,body) -> { surfaces.incrementAndGet(); assertSame(f.body(), body); return Optional.of(sand()); });
      var result = decide(f, kernel);
      assertEquals(1, bodies.get()); assertEquals(1, surfaces.get());
      assertEquals(DecisionStatus.AVAILABLE, result.status());
      assertEquals(f.body().terrainY(), result.terrainY().orElseThrow());
      assertEquals(SemanticMaterial.SAND, result.surface().top()); assertEquals(0, result.surface().depth());
      assertEquals("minecraft:desert", result.biome().id());
      assertEquals(SemanticMaterial.BEDROCK, at(result, f.generation().minY()));
      int depth = SurfaceMaterialDepthPolicy.naturalDepth(4,
         Math.toIntExact(((EarthOwnerView)f.coordinate().owner()).point().floorBlockX()),
         Math.toIntExact(((EarthOwnerView)f.coordinate().owner()).point().floorBlockZ()), f.generation().worldSeed());
      int bottom = Math.min(f.body().terrainY(), Math.max(f.generation().minY() + 1, f.body().terrainY() - depth + 1));
      assertEquals(SemanticMaterial.SANDSTONE, at(result, bottom));
      for (int y = bottom + 1; y <= f.body().terrainY(); y++) assertEquals(SemanticMaterial.SAND, at(result, y));
      assertEquals(f.body().water(), result.water());
   }
   @Test void missingBodyAndMissingSurfaceStayUnknownWithoutFallbacks() throws Exception {
      var f = fixture(); var contexts = new AtomicInteger();
      var missing = f.nativeGenerator().semanticSurfaceColumnKernel((g,c,s,k) -> Optional.empty(),
         (g,c,s,b) -> { contexts.incrementAndGet(); return Optional.of(sand()); });
      assertEquals(DecisionStatus.UNKNOWN, decide(f, missing).status()); assertEquals(0, contexts.get());
      var missingSurface = f.nativeGenerator().semanticSurfaceColumnKernel((g,c,s,k) -> Optional.of(f.body()),
         (g,c,s,b) -> Optional.empty());
      assertEquals(DecisionStatus.UNKNOWN, decide(f, missingSurface).status());
   }
   @Test void unsupportedStatePropertiesAndWaterDisagreementStopTheSemanticColumn() throws Exception {
      var f = fixture();
      var state = Blocks.GRASS_BLOCK.defaultBlockState().setValue(GrassBlock.SNOWY, true);
      var unsupported = new EarthChunkGenerator.ResolvedSurfaceContext(null, null, state, true, false, false, 0, BiomeDecision.unknown());
      var kernel = f.nativeGenerator().semanticSurfaceColumnKernel((g,c,s,k) -> Optional.of(f.body()),
         (g,c,s,b) -> Optional.of(unsupported));
      assertEquals(DecisionStatus.UNKNOWN, decide(f, kernel).status());
      var wet = new EarthChunkGenerator.ResolvedSurfaceContext(sand().palette(), null, sand().top(), true, true, false, 0, sand().biome());
      kernel = f.nativeGenerator().semanticSurfaceColumnKernel((g,c,s,k) -> Optional.of(f.body()), (g,c,s,b) -> Optional.of(wet));
      assertEquals(DecisionStatus.UNKNOWN, decide(f, kernel).status());
   }
   @Test void nativeProfileAndBodyIdentityAreCheckedBeforeSurfaceSourceWork() throws Exception {
      var f = fixture(); var bodies = new AtomicInteger(); var surfaces = new AtomicInteger();
      var kernel = f.nativeGenerator().semanticSurfaceColumnKernel((g,c,s,k) -> { bodies.incrementAndGet(); return Optional.of(f.body()); },
         (g,c,s,b) -> { surfaces.incrementAndGet(); return Optional.of(sand()); });
      var wrong = new TellusGenerationContext(f.generation().worldSeed() + 1, f.generation().worldScale(), f.generation().seaLevel(),
         f.generation().minY(), f.generation().maxYExclusive(), f.generation().geometry());
      var request = TellusSampleRequest.fromContext(f.coordinate(), SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.exact(wrong.worldScale()), wrong.geometry(), wrong.worldSeed());
      assertThrows(IllegalArgumentException.class, () -> kernel.decide(wrong, f.coordinate(), TellusSample.partial(request, "profile fixture"), null));
      assertEquals(0, bodies.get()); assertEquals(0, surfaces.get());
      var otherBody = new TellusEarthColumnSnapshot(wrong, f.coordinate(), f.body().terrainY(), f.body().surface(),
         f.body().water(), f.body().biome(), f.body().strata(), List.of());
      var mismatched = f.nativeGenerator().semanticSurfaceColumnKernel((g,c,s,k) -> Optional.of(otherBody),
         (g,c,s,b) -> { surfaces.incrementAndGet(); return Optional.of(sand()); });
      assertThrows(IllegalArgumentException.class, () -> decide(f, mismatched)); assertEquals(0, surfaces.get());
   }
   @Test void terminalSamplesDoNotCallEitherSourceAndFullNullPaletteKeepsBody() throws Exception {
      var f = fixture(); var calls = new AtomicInteger();
      var kernel = f.nativeGenerator().semanticSurfaceColumnKernel((g,c,s,k) -> { calls.incrementAndGet(); return Optional.of(f.body()); },
         (g,c,s,b) -> { calls.incrementAndGet(); return Optional.of(sand()); });
      assertEquals(DecisionStatus.UNKNOWN, kernel.decide(f.generation(), f.coordinate(), TellusSample.unknown(f.sample().request(), "unknown"), null).status());
      var queued = TellusSampleRequest.fromContext(f.coordinate(), SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.nonBlocking(f.generation().worldScale()), f.generation().geometry(), f.generation().worldSeed());
      assertEquals(DecisionStatus.QUEUED, kernel.decide(f.generation(), f.coordinate(),
         TellusSample.queued(queued, "surface-fixture", "r38", "queued"), null).status());
      assertEquals(0, calls.get());
      var none = new EarthChunkGenerator.ResolvedSurfaceContext(null, null, null, false, false, false, 0, f.body().biome());
      var noSurface = f.nativeGenerator().semanticSurfaceColumnKernel((g,c,s,k) -> Optional.of(f.body()), (g,c,s,b) -> Optional.of(none));
      var result = decide(f, noSurface);
      assertEquals(f.body().strata(), result.strata()); assertEquals(f.body().surface(), result.surface());
   }
}
