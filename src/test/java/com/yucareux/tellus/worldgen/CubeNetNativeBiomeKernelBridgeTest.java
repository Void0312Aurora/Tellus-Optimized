package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.world.data.ocean.OisstOceanClimateSource;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual native biome source delegation at column-top engine quart coordinates; not GIS or a vertical biome palette. */
class CubeNetNativeBiomeKernelBridgeTest {
   static final double SCALE = 1000.0;
   static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   static final CubeNetLayout LAYOUT = CubeNetLayout.standard(MODEL.config().faceBlocks());
   static final SphereChunkSampler.SurfaceData TERRAIN = new SphereChunkSampler.SurfaceData(10.1, 30);
   @TempDir static Path directory;
   static String oldGame, oldConfig;
   static ClassLoader oldLoader;
   static HolderLookup.Provider lookup;
   static HolderLookup.RegistryLookup<Biome> biomes;
   @BeforeAll static void bootstrap() {
      oldGame = System.getProperty("tellus.gameDir"); oldConfig = System.getProperty("tellus.configDir");
      oldLoader = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(CubeNetNativeBiomeKernelBridgeTest.class.getClassLoader());
      System.setProperty("tellus.gameDir", directory.toString()); System.setProperty("tellus.configDir", directory.resolve("config").toString());
      SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
      lookup = MinecraftTestRegistries.create(); biomes = lookup.lookupOrThrow(Registries.BIOME);
   }
   @AfterAll static void restore() {
      if (oldGame == null) System.clearProperty("tellus.gameDir"); else System.setProperty("tellus.gameDir", oldGame);
      if (oldConfig == null) System.clearProperty("tellus.configDir"); else System.setProperty("tellus.configDir", oldConfig);
      Thread.currentThread().setContextClassLoader(oldLoader);
   }
   record Case(String name, SphereChunkSampler.SurfaceData nativeSurface, String koppen,
               OisstOceanClimateSource.Sample ocean, CubeNetBiomeSource.WaterData mappedWater) { }
   static List<Case> cases() {
      var dry = CubeNetBiomeSource.WaterData.dry();
      return List.of(
         new Case("forest-Cfa", new SphereChunkSampler.SurfaceData(100, 10), "Cfa", null, dry),
         new Case("forest-Cfb", new SphereChunkSampler.SurfaceData(100, 10), "Cfb", null, dry),
         new Case("grass", new SphereChunkSampler.SurfaceData(100, 30), "Cfb", null, dry),
         new Case("mangrove", new SphereChunkSampler.SurfaceData(100, 95), "Af", null, dry),
         new Case("inland", new SphereChunkSampler.SurfaceData(100, 80, 110, SphereChunkSampler.SurfaceData.WaterKind.INLAND, null), "Cfb", null, dry),
         new Case("mapped-inland", new SphereChunkSampler.SurfaceData(100, 80), "Cfb", null, new CubeNetBiomeSource.WaterData(true, false)),
         new Case("ocean-warm", new SphereChunkSampler.SurfaceData(-10, 80, 0), null, new OisstOceanClimateSource.Sample(25, 0, true), dry),
         new Case("ocean-deep-warm", new SphereChunkSampler.SurfaceData(-300, 80, 0), null, new OisstOceanClimateSource.Sample(25, 0, true), dry),
         new Case("ocean-deep-cold", new SphereChunkSampler.SurfaceData(-300, 80, 0), null, new OisstOceanClimateSource.Sample(4, 0, true), dry),
         new Case("ocean-ice", new SphereChunkSampler.SurfaceData(-300, 80, 0), null, new OisstOceanClimateSource.Sample(-3, 0.25, true), dry),
         new Case("snow", new SphereChunkSampler.SurfaceData(100, 70), "Cfb", null, dry));
   }
   static CubeNetBiomeSource.Settings settings(boolean modified) {
      if (!modified) return CubeNetBiomeSource.Settings.DEFAULT;
      var encoded = CubeNetBiomeSource.Settings.CODEC.encodeStart(JsonOps.INSTANCE, CubeNetBiomeSource.Settings.DEFAULT)
         .result().orElseThrow().getAsJsonObject();
      encoded.addProperty("enable_water", false); encoded.addProperty("random_biomes", true);
      encoded.addProperty("terrestrial_height_scale", 1.7); encoded.addProperty("oceanic_height_scale", 2.5);
      return CubeNetBiomeSource.Settings.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();
   }
   static CubeNetBiomeSource source(Case scenario, CubeNetBiomeSource.Settings settings, List<SphereWorldModel.GeoPoint> nativeReads) {
      return new CubeNetBiomeSource(biomes, SCALE, 5, SphereSingularityPolicy.DEFAULT_CONFIG,
         new FixedBiomeSource(biomes.getOrThrow(Biomes.PLAINS)),
         (point, resolution) -> { nativeReads.add(point); return scenario.nativeSurface(); },
         (point, resolution, water) -> new CubeNetBiomeSource.ClimateData(scenario.koppen(), scenario.ocean()),
         (point, scale) -> scenario.mappedWater(), settings);
   }
   static TellusCoordinateContext coordinate(CubeNetLayout.Face face, int offset) {
      return TellusCoordinateContextFactory.fromCubeNetCanonical(MODEL, LAYOUT,
         new CubeNetLayout.FacePoint(face, LAYOUT.faceSize() * 0.25 + offset + 0.25,
            LAYOUT.faceSize() * 0.375 + offset + 0.25));
   }
   static TellusGenerationContext generation(double scale) {
      return new TellusGenerationContext(42L, scale, 64, -64, 320,
         GeometryIdentity.forProvider("tellus:cube-net", "native-biome-bridge-r49", "cube-net-v1", "spherical-noise-v1", scale));
   }
   static TellusSample sample(TellusGenerationContext g, TellusCoordinateContext c, SphereChunkSampler.SurfaceData data, boolean nonBlocking) {
      var sampler = new SphereChunkSampler(MODEL, (point, resolution) -> data);
      var provider = c.owner() instanceof SphericalOwnerView ? TellusSphericalSampleProvider.forSphereFaceProduction(sampler)
         : TellusSphericalSampleProvider.forCubeNetProduction(sampler);
      return provider.sample(TellusSampleRequest.fromContext(c, SamplingPurpose.TERRAIN_COLUMN,
         nonBlocking ? SamplingResolution.nonBlocking(g.worldScale()) : SamplingResolution.exact(g.worldScale()), g.geometry(), g.worldSeed()));
   }
   static String id(Holder<Biome> biome) { return biome.unwrapKey().orElseThrow().location().toString(); }

