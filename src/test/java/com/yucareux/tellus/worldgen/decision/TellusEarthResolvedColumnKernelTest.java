package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import com.yucareux.tellus.worldgen.write.PartialWritePolicy;
import com.yucareux.tellus.worldgen.write.SemanticColumnCell;
import com.yucareux.tellus.worldgen.write.TellusColumnMaterializer;
import com.yucareux.tellus.worldgen.write.WriteContext;
import com.yucareux.tellus.worldgen.write.WriteResult;
import com.yucareux.tellus.worldgen.write.WriteStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Base-query/resolved-input bridge tests; no decorated native generation is asserted here. */
class TellusEarthResolvedColumnKernelTest {
   private static final double SCALE = 1000.0;
   private static final long SEED = 42L;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   private static final GeometryIdentity GEOMETRY = geometry(SCALE, "earth-resolved-v1");
   private static final TellusCoordinateContext COORDINATE = earth(46.0, 12.0);
   private static final TellusGenerationContext GENERATION =
      new TellusGenerationContext(SEED, SCALE, 2, -8, 24, GEOMETRY);
   private static final BiomeDecision BIOME = new BiomeDecision("minecraft:stony_peaks", true);
   private static final TellusColumnMaterializer MATERIALIZER = new TellusColumnMaterializer();

   @Test void rulesMatchFrozenNativeBaseQueryLoopsForShellAndClassicColumns() {
      for (TellusEarthColumnRules.Input input : boundedBaseInputs()) {
         SemanticMaterial[] expected = frozenNativeBaseQuery(input);
         for (int y = input.minY(); y < input.maxYExclusive(); y++) {
            assertEquals(expected[y - input.minY()], TellusEarthColumnRules.materialAt(input, y),
               input + " at Y=" + y);
         }
         assertEquals(SemanticMaterial.AIR, TellusEarthColumnRules.materialAt(input, input.minY() - 1));
         assertEquals(SemanticMaterial.AIR, TellusEarthColumnRules.materialAt(input, input.maxYExclusive()));
         StrataDecision strata = TellusEarthColumnRules.strata(input);
         assertTrue(strata.known());
         assertEquals(frozenRuns(input, expected), strata.layers(), input.toString());
      }
   }

   @Test void shellRleExplicitlyIncludesKnownAirBelowSupportAndUsesResolvedDeepslateBoundary() {
      var input = input(5, OptionalInt.of(8), true, -8, 3, 4);
      assertEquals(2, input.supportBottomY());
      TellusEarthColumnSnapshot snapshot = base(input).orElseThrow();
      assertTrue(snapshot.completeLayers());
      assertEquals(List.of(
         new StrataDecision.Layer(-8, 1, SemanticMaterial.AIR),
         new StrataDecision.Layer(2, 2, SemanticMaterial.BEDROCK),
         new StrataDecision.Layer(3, 3, SemanticMaterial.DEEPSLATE),
         new StrataDecision.Layer(4, 5, SemanticMaterial.STONE)), snapshot.strata().layers());
      var result = materialize(GENERATION, decide(snapshot, sample(1.0)));
      assertEquals(WriteStatus.WRITTEN, result.status());
      assertEquals(SemanticMaterial.AIR, at(result, -8));
      assertEquals(SemanticMaterial.AIR, at(result, 1));
      assertEquals(SemanticMaterial.BEDROCK, at(result, 2));
      assertEquals(SemanticMaterial.DEEPSLATE, at(result, 3));
      assertEquals(SemanticMaterial.STONE, at(result, 4));
      assertEquals(SemanticMaterial.WATER, at(result, 8));
   }

   @Test void representableBaseSnapshotsMaterializeExactlyTheFrozenNativeBaseBodyAndWater() {
      for (TellusEarthColumnRules.Input input : boundedBaseInputs()) {
         Optional<TellusEarthColumnSnapshot> snapshot = base(input);
         if (snapshot.isEmpty()) {
            assertFalse(input.terrainShell());
            assertTrue(input.generatorBedrockY() > input.terrainY());
            assertTrue(input.generatorBedrockY() < input.maxYExclusive());
            assertTrue(input.waterY().isEmpty() || input.generatorBedrockY() > input.waterY().getAsInt());
            continue;
         }
         ColumnDecision decision = decide(snapshot.orElseThrow(), sample(-9.0e12));
         assertEquals(DecisionStatus.AVAILABLE, decision.status(), input.toString());
         assertEquals(input.terrainY(), decision.terrainY().orElseThrow());
         assertEquals(BIOME, decision.biome());
         var result = materialize(GENERATION, decision);
         assertEquals(WriteStatus.WRITTEN, result.status(), input.toString());
         SemanticMaterial[] expected = frozenNativeBaseQuery(input);
         int top = Math.max(input.terrainY(), input.waterY().orElse(input.terrainY()));
         List<SemanticColumnCell> expectedCells = new ArrayList<>();
         for (int y = input.minY(); y <= top; y++) {
            expectedCells.add(new SemanticColumnCell(y, expected[y - input.minY()]));
         }
         assertEquals(expectedCells, result.mappedWrites(), input.toString());
      }
   }

