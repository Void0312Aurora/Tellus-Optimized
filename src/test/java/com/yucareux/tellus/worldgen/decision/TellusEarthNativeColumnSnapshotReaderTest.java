package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.write.SemanticColumnCell;
import com.yucareux.tellus.worldgen.write.TellusColumnMaterializer;
import com.yucareux.tellus.worldgen.write.WriteContext;
import com.yucareux.tellus.worldgen.write.WriteStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** Pure exact native-column reader checks shared by both Minecraft version test source sets. */
class TellusEarthNativeColumnSnapshotReaderTest {
   private static final double SCALE = 1000.0;
   private static final GeometryIdentity GEOMETRY = GeometryIdentity.forProvider(
      "tellus:earth", "native-column-reader-test", "earth-projection-v1", "earth-noise-v1", SCALE);
   private static final SphereWorldModel.GeoPoint GEO = new SphereWorldModel.GeoPoint(46.0, 12.0, 0.0);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromGeo(
      SphereWorldModel.forWorldScale(SCALE), GEO,
      new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(GEO, SCALE)));
   private static final TellusGenerationContext GENERATION = generation(-8, 24);
   private static final BiomeDecision BIOME = new BiomeDecision("minecraft:stony_peaks", true);

   @Test void dryColumnReadsOnlyOriginalBodyAndRetainsExactIdentitiesAndRuns() {
      WaterDecision water = WaterDecision.dry();
      List<Integer> reads = new ArrayList<>();
      SemanticMaterial[] body = {
         SemanticMaterial.AIR, SemanticMaterial.AIR, SemanticMaterial.BEDROCK,
         SemanticMaterial.BASALT, SemanticMaterial.BASALT, SemanticMaterial.CALCITE,
         SemanticMaterial.AIR, SemanticMaterial.BEDROCK, SemanticMaterial.GRAVEL,
         SemanticMaterial.GRAVEL
      };
      TellusEarthColumnSnapshot snapshot = TellusEarthNativeColumnSnapshotReader.read(
         GENERATION, COORDINATE, 1, water, BIOME, y -> {
            reads.add(y);
            assertTrue(y >= -8 && y <= 1, "dry reader must not query sky");
            return body[y + 8];
         }).orElseThrow();
      assertEquals(ys(-8, 1), reads);
      assertSame(GENERATION, snapshot.generation());
      assertSame(COORDINATE, snapshot.coordinate());
      assertSame(water, snapshot.water());
      assertSame(BIOME, snapshot.biome());
      assertEquals(1, snapshot.terrainY());
      assertEquals(new SurfaceDecision(SemanticMaterial.GRAVEL, SemanticMaterial.GRAVEL, 0), snapshot.surface());
      assertTrue(snapshot.completeLayers());
      assertEquals(List.of(
         new StrataDecision.Layer(-8, -7, SemanticMaterial.AIR),
         new StrataDecision.Layer(-6, -6, SemanticMaterial.BEDROCK),
         new StrataDecision.Layer(-5, -4, SemanticMaterial.BASALT),
         new StrataDecision.Layer(-3, -3, SemanticMaterial.CALCITE),
         new StrataDecision.Layer(-2, -2, SemanticMaterial.AIR),
         new StrataDecision.Layer(-1, -1, SemanticMaterial.BEDROCK),
         new StrataDecision.Layer(0, 1, SemanticMaterial.GRAVEL)), snapshot.strata().layers());
      assertEquals(cells(-8, body), materialize(snapshot));
   }

   @Test void explicitInlandAndOceanWaterAreVerifiedOnceThroughTheirLastCellWithoutSkyReads() {
      for (WaterDecision.Kind kind : List.of(WaterDecision.Kind.INLAND, WaterDecision.Kind.OCEAN)) {
         WaterDecision water = wet(kind, 4);
         List<Integer> reads = new ArrayList<>();
         TellusEarthColumnSnapshot snapshot = TellusEarthNativeColumnSnapshotReader.read(
            GENERATION, COORDINATE, 1, water, BIOME, y -> {
               reads.add(y);
               assertTrue(y >= -8 && y <= 4, "reader must not inspect above explicit water");
               return y <= 1 ? SemanticMaterial.PACKED_MUD : SemanticMaterial.WATER;
            }).orElseThrow();
         assertSame(water, snapshot.water());
         assertEquals(ys(-8, 4), reads);
         assertEquals(List.of(new StrataDecision.Layer(-8, 1, SemanticMaterial.PACKED_MUD)),
            snapshot.strata().layers(), "above-terrain water must not extend body strata");
         List<SemanticColumnCell> expected = new ArrayList<>();
         for (int y = -8; y <= 4; y++) {
            expected.add(new SemanticColumnCell(y, y <= 1 ? SemanticMaterial.PACKED_MUD : SemanticMaterial.WATER));
         }
         assertEquals(expected, materialize(snapshot));
      }
   }

   @Test void knownAirIncludingTopAndAllAirBodyDoesNotInventSupportOrMoveTerrain() {
      for (int terrain : new int[]{-8, 1, 23}) {
         List<Integer> reads = new ArrayList<>();
         TellusEarthColumnSnapshot snapshot = TellusEarthNativeColumnSnapshotReader.read(
            GENERATION, COORDINATE, terrain, WaterDecision.dry(), BiomeDecision.unknown(), y -> {
               reads.add(y);
               return SemanticMaterial.AIR;
            }).orElseThrow();
         assertEquals(terrain, snapshot.terrainY());
         assertEquals(ys(-8, terrain), reads);
         assertEquals(new SurfaceDecision(SemanticMaterial.AIR, SemanticMaterial.AIR, 0), snapshot.surface());
         assertEquals(List.of(new StrataDecision.Layer(-8, terrain, SemanticMaterial.AIR)), snapshot.strata().layers());
         assertFalse(snapshot.biome().known(), "reader must not synthesize a biome");
         assertTrue(snapshot.completeLayers());
         assertTrue(materialize(snapshot).stream().allMatch(cell -> cell.material() == SemanticMaterial.AIR));
      }
   }

   @Test void everyKnownSemanticMaterialIsPreservedAtBottomInternalAndTopCells() {
      for (SemanticMaterial material : SemanticMaterial.values()) {
         if (material == SemanticMaterial.UNKNOWN) continue;
         TellusEarthColumnSnapshot snapshot = TellusEarthNativeColumnSnapshotReader.read(
            GENERATION, COORDINATE, -6, WaterDecision.dry(), BIOME, y -> material).orElseThrow();
         assertEquals(new SurfaceDecision(material, material, 0), snapshot.surface());
         assertEquals(List.of(new StrataDecision.Layer(-8, -6, material)), snapshot.strata().layers());
         assertEquals(cells(-8, material, material, material), materialize(snapshot));
      }
   }

   @Test void singleCellBedrockAtMinimumIsRepresentedWithoutASurfaceCollision() {
      AtomicInteger calls = new AtomicInteger();
      TellusEarthColumnSnapshot snapshot = TellusEarthNativeColumnSnapshotReader.read(
         GENERATION, COORDINATE, -8, WaterDecision.dry(), BIOME, y -> {
            assertEquals(-8, y);
            calls.incrementAndGet();
            return SemanticMaterial.BEDROCK;
         }).orElseThrow();
      assertEquals(1, calls.get());
      assertEquals(cells(-8, SemanticMaterial.BEDROCK), materialize(snapshot));
   }

   @Test void unknownBodyAtAnyPositionReturnsEmptyWithoutFallbackOrLaterReads() {
      for (int unknownY : new int[]{-8, -3, 1}) {
         List<Integer> reads = new ArrayList<>();
         assertTrue(TellusEarthNativeColumnSnapshotReader.read(
            GENERATION, COORDINATE, 1, wet(WaterDecision.Kind.INLAND, 4), BIOME, y -> {
               reads.add(y);
               return y == unknownY ? SemanticMaterial.UNKNOWN : SemanticMaterial.TUFF;
            }).isEmpty());
         assertEquals(ys(-8, unknownY), reads, "unknown body is terminal before water validation");
      }
   }

   @Test void everyNonWaterMaterialInExplicitWaterSpanIsUnrepresentableRatherThanReplaced() {
      for (SemanticMaterial invalid : SemanticMaterial.values()) {
         if (invalid == SemanticMaterial.WATER) continue;
         for (int invalidY : new int[]{2, 3, 4}) {
            List<Integer> reads = new ArrayList<>();
            assertTrue(TellusEarthNativeColumnSnapshotReader.read(
               GENERATION, COORDINATE, 1, wet(WaterDecision.Kind.OCEAN, 4), BIOME, y -> {
                  reads.add(y);
                  return y <= 1 ? SemanticMaterial.DEEPSLATE
                     : y == invalidY ? invalid : SemanticMaterial.WATER;
               }).isEmpty(), invalid + " at Y=" + invalidY);
            assertEquals(ys(-8, invalidY), reads);
         }
      }
   }

   @Test void unknownWaterStopsBeforeBodyCallbackAndIsNeverInferredFromCells() {
      AtomicInteger calls = new AtomicInteger();
      assertTrue(TellusEarthNativeColumnSnapshotReader.read(
         GENERATION, COORDINATE, 1, WaterDecision.unknown(), BIOME, y -> {
            calls.incrementAndGet();
            return SemanticMaterial.WATER;
         }).isEmpty());
      assertEquals(0, calls.get());
   }

   @Test void suppliedWetHeightAtOrBelowTerrainIsRetainedAndHasNoAboveBodyReads() {
      for (int waterY : new int[]{-8, -3, 1}) {
         WaterDecision water = wet(WaterDecision.Kind.INLAND, waterY);
         List<Integer> reads = new ArrayList<>();
         TellusEarthColumnSnapshot snapshot = TellusEarthNativeColumnSnapshotReader.read(
            GENERATION, COORDINATE, 1, water, BIOME, y -> {
               reads.add(y);
               return SemanticMaterial.SANDSTONE;
            }).orElseThrow();
         assertSame(water, snapshot.water());
         assertEquals(ys(-8, 1), reads);
         assertEquals(10, materialize(snapshot).size());
      }
   }

   @Test void invalidTerrainAndWaterRangesAreRejectedBeforeAnyCallbackEvenForUnknownWater() {
      AtomicInteger calls = new AtomicInteger();
      IntFunction<SemanticMaterial> callback = y -> {
         calls.incrementAndGet();
         return SemanticMaterial.UNKNOWN;
      };
      for (int terrain : new int[]{Integer.MIN_VALUE, -9, 24, Integer.MAX_VALUE}) {
         for (WaterDecision water : List.of(WaterDecision.dry(), WaterDecision.unknown(),
            wet(WaterDecision.Kind.OCEAN, 4))) {
            assertThrows(IllegalArgumentException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
               GENERATION, COORDINATE, terrain, water, BIOME, callback));
         }
      }
      for (int waterY : new int[]{Integer.MIN_VALUE, -9, 24, Integer.MAX_VALUE}) {
         assertThrows(IllegalArgumentException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
            GENERATION, COORDINATE, 1, wet(WaterDecision.Kind.INLAND, waterY), BIOME, callback));
      }
      assertEquals(0, calls.get());
   }

   @Test void fullGenerationBudgetIsValidatedWithLongArithmeticBeforeCallbacksEvenForTinyBodies() {
      AtomicInteger calls = new AtomicInteger();
      for (TellusGenerationContext generation : List.of(
         generation(-8, 1_048_569), generation(Integer.MIN_VALUE, Integer.MAX_VALUE))) {
         for (WaterDecision water : List.of(WaterDecision.dry(), WaterDecision.unknown(),
            wet(WaterDecision.Kind.INLAND, generation.maxYExclusive() - 1))) {
            assertThrows(IllegalArgumentException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
               generation, COORDINATE, generation.minY(), water, BIOME, y -> {
                  calls.incrementAndGet();
                  return SemanticMaterial.UNKNOWN;
               }));
         }
      }
      assertEquals(0, calls.get());
      TellusGenerationContext atLimit = generation(-8, 1_048_568);
      assertTrue(TellusEarthNativeColumnSnapshotReader.read(atLimit, COORDINATE, -8,
         WaterDecision.dry(), BIOME, y -> {
            calls.incrementAndGet();
            assertEquals(-8, y);
            return SemanticMaterial.BEDROCK;
         }).isPresent());
      assertEquals(1, calls.get(), "generation range exactly at budget is accepted without reading sky");
   }

   @Test void integerEndpointRangesUseLongIterationWithoutWrappingOrOffByOneReads() {
      for (TellusGenerationContext generation : List.of(
         generation(Integer.MIN_VALUE, Integer.MIN_VALUE + 4),
         generation(Integer.MAX_VALUE - 4, Integer.MAX_VALUE))) {
         int terrain = generation.minY() + 1;
         int waterY = generation.maxYExclusive() - 1;
         List<Integer> reads = new ArrayList<>();
         TellusEarthColumnSnapshot snapshot = TellusEarthNativeColumnSnapshotReader.read(
            generation, COORDINATE, terrain, wet(WaterDecision.Kind.OCEAN, waterY), BIOME, y -> {
               reads.add(y);
               return y <= terrain ? SemanticMaterial.BEDROCK : SemanticMaterial.WATER;
            }).orElseThrow();
         assertEquals(ys(generation.minY(), waterY), reads);
         assertEquals(List.of(new StrataDecision.Layer(generation.minY(), terrain, SemanticMaterial.BEDROCK)),
            snapshot.strata().layers());
      }
   }

   @Test void nullArgumentsAreProgrammerErrorsBeforeAnyCallback() {
      AtomicInteger calls = new AtomicInteger();
      IntFunction<SemanticMaterial> callback = y -> {
         calls.incrementAndGet();
         return SemanticMaterial.STONE;
      };
      assertThrows(NullPointerException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
         null, COORDINATE, 1, WaterDecision.dry(), BIOME, callback));
      assertThrows(NullPointerException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
         GENERATION, null, 1, WaterDecision.dry(), BIOME, callback));
      assertThrows(NullPointerException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
         GENERATION, COORDINATE, 1, null, BIOME, callback));
      assertThrows(NullPointerException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
         GENERATION, COORDINATE, 1, WaterDecision.dry(), null, callback));
      assertThrows(NullPointerException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
         GENERATION, COORDINATE, 1, WaterDecision.unknown(), BIOME, null));
      assertEquals(0, calls.get());
   }

   @Test void nullBodyAndWaterCallbackResultsAreProgrammerErrorsNotFallbacks() {
      for (int nullY : new int[]{-8, 1, 2, 4}) {
         List<Integer> reads = new ArrayList<>();
         assertThrows(NullPointerException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
            GENERATION, COORDINATE, 1, wet(WaterDecision.Kind.INLAND, 4), BIOME, y -> {
               reads.add(y);
               return y == nullY ? null : y <= 1 ? SemanticMaterial.STONE : SemanticMaterial.WATER;
            }));
         assertEquals(ys(-8, nullY), reads);
      }
   }

   @Test void callbackErrorsPropagateUnchangedFromBodyAndExplicitWater() {
      for (int failingY : new int[]{-8, 1, 2, 4}) {
         IllegalStateException failure = new IllegalStateException("native callback at " + failingY);
         List<Integer> reads = new ArrayList<>();
         assertSame(failure, assertThrows(IllegalStateException.class, () -> TellusEarthNativeColumnSnapshotReader.read(
            GENERATION, COORDINATE, 1, wet(WaterDecision.Kind.OCEAN, 4), BIOME, y -> {
               reads.add(y);
               if (y == failingY) throw failure;
               return y <= 1 ? SemanticMaterial.STONE : SemanticMaterial.WATER;
            })));
         assertEquals(ys(-8, failingY), reads);
      }
   }

   private static TellusGenerationContext generation(int minY, int maxYExclusive) {
      return new TellusGenerationContext(42L, SCALE, 2, minY, maxYExclusive, GEOMETRY);
   }

   private static WaterDecision wet(WaterDecision.Kind kind, int waterY) {
      return new WaterDecision(kind, OptionalInt.of(waterY));
   }

   private static List<Integer> ys(int from, int to) {
      List<Integer> values = new ArrayList<>();
      for (long y = from; y <= to; y++) values.add((int)y);
      return values;
   }

   private static List<SemanticColumnCell> cells(int minY, SemanticMaterial... materials) {
      List<SemanticColumnCell> cells = new ArrayList<>();
      for (int i = 0; i < materials.length; i++) cells.add(new SemanticColumnCell(minY + i, materials[i]));
      return cells;
   }

   private static List<SemanticColumnCell> materialize(TellusEarthColumnSnapshot snapshot) {
      ColumnDecision decision = new ColumnDecision(snapshot.coordinate(), DecisionStatus.AVAILABLE,
         OptionalInt.of(snapshot.terrainY()), snapshot.surface(), snapshot.water(), snapshot.biome(),
         snapshot.strata(), snapshot.diagnostics());
      var result = new TellusColumnMaterializer().materializeCells(
         WriteContext.canonical(snapshot.coordinate(), snapshot.generation(), "native-column-reader-test"), decision);
      assertEquals(WriteStatus.WRITTEN, result.status(), result.diagnostics().toString());
      return result.mappedWrites();
   }
}