   @Test void nativeQuartBiomeParityAcrossSixFacesCasesAndSettingsPreservesAllTerrainWrites() {
      int columns = 0, cells = 0, nonQuart = 0, negative = 0;
      Set<String> ids = new HashSet<>();
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         for (boolean modified : new boolean[]{false, true}) for (var scenario : cases()) {
            var settings = settings(modified); List<SphereWorldModel.GeoPoint> reads = new ArrayList<>();
            var nativeSource = source(scenario, settings, reads);
            var kernel = nativeSource.semanticColumnKernel();
            var oldKernel = new TellusSphericalDecisionKernel(new TellusSphericalDecisionKernel.Settings(
               settings.enableWater(), settings.terrestrialHeightScale(), settings.oceanicHeightScale()));
            for (var face : CubeNetLayout.Face.values()) for (int offset : new int[]{1, 3}) {
               var g = generation(SCALE); var c = coordinate(face, offset); var sample = sample(g, c, TERRAIN, false);
               var old = oldKernel.decide(g, c, sample); assertFalse(old.biome().known());
               var owner = (CubeNetCanonicalOwnerView)c.owner();
               int x = (int)Math.floor(owner.planarPoint().x()), z = (int)Math.floor(owner.planarPoint().z());
               int qx = QuartPos.fromBlock(x), qz = QuartPos.fromBlock(z), qy = QuartPos.fromBlock(old.terrainY().orElseThrow());
               String expected = id(nativeSource.getNoiseBiome(qx, qy, qz, Climate.empty()));
               assertFalse(reads.isEmpty()); var expectedPoint = reads.get(0); reads.clear();
               var decision = TellusColumnDecisionEngine.withSphericalProductionRules(kernel).decide(g, c, sample);
               assertEquals(DecisionStatus.AVAILABLE, decision.status()); assertTrue(decision.biome().known());
               assertEquals(expected, decision.biome().id(), scenario.name()); ids.add(expected);
               assertFalse(reads.isEmpty(), "Bridge must query the real native source, not relabel provider SurfaceData");
               assertEquals(expectedPoint, reads.get(0));
               assertNotEquals(c.geo(), reads.get(0), "Engine quart anchoring must not silently become provider-point sampling");
               reads.clear();
               assertEquals(old.terrainY(), decision.terrainY()); assertEquals(old.surface(), decision.surface());
               assertEquals(old.water(), decision.water()); assertEquals(old.strata(), decision.strata());
               var writer = new TellusColumnMaterializer(); var write = WriteContext.canonical(c, g, "native-biome-bridge");
               var before = writer.materializeCells(write, old); var after = writer.materializeCells(write, decision);
               assertEquals(WriteStatus.WRITTEN, after.status()); assertEquals(before.mappedWrites(), after.mappedWrites());
               cells += after.mappedWrites().size(); columns++;
               if ((x & 3) != 0 || (z & 3) != 0) nonQuart++;
               if (x < 0 || z < 0) negative++;
            }
         }
      }
      assertEquals(264, columns); assertEquals(34320, cells); assertTrue(nonQuart > 0); assertTrue(negative > 0);
      assertTrue(ids.contains("minecraft:river")); assertTrue(ids.contains("minecraft:mangrove_swamp"));
      assertTrue(ids.contains("minecraft:warm_ocean")); assertTrue(ids.contains("minecraft:deep_frozen_ocean"));
      System.out.println("CUBE_NATIVE_BIOME_KERNEL columns=" + columns + " terrainCells=" + cells + " biomeMismatches=0 terrainMismatches=0");
   }

   @Test void settingsScalesAndWaterControlKernelWithoutChangingNativeSource() {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         var scenario = cases().get(0); var settings = settings(true);
         var source = source(scenario, settings, new ArrayList<>()); var c = coordinate(CubeNetLayout.Face.POS_X, 1); var g = generation(SCALE);
         var kernel = source.semanticColumnKernel();
         var wet = new SphereChunkSampler.SurfaceData(10010, 80, 12400, SphereChunkSampler.SurfaceData.WaterKind.INLAND, null);
         var positive = kernel.decide(g, c, sample(g, c, wet, false));
         assertEquals(82, positive.terrainY().orElseThrow()); assertEquals(WaterDecision.Kind.NONE, positive.water().kind());
         var negative = kernel.decide(g, c, sample(g, c, new SphereChunkSampler.SurfaceData(-10010, 30), false));
         assertEquals(38, negative.terrainY().orElseThrow());
         assertTrue(positive.biome().known()); assertTrue(negative.biome().known());
      }
   }

   @Test void nonblockingAndRejectedNativeProfilesDoNotReadSource() {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         List<SphereWorldModel.GeoPoint> reads = new ArrayList<>(); var source = source(cases().get(0), settings(false), reads);
         var kernel = source.semanticColumnKernel(); var c = coordinate(CubeNetLayout.Face.POS_X, 1); var g = generation(SCALE);
         assertEquals(DecisionStatus.QUEUED, kernel.decide(g, c, sample(g, c, TERRAIN, true)).status());
         var wrong = generation(SCALE * 2);
         assertThrows(IllegalArgumentException.class, () -> kernel.decide(wrong, c, sample(wrong, c, TERRAIN, false)));
         var sphere = TellusCoordinateContextFactory.fromSphericalBlock(MODEL, ((CubeNetCanonicalOwnerView)c.owner()).block());
         assertThrows(IllegalArgumentException.class, () -> kernel.decide(g, sphere, sample(g, sphere, TERRAIN, false)));
         var owner = (CubeNetCanonicalOwnerView)c.owner();
         for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new CubeNetLayout.PlanarPoint(invalid, owner.planarPoint().z()));
         }
         for (double x : new double[]{(double)Integer.MAX_VALUE + 1, (double)Integer.MIN_VALUE - 1, owner.planarPoint().x() + LAYOUT.faceSize() * 2.0}) {
            var planar = new CubeNetLayout.PlanarPoint(x, owner.planarPoint().z());
            var alteredOwner = new CubeNetCanonicalOwnerView(owner.block(), owner.chunk(), owner.facePoint(), planar);
            var altered = ImmutableTellusCoordinateContext.cubeNetView(c.geo(), c.earth(), c.sphericalNoise(), alteredOwner, owner.facePoint(), planar);
            assertThrows(IllegalArgumentException.class, () -> kernel.decide(g, altered, sample(g, altered, TERRAIN, false)));
         }
         source.beginStructureBiomeQueries();
         try { assertThrows(IllegalStateException.class, () -> kernel.decide(g, c, sample(g, c, TERRAIN, false))); }
         finally { source.endStructureBiomeQueries(); }
         assertTrue(reads.isEmpty(), "Terminal/profile rejection must precede native biome sampling");
      }
   }

   @Test void unkeyedNativeHolderRemainsUnknownWithoutFabricatedRegistryId() throws Exception {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         var source = source(cases().get(4), settings(false), new ArrayList<>());
         var river = CubeNetBiomeSource.class.getDeclaredField("river"); river.setAccessible(true);
         river.set(source, Holder.direct(biomes.getOrThrow(Biomes.RIVER).value()));
         var c = coordinate(CubeNetLayout.Face.POS_X, 1); var g = generation(SCALE);
         var decision = source.semanticColumnKernel().decide(g, c, sample(g, c, TERRAIN, false));
         assertEquals(DecisionStatus.AVAILABLE, decision.status()); assertEquals(BiomeDecision.unknown(), decision.biome());
      }
   }

   @SuppressWarnings("unchecked")
   static net.minecraft.resources.RegistryOps<com.google.gson.JsonElement> serializationOps() throws Exception {
      try {
         // 1.21's RegistrySetBuilder provider overrides this to retain its UniversalOwner.
         var factory = HolderLookup.Provider.class.getMethod("createSerializationContext", com.mojang.serialization.DynamicOps.class);
         return (net.minecraft.resources.RegistryOps<com.google.gson.JsonElement>)factory.invoke(lookup, JsonOps.INSTANCE);
      } catch (NoSuchMethodException legacy1201) {
         return net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, lookup);
      }
   }

   @Test void creatingExplicitKernelDoesNotMutateSourceCodecSettings() throws Exception {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         var source = source(cases().get(0), settings(true), new ArrayList<>());
         var ops = serializationOps();
         var encoded = CubeNetBiomeSource.CODEC.codec().encodeStart(ops, source);
         var before = encoded.result().orElseThrow(() -> new AssertionError(encoded.error().map(Object::toString).orElse("missing codec output")));
         assertNotNull(source.semanticColumnKernel());
         var after = CubeNetBiomeSource.CODEC.codec().encodeStart(ops, source).result().orElseThrow();
         assertEquals(before, after);
         var restored = CubeNetBiomeSource.CODEC.codec().parse(ops, after).result().orElseThrow();
         assertEquals(source.settings(), restored.settings());
         assertEquals(after, CubeNetBiomeSource.CODEC.codec().encodeStart(ops, restored).result().orElseThrow());
      }
   }
}