   @Test void classicGeneratorBedrockAboveTerrainIsOverwrittenOnlyByNativeWaterPriority() {
      var wet = input(-2, OptionalInt.of(6), false, 3, 99, 99);
      var dry = input(-2, OptionalInt.empty(), false, 3, 99, 99);
      var waterBelowBedrock = input(-2, OptionalInt.of(2), false, 3, 99, 99);
      assertEquals(SemanticMaterial.WATER, frozenNativeBaseQuery(wet)[3 - wet.minY()]);
      assertEquals(SemanticMaterial.WATER, TellusEarthColumnRules.materialAt(wet, 3));
      assertEquals(SemanticMaterial.BEDROCK, frozenNativeBaseQuery(dry)[3 - dry.minY()]);
      assertEquals(SemanticMaterial.BEDROCK, TellusEarthColumnRules.materialAt(dry, 3));
      assertTrue(base(dry).isEmpty(), "a dry block above reported terrain has no ColumnDecision representation");
      assertTrue(base(waterBelowBedrock).isEmpty());
      TellusEarthColumnSnapshot snapshot = base(wet).orElseThrow();
      assertEquals(-2, snapshot.terrainY(), "the bridge must not move terrain up to the generator bedrock");
      assertEquals(WaterDecision.Kind.INLAND, snapshot.water().kind());
      assertEquals(SemanticMaterial.WATER, at(materialize(GENERATION, decide(snapshot, sample(0))), 3));
      assertTrue(base(input(-2, OptionalInt.empty(), false, 24, 1, 0)).isPresent(),
         "out-of-accessor generator bedrock is ignored by the old native query");
   }

   @Test void resolverReceivesOriginalInputsAndDirectSnapshotFactsIgnoreRawMetresScaling() {
      TellusEarthColumnSnapshot snapshot = geologicalSnapshot();
      for (double rawMetres : new double[]{-9.0e12, 9.0e12}) {
         TellusSample sample = sample(rawMetres);
         AtomicInteger calls = new AtomicInteger();
         var kernel = TellusEarthColumnDecisionKernel.resolvedColumns((generation, coordinate, supplied, koppen) -> {
            calls.incrementAndGet();
            assertSame(GENERATION, generation);
            assertSame(COORDINATE, coordinate);
            assertSame(sample, supplied);
            assertEquals("Cfb", koppen);
            return Optional.of(snapshot);
         });
         ColumnDecision decision = TellusColumnDecisionEngine.withEarthProductionRules(kernel)
            .decide(GENERATION, COORDINATE, sample, "Cfb");
         assertEquals(1, calls.get());
         assertSnapshotFacts(snapshot, decision);
         assertEquals(DecisionStatus.AVAILABLE, decision.status());
         assertEquals(6, decision.terrainY().orElseThrow());
         assertEquals(OptionalInt.of(10), decision.water().surfaceY());
      }
   }

   @Test void suppliedGeologicalLayersAndSurfaceAreNotReplacedWithStone() {
      TellusEarthColumnSnapshot snapshot = geologicalSnapshot();
      var result = materialize(GENERATION, decide(snapshot, sample(0)));
      assertEquals(WriteStatus.WRITTEN, result.status());
      assertEquals(19, result.mappedWrites().size());
      assertEquals(SemanticMaterial.BEDROCK, at(result, -8));
      assertEquals(SemanticMaterial.BASALT, at(result, -7));
      assertEquals(SemanticMaterial.BASALT, at(result, -1));
      assertEquals(SemanticMaterial.CALCITE, at(result, 0));
      assertEquals(SemanticMaterial.CALCITE, at(result, 1));
      assertEquals(SemanticMaterial.TUFF, at(result, 2));
      assertEquals(SemanticMaterial.TUFF, at(result, 3));
      assertEquals(SemanticMaterial.PACKED_MUD, at(result, 4));
      assertEquals(SemanticMaterial.PACKED_MUD, at(result, 5));
      assertEquals(SemanticMaterial.GRAVEL, at(result, 6));
      assertEquals(SemanticMaterial.WATER, at(result, 7));
      assertEquals(SemanticMaterial.WATER, at(result, 10));
      assertTrue(result.mappedWrites().stream().noneMatch(cell -> cell.material() == SemanticMaterial.STONE));
   }

