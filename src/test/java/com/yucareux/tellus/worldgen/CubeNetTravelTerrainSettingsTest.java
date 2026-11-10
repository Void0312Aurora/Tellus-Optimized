package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shared mc1201/mc1211 codec, projection and native dispatch regressions.
 * The placement probe is a controlled object, not a live world: it stops at the
 * first world access inside the selected tree implementation, before any write.
 */
class CubeNetTravelTerrainSettingsTest {
   @TempDir static Path directory;
   private static final String[] TRAVEL_JSON = {
      "custom_trees", "huge_red_mushrooms", "climate_based_built_up_terrain"
   };

   @BeforeAll static void initialize() throws Exception {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         SphereFaceNativeBiomeKernelBridgeTest.directory = directory;
         SphereFaceNativeBiomeKernelBridgeTest.bootstrap();
      }
   }

   @AfterAll static void restore() {
      SphereFaceNativeBiomeKernelBridgeTest.restore();
   }

   private static CubeNetBiomeSource.Settings decode(JsonObject json) {
      return CubeNetBiomeSource.Settings.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
   }

   private static JsonObject rawSettings(boolean customTrees, boolean red, boolean climate) {
      // Raw input, rather than encoding DEFAULT, also exercises the decoder's names.
      var json = JsonParser.parseString("""
         {"random_biomes":false,"cave_generation":false,"deep_dark":false,"buildings":false,
          "height_offset":76,"spawn_latitude":12.5,"spawn_longitude":-130.25,
          "terrestrial_height_scale":1.75,"oceanic_height_scale":2.5}
         """).getAsJsonObject();
      json.addProperty(TRAVEL_JSON[0], customTrees);
      json.addProperty(TRAVEL_JSON[1], red);
      json.addProperty(TRAVEL_JSON[2], climate);
      return json;
   }

   private static void assertTravel(CubeNetBiomeSource.Settings settings, boolean custom, boolean red, boolean climate) {
      assertEquals(custom, settings.customTrees(), "custom_trees");
      assertEquals(red, settings.hugeRedMushrooms(), "huge_red_mushrooms");
      assertEquals(climate, settings.climateBasedBuiltUpTerrain(), "climate_based_built_up_terrain");
   }

   @Test void missingFieldsUseCurrentEarthDefaultsAndDoNotDisturbOldSettings() {
      var earth = EarthGeneratorSettings.DEFAULT;
      assertTrue(earth.customTrees());
      assertFalse(earth.hugeRedMushrooms());
       assertFalse(earth.climateBasedBuiltUpTerrain());
       assertTravel(CubeNetBiomeSource.Settings.DEFAULT, true, false, false);
      assertEquals(CubeNetBiomeSource.Settings.DEFAULT, decode(new JsonObject()));
      var legacyJson = rawSettings(false, true, false);
      for (String name : TRAVEL_JSON) legacyJson.remove(name);
      var legacy = decode(legacyJson);
      assertTravel(legacy, earth.customTrees(), earth.hugeRedMushrooms(), earth.climateBasedBuiltUpTerrain());
      assertEquals(76, legacy.heightOffset());
      assertEquals(12.5, legacy.spawnLatitude());
      assertEquals(-130.25, legacy.spawnLongitude());
      assertEquals(1.75, legacy.terrestrialHeightScale());
      assertEquals(2.5, legacy.oceanicHeightScale());
   }

   @Test void actualCodecRoundTripsEveryBooleanCombinationIncludingExplicitFalseAndTrue() {
      for (boolean custom : new boolean[]{false, true}) for (boolean red : new boolean[]{false, true})
         for (boolean climate : new boolean[]{false, true}) {
            var settings = decode(rawSettings(custom, red, climate));
            assertTravel(settings, custom, red, climate);
            var encoded = CubeNetBiomeSource.Settings.CODEC.encodeStart(JsonOps.INSTANCE, settings)
               .result().orElseThrow().getAsJsonObject();
            assertEquals(settings, decode(encoded));
            // Optional fields may omit defaults; nondefaults must survive in the actual JSON.
            if (!custom) assertFalse(encoded.get(TRAVEL_JSON[0]).getAsBoolean());
            if (red) assertTrue(encoded.get(TRAVEL_JSON[1]).getAsBoolean());
             if (climate) assertTrue(encoded.get(TRAVEL_JSON[2]).getAsBoolean());
         }
   }

   @Test void retainedFortyArgumentConstructorPreservesEveryOldComponentAndDefaultsOnlyTheNewThree() throws Exception {
      var components = CubeNetBiomeSource.Settings.class.getRecordComponents();
      assertEquals(43, components.length);
      assertEquals(List.of("customTrees", "hugeRedMushrooms", "climateBasedBuiltUpTerrain"),
         Arrays.stream(components).skip(40).map(component -> component.getName()).toList());
      var input = decode(rawSettings(false, true, false));
      Class<?>[] types = Arrays.stream(components).limit(components.length - 3)
         .map(component -> component.getType()).toArray(Class<?>[]::new);
      Object[] arguments = new Object[types.length];
      for (int i = 0; i < arguments.length; i++) arguments[i] = components[i].getAccessor().invoke(input);
      var retained = CubeNetBiomeSource.Settings.class.getConstructor(types).newInstance(arguments);
      for (int i = 0; i < arguments.length; i++) {
         assertEquals(arguments[i], components[i].getAccessor().invoke(retained), components[i].getName());
      }
      assertTravel(retained, EarthGeneratorSettings.DEFAULT.customTrees(), EarthGeneratorSettings.DEFAULT.hugeRedMushrooms(),
         EarthGeneratorSettings.DEFAULT.climateBasedBuiltUpTerrain());
   }

   @Test void generatorFieldsAndTypedSettingsViewProjectTheSameDecodedValuesOnBothTargets() throws Exception {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         for (boolean custom : new boolean[]{false, true}) for (boolean red : new boolean[]{false, true})
            for (boolean climate : new boolean[]{false, true}) {
               var settings = decode(rawSettings(custom, red, climate));
               var source = CubeNetNativeBaseProviderPipelineTest.source(settings, new AtomicInteger());
               var generator = CubeNetNativeBaseProviderPipelineTest.generator(source,
                  (point, resolution) -> new SphereChunkSampler.SurfaceData(1010.1, MountainSurfaceRules.ESA_TREE_COVER), false, 42);
               assertEquals(custom, CubeNetNativeBaseProviderPipelineTest.field(generator, "cubeNetCustomTrees"));
               assertEquals(red, CubeNetNativeBaseProviderPipelineTest.field(generator, "cubeNetHugeRedMushrooms"));
               assertEquals(climate, CubeNetNativeBaseProviderPipelineTest.field(generator, "cubeNetClimateBasedBuiltUpTerrain"));
               var view = CubeNetGeneratorSettingsViewFactory.viewFor(generator);
                assertEquals("tellus:cube_net", view.generatorId());
                assertSame(generator, view.generator());
               assertEquals(settings, view.sourceSettings());
               var terrain = view.terrain().orElseThrow();
               assertEquals(custom, terrain.customTrees());
               assertEquals(red, terrain.hugeRedMushrooms());
               assertEquals(climate, terrain.climateBasedBuiltUpTerrain());
            }
      }
   }

   private static Method earthMethod(String name, Class<?>... types) throws Exception {
      var method = EarthChunkGenerator.class.getDeclaredMethod(name, types);
      method.setAccessible(true);
      return method;
   }

   @Test void builtUpCoverMatchesOriginalEarthOracleAndOtherwisePreservesVisualCover() throws Exception {
      Method oracle = earthMethod("climateBasedBuiltUpCoverClass", Holder.class);
      List<Holder<Biome>> biomes = new ArrayList<>();
      biomes.add(null);
      SphereFaceNativeBiomeKernelBridgeTest.biomes.listElements().forEach(biomes::add);
      Set<Integer> observed = new HashSet<>();
      for (var biome : biomes) {
         int expected = (int)oracle.invoke(null, biome);
         observed.add(expected);
         for (int visual : new int[]{MountainSurfaceRules.ESA_BUILT, MountainSurfaceRules.ESA_CROPLAND, MountainSurfaceRules.ESA_WETLAND}) {
            assertEquals(expected, CubeNetTravelTerrainRules.surfaceCover(MountainSurfaceRules.ESA_BUILT, visual, biome, true));
            assertEquals(visual, CubeNetTravelTerrainRules.surfaceCover(MountainSurfaceRules.ESA_BUILT, visual, biome, false));
            for (int raw : new int[]{MountainSurfaceRules.ESA_TREE_COVER, MountainSurfaceRules.ESA_GRASSLAND, MountainSurfaceRules.ESA_WATER}) {
               assertEquals(visual, CubeNetTravelTerrainRules.surfaceCover(raw, visual, biome, true));
               assertEquals(visual, CubeNetTravelTerrainRules.surfaceCover(raw, visual, biome, false));
            }
         }
      }
      assertTrue(observed.contains(MountainSurfaceRules.ESA_BARE));
      assertTrue(observed.contains(MountainSurfaceRules.ESA_TREE_COVER));
      assertTrue(observed.contains(MountainSurfaceRules.ESA_GRASSLAND));
   }

   /** Independent Earth hash: explicitly retain 32-bit overflow before widening. */
   private static TellusProceduralTreeGenerator.TreeAnchor earthAnchor(int cellX, int cellZ, long worldSeed) {
      int overflowedX = cellX * 3129871;
      long hash = (long)overflowedX ^ cellZ * 116129781L;
      hash = hash * hash * 42317861L + hash * 11L;
      long seed = (hash >> 16) ^ worldSeed;
      Random random = new Random(seed);
      return new TellusProceduralTreeGenerator.TreeAnchor(cellX * 5 + random.nextInt(5), cellZ * 5 + random.nextInt(5), seed);
   }

   @Test void nativeFiveBlockGridMatchesEarthHashIncludingSignedOverflowAndCustomAnchorsStayUnchanged() {
      assertEquals(5, CubeNetTravelTerrainRules.treeCellSize(false));
      assertEquals(TellusProceduralTreeGenerator.PLACEMENT_CELL_SIZE, CubeNetTravelTerrainRules.treeCellSize(true));
      int[] coordinates = {0, 1, -1, 686, -687, 123456, -234567, Integer.MIN_VALUE, Integer.MAX_VALUE};
      for (int x : coordinates) for (int z : coordinates) for (long seed : new long[]{0, 42, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
         assertEquals(earthAnchor(x, z, seed), CubeNetTravelTerrainRules.treeAnchor(x, z, seed, false), "native cell=" + x + "," + z);
         assertEquals(TellusProceduralTreeGenerator.anchorForCell(x, z, seed), CubeNetTravelTerrainRules.treeAnchor(x, z, seed, true),
            "procedural cell=" + x + "," + z);
      }
      // This fixture explicitly distinguishes accidental long multiplication from Earth's int multiplication.
      assertNotEquals((long)(123456 * 3129871), 123456L * 3129871L);
   }

   @SuppressWarnings("unchecked")
   @Test void mushroomFilteringMatchesOriginalEarthListsIncludingCompositeNativeRoots() throws Exception {
      Method oracle = earthMethod("treeFeaturesForBiome", Holder.class, boolean.class);
      int changed = 0, compositeRoots = 0, biomes = 0;
      for (var biome : SphereFaceNativeBiomeKernelBridgeTest.biomes.listElements().toList()) {
         List<WorldgenApi.TreeFeatureHandle> enabled = CubeNetTravelTerrainRules.treeFeatures(biome, true);
         List<WorldgenApi.TreeFeatureHandle> disabled = CubeNetTravelTerrainRules.treeFeatures(biome, false);
         for (boolean red : new boolean[]{false, true}) {
            var expected = (List<WorldgenApi.TreeFeatureHandle>)oracle.invoke(null, biome, red);
            var actual = red ? enabled : disabled;
            assertEquals(expected, actual, biome.unwrapKey().orElseThrow().location() + " hugeRedMushrooms=" + red);
         }
         // A native random-selector root is valid: only its nested configured features need be trees.
         compositeRoots += (int)enabled.stream().filter(feature -> !feature.isTree()).count();
         if (!enabled.equals(disabled)) changed++;
         assertFalse(disabled.stream().anyMatch(WorldgenApi.TreeFeatureHandle::isHugeRedMushroom));
         biomes++;
      }
      assertTrue(biomes > 20, "Use actual vanilla biome/feature registries");
      assertTrue(compositeRoots > 0, "Exercise native composite selectors, not only direct tree roots");
      assertTrue(changed > 0, "Exercise red-mushroom-bearing native feature graphs");
   }

   private static final class PlacementBoundary extends RuntimeException {
      final boolean procedural;
      PlacementBoundary(boolean procedural) { this.procedural = procedural; }
   }

   @SuppressWarnings("unchecked")
   @Test void actualPrivatePlacementDispatchesNativeAndProceduralAtTheExpectedCanonicalAnchor() throws Exception {
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         for (boolean custom : new boolean[]{false, true}) {
            AtomicInteger samples = new AtomicInteger();
            var source = CubeNetNativeBaseProviderPipelineTest.source(decode(rawSettings(custom, false, true)), new AtomicInteger());
            var generator = CubeNetNativeBaseProviderPipelineTest.generator(source, (point, resolution) -> {
               assertEquals(CubeNetNativeBaseProviderPipelineTest.SCALE, resolution);
               assertTrue(Double.isFinite(point.latitude()) && Double.isFinite(point.longitude()));
               samples.incrementAndGet();
               return new SphereChunkSampler.SurfaceData(1010.1, MountainSurfaceRules.ESA_TREE_COVER);
            }, false, 42);
            var coordinate = CubeNetNativeBaseProviderPipelineTest.coordinate(generator, CubeNetLayout.Face.NEG_X, 1, 2);
            var owner = (com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView)coordinate.owner();
            var planar = generator.adapter().layout().toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.NEG_X,
               owner.block().blockX() + 0.5, owner.block().blockZ() + 0.5));
            var position = new ChunkPos(Math.floorDiv((int)Math.floor(planar.x()), 16), Math.floorDiv((int)Math.floor(planar.z()), 16));
            var chunk = SphereFaceNativeBiomeKernelBridgeTest.chunk(position);
            int groundY = ((SphereTerrainMaterializer)CubeNetNativeBaseProviderPipelineTest.field(generator, "materializer")).height(1010.1);
            List<BlockPos> biomeQueries = new ArrayList<>();
            WorldGenLevel controlledWorld = (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
               new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                  // Observe the implementation actually entered, not a flag or helper result.
                  // Stop at its first world access: no partial native/procedural write simulation.
                  for (var frame : Thread.currentThread().getStackTrace()) {
                     if (!frame.getMethodName().equals("place")) continue;
                     if (frame.getClassName().equals(TellusProceduralTreeGenerator.class.getName())) throw new PlacementBoundary(true);
                     if (frame.getClassName().equals(WorldgenApi.TreeFeatureHandle.class.getName())) throw new PlacementBoundary(false);
                  }
                  return switch (method.getName()) {
                     case "getBlockState" -> ((BlockPos)args[0]).getY() == groundY
                        ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                     case "getBiome" -> {
                        biomeQueries.add(((BlockPos)args[0]).immutable());
                        yield SphereFaceNativeBiomeKernelBridgeTest.biomes.getOrThrow(Biomes.FOREST);
                     }
                     case "toString" -> "controlled-tree-dispatch-object-not-live-world";
                     case "hashCode" -> System.identityHashCode(proxy);
                     case "equals" -> proxy == args[0];
                     default -> throw new AssertionError("Unexpected controlled-world call: " + method);
                  };
               });
            var reached = assertThrows(PlacementBoundary.class, () -> CubeNetNativeBaseProviderPipelineTest.invoke(generator,
               "placeCubeNetTrees", new Class<?>[]{WorldGenLevel.class, net.minecraft.world.level.chunk.ChunkAccess.class}, controlledWorld, chunk));
            assertEquals(custom, reached.procedural, "The private production branch must select the requested implementation");
            assertEquals(1, biomeQueries.size());
            assertTrue(samples.get() > 0, "Placement must use the injected GeoPoint terrain sampler");
            var address = ((java.util.Optional<SphereWorldModel.SphereChunkAddress>)CubeNetNativeBaseProviderPipelineTest.invoke(generator,
               "address", new Class<?>[]{ChunkPos.class}, position)).orElseThrow();
            int cellSize = custom ? TellusProceduralTreeGenerator.PLACEMENT_CELL_SIZE : 5;
            BlockPos expected = null;
            outer: for (int x = Math.floorDiv((int)address.blockX(), cellSize) - 1;
               x <= Math.floorDiv((int)address.blockX() + 15, cellSize) + 1; x++) {
               for (int z = Math.floorDiv((int)address.blockZ(), cellSize) - 1;
                  z <= Math.floorDiv((int)address.blockZ() + 15, cellSize) + 1; z++) {
                  long seed = 42L ^ (long)address.face() * 0x9E3779B97F4A7C15L;
                  var anchor = custom ? TellusProceduralTreeGenerator.anchorForCell(x, z, seed) : earthAnchor(x, z, seed);
                  if (anchor.worldX() < address.blockX() || anchor.worldX() > address.blockX() + 15
                     || anchor.worldZ() < address.blockZ() || anchor.worldZ() > address.blockZ() + 15) continue;
                  var mapped = generator.adapter().layout().toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.of(address.face()),
                     anchor.worldX() + 0.5, anchor.worldZ() + 0.5));
                  int worldX = (int)Math.floor(mapped.x()), worldZ = (int)Math.floor(mapped.z());
                  if (Math.floorDiv(worldX, 16) != position.x || Math.floorDiv(worldZ, 16) != position.z) continue;
                  expected = new BlockPos(worldX, groundY + 1, worldZ);
                  break outer;
               }
            }
            assertNotNull(expected, "Fixture must contain an admitted canonical tree anchor");
            assertEquals(expected, biomeQueries.get(0), "Face-salted anchor, clipping, planar ownership and above-ground position");
         }
      }
   }
}