   @Test void partialSampleWithUnavailableRawElevationCanStillCarryResolvedKnownLayers() {
      TellusSample partial = TellusSample.partial(request(COORDINATE, GEOMETRY, SEED), "incomplete source snapshot");
      TellusEarthColumnSnapshot snapshot = geologicalSnapshot();
      AtomicInteger calls = new AtomicInteger();
      var kernel = TellusEarthColumnDecisionKernel.resolvedColumns((generation, coordinate, sample, koppen) -> {
         calls.incrementAndGet();
         assertSame(partial, sample);
         return Optional.of(snapshot);
      });
      ColumnDecision decision = kernel.decide(GENERATION, COORDINATE, partial, null);
      assertEquals(1, calls.get(), "resolved input must not require raw elevation completeness");
      assertEquals(DecisionStatus.PARTIAL, decision.status());
      assertSnapshotFacts(snapshot, decision);
      assertEquals(WriteStatus.PARTIAL, materialize(GENERATION, decision).status());
      var rejected = MATERIALIZER.materializeCells(WriteContext.canonical(COORDINATE, GENERATION,
         "resolved-reject-partial", PartialWritePolicy.NO_WRITE), decision);
      assertEquals(WriteStatus.NO_WRITE_PARTIAL, rejected.status());
      assertTrue(rejected.mappedWrites().isEmpty());
   }

   @Test void unknownSnapshotComponentsRemainPartialAndNeverReceiveStoneFallbacks() {
      TellusEarthColumnSnapshot full = geologicalSnapshot();
      List<TellusEarthColumnSnapshot> partials = List.of(
         snapshot(new SurfaceDecision(SemanticMaterial.UNKNOWN, SemanticMaterial.PACKED_MUD, 2),
            full.water(), full.strata()),
         snapshot(new SurfaceDecision(SemanticMaterial.GRAVEL, SemanticMaterial.UNKNOWN, 2),
            full.water(), full.strata()),
         snapshot(full.surface(), WaterDecision.unknown(), full.strata()),
         snapshot(full.surface(), full.water(), StrataDecision.unknown()),
         snapshot(full.surface(), full.water(), new StrataDecision(List.of(
            new StrataDecision.Layer(-8, 1, SemanticMaterial.UNKNOWN),
            new StrataDecision.Layer(2, 6, SemanticMaterial.TUFF)), true)));
      for (TellusEarthColumnSnapshot snapshot : partials) {
         assertFalse(snapshot.completeLayers());
         ColumnDecision decision = decide(snapshot, sample(12345));
         assertEquals(DecisionStatus.PARTIAL, decision.status());
         assertSnapshotFacts(snapshot, decision);
         var result = materialize(GENERATION, decision);
         assertEquals(WriteStatus.PARTIAL, result.status());
         assertTrue(result.mappedWrites().stream().noneMatch(cell -> cell.material() == SemanticMaterial.STONE
            || cell.material() == SemanticMaterial.UNKNOWN));
      }
      var unknownBody = partials.get(3);
      var bodyWrites = materialize(GENERATION, decide(unknownBody, sample(0))).mappedWrites();
      assertTrue(bodyWrites.stream().noneMatch(cell -> cell.y() < 4));
      var unknownTop = materialize(GENERATION, decide(partials.get(0), sample(0)));
      assertTrue(unknownTop.mappedWrites().stream().noneMatch(cell -> cell.y() == 6));
      var unknownFiller = materialize(GENERATION, decide(partials.get(1), sample(0)));
      assertTrue(unknownFiller.mappedWrites().stream().noneMatch(cell -> cell.y() == 4 || cell.y() == 5));
   }

   @Test void emptyResolverSnapshotReturnsUnknownRatherThanLegacyStone() {
      AtomicInteger calls = new AtomicInteger();
      var kernel = TellusEarthColumnDecisionKernel.resolvedColumns((generation, coordinate, sample, koppen) -> {
         calls.incrementAndGet();
         return Optional.empty();
      });
      for (TellusSample sample : List.of(sample(12345),
         TellusSample.partial(request(COORDINATE, GEOMETRY, SEED), "partial"))) {
         ColumnDecision decision = TellusColumnDecisionEngine.withEarthProductionRules(kernel)
            .decide(GENERATION, COORDINATE, sample, null);
         assertEquals(DecisionStatus.UNKNOWN, decision.status());
         assertTrue(decision.terrainY().isEmpty());
         assertEquals(SemanticMaterial.UNKNOWN, decision.surface().top());
         assertFalse(decision.strata().known());
         var result = materialize(GENERATION, decision);
         assertEquals(WriteStatus.NO_WRITE_UNKNOWN, result.status());
         assertTrue(result.mappedWrites().isEmpty());
      }
      assertEquals(2, calls.get());
   }

   @Test void unknownAndQueuedAreTerminalBeforeResolvedSourceWork() {
      AtomicInteger calls = new AtomicInteger();
      var kernel = TellusEarthColumnDecisionKernel.resolvedColumns((generation, coordinate, sample, koppen) -> {
         calls.incrementAndGet();
         fail("terminal samples must not consult resolved source");
         return Optional.empty();
      });
      TellusSampleRequest request = TellusSampleRequest.fromContext(COORDINATE, SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.nonBlocking(SCALE), GEOMETRY, SEED);
      for (TellusSample sample : List.of(TellusSample.unknown(request, "absent"),
         TellusSample.queued(request, "fixture", "r1", "pending"))) {
         DecisionStatus expected = sample.status() == SampleStatus.QUEUED ? DecisionStatus.QUEUED : DecisionStatus.UNKNOWN;
         ColumnDecision direct = kernel.decide(GENERATION, COORDINATE, sample, "Cfb");
         ColumnDecision throughEngine = TellusColumnDecisionEngine.withEarthProductionRules(kernel)
            .decide(GENERATION, COORDINATE, sample, "Cfb");
         assertEquals(expected, direct.status());
         assertEquals(expected, throughEngine.status());
         var result = materialize(GENERATION, throughEngine);
         assertEquals(expected == DecisionStatus.QUEUED ? WriteStatus.NO_WRITE_QUEUED : WriteStatus.NO_WRITE_UNKNOWN,
            result.status());
         assertTrue(result.mappedWrites().isEmpty());
      }
      assertEquals(0, calls.get());
   }

   @Test void sampleSeedGeometryCoordinateAndNonEarthOwnerAreRejectedBeforeResolver() {
      AtomicInteger calls = new AtomicInteger();
      var kernel = TellusEarthColumnDecisionKernel.resolvedColumns((generation, coordinate, sample, koppen) -> {
         calls.incrementAndGet();
         return Optional.of(geologicalSnapshot());
      });
      var engine = TellusColumnDecisionEngine.withEarthProductionRules(kernel);
      TellusCoordinateContext otherEarth = earth(45.0, 13.0);
      GeometryIdentity otherGeometry = geometry(SCALE, "different-revision");
      for (TellusSample sample : List.of(
         sample(request(COORDINATE, GEOMETRY, SEED + 1), 0),
         sample(request(COORDINATE, otherGeometry, SEED), 0),
         sample(request(otherEarth, GEOMETRY, SEED), 0),
         TellusSample.unknown(request(COORDINATE, GEOMETRY, SEED + 1), "wrong seed remains invalid"))) {
         assertThrows(IllegalArgumentException.class, () -> kernel.decide(GENERATION, COORDINATE, sample, null));
         assertThrows(IllegalArgumentException.class, () -> engine.decide(GENERATION, COORDINATE, sample, null));
      }
      TellusCoordinateContext sphere = TellusCoordinateContextFactory.fromSphericalBlock(MODEL,
         new SphereWorldModel.SphereBlockAddress(0, 1, 1));
      TellusSample sphericalSample = sample(request(sphere, GEOMETRY, SEED), 0);
      assertThrows(IllegalArgumentException.class, () -> kernel.decide(GENERATION, sphere, sphericalSample, null));
      assertThrows(IllegalArgumentException.class, () -> engine.decide(GENERATION, sphere, sphericalSample, null));
      assertEquals(0, calls.get());
   }

   @Test void resolvedSnapshotMustMatchEntireGenerationIdentityAndExactCoordinate() {
      TellusEarthColumnSnapshot full = geologicalSnapshot();
      for (TellusGenerationContext wrong : List.of(
         new TellusGenerationContext(SEED + 1, SCALE, 2, -8, 24, GEOMETRY),
         new TellusGenerationContext(SEED, SCALE, 3, -8, 24, GEOMETRY),
         new TellusGenerationContext(SEED, SCALE, 2, -9, 24, GEOMETRY),
         new TellusGenerationContext(SEED, SCALE, 2, -8, 25, GEOMETRY),
         new TellusGenerationContext(SEED, SCALE, 2, -8, 24, geometry(SCALE, "other")),
         new TellusGenerationContext(SEED, 2000.0, 2, -8, 24, geometry(2000.0, "earth-resolved-v1")))) {
         TellusEarthColumnSnapshot mismatch = new TellusEarthColumnSnapshot(wrong, COORDINATE, full.terrainY(),
            full.surface(), full.water(), full.biome(), full.strata(), full.diagnostics());
         assertSnapshotRejected(mismatch);
      }
      assertSnapshotRejected(new TellusEarthColumnSnapshot(GENERATION, earth(45.0, 13.0), full.terrainY(),
         full.surface(), full.water(), full.biome(), full.strata(), full.diagnostics()));
   }

   @Test void outOfRangeResolvedHeightsAreRejectedInsteadOfClamped() {
      TellusEarthColumnSnapshot full = geologicalSnapshot();
      for (int terrain : new int[]{GENERATION.minY() - 1, GENERATION.maxYExclusive()}) {
         assertThrows(IllegalArgumentException.class, () -> new TellusEarthColumnSnapshot(GENERATION, COORDINATE,
            terrain, full.surface(), full.water(), BIOME, full.strata(), List.of()));
      }
      for (int water : new int[]{GENERATION.minY() - 1, GENERATION.maxYExclusive()}) {
         assertThrows(IllegalArgumentException.class, () -> new TellusEarthColumnSnapshot(GENERATION, COORDINATE,
            6, full.surface(), new WaterDecision(WaterDecision.Kind.OCEAN, OptionalInt.of(water)),
            BIOME, full.strata(), List.of()));
      }
      for (int terrain : new int[]{GENERATION.minY(), GENERATION.maxYExclusive() - 1}) {
         var snapshot = new TellusEarthColumnSnapshot(GENERATION, COORDINATE, terrain,
            new SurfaceDecision(SemanticMaterial.TUFF, SemanticMaterial.TUFF, 0), WaterDecision.dry(), BIOME,
            new StrataDecision(List.of(new StrataDecision.Layer(GENERATION.minY(), terrain, SemanticMaterial.TUFF)), true),
            List.of());
         assertEquals(terrain, decide(snapshot, sample(9.0e12)).terrainY().orElseThrow());
      }
   }

   @Test void baseFactoryRejectsMismatchedRangeAndAmbiguousWaterKind() {
      var input = input(6, OptionalInt.of(10), true, -8, 5, 3);
      var wrongRange = new TellusEarthColumnRules.Input(-9, 24, 6, OptionalInt.of(10), true, -8, 5, 3);
      assertThrows(IllegalArgumentException.class, () -> TellusEarthColumnSnapshot.baseColumn(
         GENERATION, COORDINATE, wrongRange, WaterDecision.Kind.INLAND, BIOME));
      for (WaterDecision.Kind kind : List.of(WaterDecision.Kind.NONE, WaterDecision.Kind.UNKNOWN)) {
         assertThrows(IllegalArgumentException.class, () -> TellusEarthColumnSnapshot.baseColumn(
            GENERATION, COORDINATE, input, kind, BIOME));
      }
      for (WaterDecision.Kind kind : List.of(WaterDecision.Kind.INLAND, WaterDecision.Kind.OCEAN, WaterDecision.Kind.UNKNOWN)) {
         assertThrows(IllegalArgumentException.class, () -> TellusEarthColumnSnapshot.baseColumn(
            GENERATION, COORDINATE, input(6, OptionalInt.empty(), true, -8, 5, 3), kind, BIOME));
      }
      assertEquals(WaterDecision.Kind.OCEAN, TellusEarthColumnSnapshot.baseColumn(
         GENERATION, COORDINATE, input, WaterDecision.Kind.OCEAN, BIOME).orElseThrow().water().kind());
   }

   @Test void columnBudgetsAreCheckedBeforeExpansionAndUseLongRangeArithmetic() {
      assertDoesNotThrow(() -> new TellusEarthColumnRules.Input(-8, 1_048_568, -6,
         OptionalInt.empty(), true, -8, 1, 0));
      assertThrows(IllegalArgumentException.class, () -> new TellusEarthColumnRules.Input(-8, 1_048_569, -6,
         OptionalInt.empty(), true, -8, 1, 0));
      assertThrows(IllegalArgumentException.class, () -> new TellusEarthColumnRules.Input(
         Integer.MIN_VALUE, Integer.MAX_VALUE, 0, OptionalInt.empty(), false, 0, 0, 0));
      for (TellusGenerationContext generation : List.of(
         new TellusGenerationContext(SEED, SCALE, 2, -8, 1_048_569, GEOMETRY),
         new TellusGenerationContext(SEED, SCALE, 2, Integer.MIN_VALUE, Integer.MAX_VALUE, GEOMETRY))) {
         int terrain = generation.minY() + 2;
         // Only three cells could expand even if the guard regresses: this test remains bounded.
         var snapshot = new TellusEarthColumnSnapshot(generation, COORDINATE, terrain,
            new SurfaceDecision(SemanticMaterial.TUFF, SemanticMaterial.TUFF, 0), WaterDecision.dry(), BIOME,
            new StrataDecision(List.of(new StrataDecision.Layer(generation.minY(), terrain, SemanticMaterial.TUFF)), true),
            List.of());
         AtomicInteger calls = new AtomicInteger();
         var kernel = TellusEarthColumnDecisionKernel.resolvedColumns((g, c, s, k) -> {
            calls.incrementAndGet();
            return Optional.of(snapshot);
         });
         ColumnDecision guarded = kernel.decide(generation, COORDINATE, sample(0), null);
         assertEquals(DecisionStatus.UNKNOWN, guarded.status());
         assertEquals(0, calls.get(), "oversized ranges must stop before source resolution");
         assertTrue(guarded.diagnostics().stream().anyMatch(message -> message.contains("budget")));
         // Check the materializer guard independently; the kernel's UNKNOWN result is already terminal.
         ColumnDecision decision = new ColumnDecision(COORDINATE, DecisionStatus.AVAILABLE,
            OptionalInt.of(terrain), snapshot.surface(), snapshot.water(), snapshot.biome(), snapshot.strata(), List.of());
         var result = materialize(generation, decision);
         assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
         assertTrue(result.mappedWrites().isEmpty());
         assertTrue(result.diagnostics().stream().anyMatch(message -> message.contains("budget")));
      }
   }

   @Test void explicitlySupportedSingleCellBedrockTopAtMinimumIsWritten() {
      for (boolean shell : new boolean[]{true, false}) {
         var input = input(GENERATION.minY(), OptionalInt.empty(), shell, GENERATION.minY(), 0, 0);
         TellusEarthColumnSnapshot snapshot = base(input).orElseThrow();
         assertEquals(SemanticMaterial.BEDROCK, snapshot.surface().top());
         assertEquals(0, snapshot.surface().depth());
         assertEquals(List.of(new StrataDecision.Layer(-8, -8, SemanticMaterial.BEDROCK)), snapshot.strata().layers());
         var result = materialize(GENERATION, decide(snapshot, sample(9.0e12)));
         assertEquals(WriteStatus.WRITTEN, result.status());
         assertEquals(List.of(new SemanticColumnCell(-8, SemanticMaterial.BEDROCK)), result.mappedWrites());
      }
      var supported = new TellusEarthColumnSnapshot(GENERATION, COORDINATE, -8,
         new SurfaceDecision(SemanticMaterial.BEDROCK, SemanticMaterial.UNKNOWN, 0), WaterDecision.dry(), BIOME,
         new StrataDecision(List.of(new StrataDecision.Layer(-8, -8, SemanticMaterial.BEDROCK)), true), List.of());
      assertTrue(supported.completeLayers(), "a zero-depth filler is unused");
      ColumnDecision decision = decide(supported, sample(0));
      assertEquals(DecisionStatus.AVAILABLE, decision.status());
      var result = materialize(GENERATION, decision);
      assertEquals(WriteStatus.WRITTEN, result.status());
      assertEquals(List.of(new SemanticColumnCell(-8, SemanticMaterial.BEDROCK)), result.mappedWrites());
   }

   @Test void minimumBedrockExceptionStillRejectsOtherSurfaceCollisions() {
      StrataDecision bedrock = new StrataDecision(List.of(
         new StrataDecision.Layer(-8, -8, SemanticMaterial.BEDROCK)), true);
      for (SurfaceDecision surface : List.of(
         new SurfaceDecision(SemanticMaterial.STONE, SemanticMaterial.STONE, 0),
         new SurfaceDecision(SemanticMaterial.BEDROCK, SemanticMaterial.BEDROCK, 1))) {
         var snapshot = new TellusEarthColumnSnapshot(GENERATION, COORDINATE, -8, surface,
            WaterDecision.dry(), BIOME, bedrock, List.of());
         var result = materialize(GENERATION, decide(snapshot, sample(0)));
         assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
         assertTrue(result.mappedWrites().isEmpty());
      }
   }

   private static List<TellusEarthColumnRules.Input> boundedBaseInputs() {
      return List.of(
         input(5, OptionalInt.empty(), true, -8, 3, 4),
         input(5, OptionalInt.of(8), true, 20, 3, 4),
         input(5, OptionalInt.of(2), true, -8, 30, 100),
         input(-3, OptionalInt.of(1), true, -8, 3, -4),
         input(2, OptionalInt.empty(), true, -8, 0, 99),
         input(-8, OptionalInt.empty(), true, -8, 0, 0),
         input(23, OptionalInt.of(23), true, -8, 2, -100),
         input(5, OptionalInt.of(8), false, -8, 0, 100),
         input(-2, OptionalInt.empty(), false, -8, 99, 100),
         input(5, OptionalInt.empty(), false, 2, 0, -100),
         input(-8, OptionalInt.empty(), false, -8, 0, 0),
         input(-2, OptionalInt.of(6), false, 3, 1, 0),
         input(-2, OptionalInt.of(2), false, 3, 1, 0),
         input(-2, OptionalInt.empty(), false, 3, 1, 0),
         input(5, OptionalInt.empty(), false, -9, 0, 0));
   }

   /**
    * Frozen EarthChunkGenerator.getBaseColumn loops, with semantic materials in place of BlockStates.
    * Inputs are already resolved heights; no Rules.materialAt/strata call is used to compute expected data.
    */
   private static SemanticMaterial[] frozenNativeBaseQuery(TellusEarthColumnRules.Input input) {
      int minY = input.minY();
      int height = input.maxYExclusive() - minY;
      SemanticMaterial[] states = new SemanticMaterial[height];
      Arrays.fill(states, SemanticMaterial.AIR);
      int surface = input.terrainY();
      int surfaceIndex = surface - minY;
      if (input.terrainShell()) {
         int supportBottomY = Math.max(minY, surface - input.undergroundDepth());
         int deepslateBoundaryY = input.deepslateBoundaryY();
         for (int y = supportBottomY; y <= surface; y++) {
            int index = y - minY;
            if (index >= 0 && index < states.length) {
               states[index] = y == supportBottomY ? SemanticMaterial.BEDROCK
                  : y < deepslateBoundaryY ? SemanticMaterial.DEEPSLATE : SemanticMaterial.STONE;
            }
         }
      } else {
         for (int i = 0; i <= surfaceIndex; i++) {
            if (i >= 0 && i < states.length) {
               int y = minY + i;
               states[i] = y < 0 ? SemanticMaterial.DEEPSLATE : SemanticMaterial.STONE;
            }
         }
         int bedrockIndex = input.generatorBedrockY() - minY;
         if (bedrockIndex >= 0 && bedrockIndex < states.length) states[bedrockIndex] = SemanticMaterial.BEDROCK;
      }
      if (input.waterY().isPresent()) {
         int waterTop = input.waterY().getAsInt();
         int waterIndex = waterTop - minY;
         for (int ix = surfaceIndex + 1; ix <= waterIndex; ix++) {
            if (ix >= 0 && ix < states.length) states[ix] = SemanticMaterial.WATER;
         }
      }
      return states;
   }

   private static List<StrataDecision.Layer> frozenRuns(TellusEarthColumnRules.Input input, SemanticMaterial[] cells) {
      List<StrataDecision.Layer> layers = new ArrayList<>();
      int start = input.minY();
      for (int y = start + 1; y <= input.terrainY(); y++) {
         if (cells[y - input.minY()] != cells[start - input.minY()]) {
            layers.add(new StrataDecision.Layer(start, y - 1, cells[start - input.minY()]));
            start = y;
         }
      }
      layers.add(new StrataDecision.Layer(start, input.terrainY(), cells[start - input.minY()]));
      return layers;
   }

   private static TellusEarthColumnRules.Input input(int terrain, OptionalInt water, boolean shell,
                                                     int bedrock, int depth, int deepslate) {
      return new TellusEarthColumnRules.Input(-8, 24, terrain, water, shell, bedrock, depth, deepslate);
   }

   private static Optional<TellusEarthColumnSnapshot> base(TellusEarthColumnRules.Input input) {
      return TellusEarthColumnSnapshot.baseColumn(GENERATION, COORDINATE, input,
         input.waterY().isPresent() ? WaterDecision.Kind.INLAND : WaterDecision.Kind.NONE, BIOME);
   }

   private static TellusEarthColumnSnapshot geologicalSnapshot() {
      return snapshot(new SurfaceDecision(SemanticMaterial.GRAVEL, SemanticMaterial.PACKED_MUD, 2),
         new WaterDecision(WaterDecision.Kind.OCEAN, OptionalInt.of(10)),
         new StrataDecision(List.of(
            new StrataDecision.Layer(-8, -8, SemanticMaterial.BEDROCK),
            new StrataDecision.Layer(-7, -1, SemanticMaterial.BASALT),
            new StrataDecision.Layer(0, 1, SemanticMaterial.CALCITE),
            new StrataDecision.Layer(2, 6, SemanticMaterial.TUFF)), true));
   }

   private static TellusEarthColumnSnapshot snapshot(SurfaceDecision surface, WaterDecision water, StrataDecision strata) {
      return new TellusEarthColumnSnapshot(GENERATION, COORDINATE, 6, surface, water, BIOME, strata,
         List.of("caller-resolved geological base; no decorated native generation"));
   }

   private static ColumnDecision decide(TellusEarthColumnSnapshot snapshot, TellusSample sample) {
      return TellusColumnDecisionEngine.withEarthProductionRules(TellusEarthColumnDecisionKernel.resolvedColumns(
         (generation, coordinate, supplied, koppen) -> Optional.of(snapshot)))
         .decide(GENERATION, COORDINATE, sample, "Cfb");
   }

   private static void assertSnapshotRejected(TellusEarthColumnSnapshot snapshot) {
      AtomicInteger calls = new AtomicInteger();
      var kernel = TellusEarthColumnDecisionKernel.resolvedColumns((generation, coordinate, sample, koppen) -> {
         calls.incrementAndGet();
         return Optional.of(snapshot);
      });
      assertThrows(IllegalArgumentException.class, () -> TellusColumnDecisionEngine.withEarthProductionRules(kernel)
         .decide(GENERATION, COORDINATE, sample(0), "Cfb"));
      assertEquals(1, calls.get(), "snapshot identity can only be checked after the resolver supplies it");
   }

   private static void assertSnapshotFacts(TellusEarthColumnSnapshot snapshot, ColumnDecision decision) {
      assertEquals(snapshot.coordinate(), decision.coordinate());
      assertEquals(snapshot.terrainY(), decision.terrainY().orElseThrow());
      assertEquals(snapshot.surface(), decision.surface());
      assertEquals(snapshot.water(), decision.water());
      assertEquals(snapshot.strata(), decision.strata());
      assertEquals(snapshot.biome(), decision.biome());
   }

   private static WriteResult<SemanticColumnCell> materialize(TellusGenerationContext generation, ColumnDecision decision) {
      return MATERIALIZER.materializeCells(WriteContext.canonical(COORDINATE, generation, "resolved-base-test"), decision);
   }

   private static SemanticMaterial at(WriteResult<SemanticColumnCell> result, int y) {
      return result.mappedWrites().stream().filter(cell -> cell.y() == y).findFirst().orElseThrow().material();
   }

   private static TellusSample sample(double rawMetres) {
      return sample(request(COORDINATE, GEOMETRY, SEED), rawMetres);
   }

   private static TellusSample sample(TellusSampleRequest request, double rawMetres) {
      return new TellusSample(request, SampleStatus.AVAILABLE,
         new TellusElevationSample(SampleStatus.AVAILABLE, rawMetres),
         new TellusCoverSample(SampleStatus.AVAILABLE, 10, 10), TellusWaterSample.dry(),
         TellusSampleProvenance.snapshot("resolved-fixture", "r1", true, "available raw source fields"));
   }

   private static TellusSampleRequest request(TellusCoordinateContext coordinate, GeometryIdentity geometry, long seed) {
      return TellusSampleRequest.fromContext(coordinate, SamplingPurpose.TERRAIN_COLUMN,
         SamplingResolution.exact(SCALE), geometry, seed);
   }

   private static GeometryIdentity geometry(double scale, String revision) {
      return GeometryIdentity.forProvider("tellus:earth", revision, "earth-projection-v1", "earth-noise-v1", scale);
   }

   private static TellusCoordinateContext earth(double latitude, double longitude) {
      var geo = new SphereWorldModel.GeoPoint(latitude, longitude, 0.0);
      return TellusCoordinateContextFactory.fromGeo(MODEL, geo,
         new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo, SCALE)));
   }
}
