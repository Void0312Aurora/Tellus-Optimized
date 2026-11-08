package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.worldgen.decision.TellusEarthSurfaceColumnRules.Cell;
import com.yucareux.tellus.worldgen.decision.TellusEarthSurfaceColumnRules.Mode;
import com.yucareux.tellus.worldgen.decision.TellusEarthSurfaceColumnRules.Plan;
import com.yucareux.tellus.worldgen.decision.TellusEarthSurfaceColumnRules.Span;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.write.SemanticColumnCell;
import com.yucareux.tellus.worldgen.write.TellusColumnMaterializer;
import com.yucareux.tellus.worldgen.write.WriteContext;
import com.yucareux.tellus.worldgen.write.WriteResult;
import com.yucareux.tellus.worldgen.write.WriteStatus;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Frozen native surface loops and the exact resolved-patch bridge; no native source reads. */
class TellusEarthSurfaceColumnRulesTest {
   private static final double SCALE = 1000.0;
   private static final GeometryIdentity GEOMETRY = GeometryIdentity.forProvider(
      "tellus:earth", "surface-column-test", "earth-projection-v1", "earth-noise-v1", SCALE);
   private static final SphereWorldModel.GeoPoint GEO = new SphereWorldModel.GeoPoint(-46.0, -12.0, 0.0);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromGeo(
      SphereWorldModel.forWorldScale(SCALE), GEO,
      new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(GEO, SCALE)));
   private static final TellusGenerationContext GENERATION =
      new TellusGenerationContext(42L, SCALE, 2, -8, 24, GEOMETRY);
   private static final BiomeDecision BIOME = new BiomeDecision("minecraft:stony_peaks", true);
   private static final BiomeDecision RESOLVED_BIOME = new BiomeDecision("minecraft:beach", true);

   @Test void allBranchFlagsRetainNativePriorityAtTheBadlandsBoundary() {
      // All 32 flag combinations, only the two relief values around the native threshold.
      for (int flags = 0; flags < 32; flags++) {
         boolean thin = (flags & 1) != 0;
         boolean palette = (flags & 2) != 0;
         boolean underwater = (flags & 4) != 0;
         boolean badlands = (flags & 8) != 0;
         boolean strata = (flags & 16) != 0;
         for (int relief : new int[]{2, 3}) {
            Mode expected;
            // Frozen applySurface/applyThinShellSurface order, independent of select().
            if (!palette) expected = thin ? Mode.TOP_ONLY : Mode.NONE;
            else if (!underwater && badlands && relief >= 3) expected = Mode.BADLANDS;
            else if (strata && !underwater) expected = Mode.STRATA;
            else expected = Mode.STANDARD;
            assertEquals(expected, TellusEarthSurfaceColumnRules.select(
               thin, palette, underwater, badlands, relief, strata), "flags=" + flags + ", relief=" + relief);
         }
      }
   }

   @Test void bottomClippingFreezesFullEmptyAndThinShellNativeRanges() {
      // minY, surface, palette depth, shell flag, underground depth, literal native bottom.
      int[][] cases = {
         {-8, -3, 4, 0, 0, -6}, {-8, -3, 4, 1, 0, -3},
         {-8, -3, 4, 1, 2, -4}, {-8, -3, 4, 1, 99, -6},
         {-8, -8, 8, 0, 0, -7}, {-8, -8, 8, 1, 0, -8},
         {-8, -7, 99, 0, 0, -7}, {-8, 5, 0, 0, 0, 6},
         {-8, 5, 0, 1, 2, 5}, {-8, 5, 1, 0, 0, 5},
         {-8, 5, 99, 1, 3, 3}, {-8, 5, 99, 1, 99, -7}
      };
      for (int[] c : cases) {
         int actual = TellusEarthSurfaceColumnRules.bottomY(c[0], c[1], c[2], c[3] != 0, c[4]);
         assertEquals(c[5], actual, java.util.Arrays.toString(c));
         assertEquals(frozenBottom(c[0], c[1], c[2], c[3] != 0, c[4]), actual);
      }
      assertThrows(IllegalArgumentException.class,
         () -> TellusEarthSurfaceColumnRules.bottomY(-8, -3, -1, false, 0));
      assertThrows(IllegalArgumentException.class,
         () -> TellusEarthSurfaceColumnRules.bottomY(-8, -3, 4, true, -1));
   }

   @Test void bottomArithmeticRetainsTheOldIntOverflowWhileSpanCountingUsesLong() {
      for (int[] c : new int[][]{
         {Integer.MIN_VALUE, Integer.MIN_VALUE, 1, 0},
         {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
         {Integer.MAX_VALUE, Integer.MAX_VALUE, 2, 0},
         {-8, Integer.MAX_VALUE, 0, Integer.MAX_VALUE}}) {
         for (boolean thin : new boolean[]{false, true}) {
            assertEquals(frozenBottom(c[0], c[1], c[2], thin, c[3]),
               TellusEarthSurfaceColumnRules.bottomY(c[0], c[1], c[2], thin, c[3]));
         }
      }
      assertEquals(1_048_576, new Span(1_048_575, 0).cellCount());
      assertEquals(0, new Span(Integer.MIN_VALUE, Integer.MAX_VALUE).cellCount());
      assertThrows(IllegalArgumentException.class, () -> new Span(1_048_576, 0));
      assertThrows(IllegalArgumentException.class, () -> new Span(Integer.MAX_VALUE, Integer.MIN_VALUE));
   }

   @Test void standardPlansMatchFrozenDescendingLoopsIncludingSandAndSingleCellTopPriority() {
      List<Span> spans = List.of(new Span(-3, -7), new Span(-3, -3), new Span(-8, -7),
         new Span(4, 1), new Span(Integer.MIN_VALUE + 2, Integer.MIN_VALUE));
      SemanticMaterial[][] materials = {
         {SemanticMaterial.SAND, SemanticMaterial.SAND, SemanticMaterial.SANDSTONE},
         {SemanticMaterial.RED_SAND, SemanticMaterial.RED_SAND, SemanticMaterial.RED_SANDSTONE},
         {SemanticMaterial.GRASS, SemanticMaterial.DIRT, SemanticMaterial.DIRT}
      };
      for (Span span : spans) {
         for (SemanticMaterial[] m : materials) {
            Plan<SemanticMaterial> plan = TellusEarthSurfaceColumnRules.standard(span, m[0], m[1], m[2]);
            assertEquals(Mode.STANDARD, plan.mode());
            assertEquals(span, plan.span());
            assertEquals(m[0], plan.top());
            assertEquals(m[1], plan.shallowFiller());
            assertEquals(m[2], plan.bottomFiller());
            assertPlan(frozenStandard(span, m[0], m[1], m[2]), plan);
         }
      }
      assertThrows(IllegalArgumentException.class,
         () -> TellusEarthSurfaceColumnRules.standard(new Span(-3, -7), "top", "shallow", "deep").materialAt(-8));
      assertThrows(IllegalArgumentException.class,
         () -> TellusEarthSurfaceColumnRules.standard(new Span(-3, -7), "top", "shallow", "deep").materialAt(-2));
   }

   @Test void layeredPlansResolveOnlyBelowTopInDescendingOrderAndNeverExpandEmptySpans() {
      for (Mode mode : List.of(Mode.STRATA, Mode.BADLANDS)) {
         List<Integer> resolved = new ArrayList<>();
         Plan<String> plan = TellusEarthSurfaceColumnRules.layered(mode, new Span(-3, -6), "final-top", y -> {
            resolved.add(y);
            return "band/" + y;
         });
         assertEquals(mode, plan.mode());
         assertEquals("final-top", plan.materialAt(-3));
         assertTrue(resolved.isEmpty(), "the final top must bypass the underlying resolver");
         List<Cell<String>> expected = List.of(new Cell<>(-3, "final-top"), new Cell<>(-4, "band/-4"),
            new Cell<>(-5, "band/-5"), new Cell<>(-6, "band/-6"));
         assertEquals(expected, plan.cells());
         assertEquals(List.of(-4, -5, -6), resolved);
         resolved.clear();
         List<Cell<String>> emitted = new ArrayList<>();
         plan.forEach((y, material) -> emitted.add(new Cell<>(y, material)));
         assertEquals(expected, emitted);
         assertEquals(List.of(-4, -5, -6), resolved);
         for (Span span : List.of(new Span(-3, -3), new Span(-8, -7))) {
            Plan<String> clipped = TellusEarthSurfaceColumnRules.layered(mode, span, "top", y -> {
               fail("a single top or empty span has no below-top resolver calls");
               return "unexpected";
            });
            assertPlan(span.cellCount() == 0 ? List.of() : List.of(new Cell<>(-3, "top")), clipped);
         }
      }
      for (Mode mode : List.of(Mode.NONE, Mode.TOP_ONLY, Mode.STANDARD)) {
         assertThrows(IllegalArgumentException.class,
            () -> TellusEarthSurfaceColumnRules.layered(mode, new Span(0, 0), "top", y -> "below"));
      }
   }

   @Test void topOnlyPlansVisitExactlyOneCellEvenAtIntegerEndpoints() {
      for (int y : new int[]{Integer.MIN_VALUE, -3, Integer.MAX_VALUE}) {
         Plan<String> plan = TellusEarthSurfaceColumnRules.topOnly(y, "resolved-top");
         assertEquals(Mode.TOP_ONLY, plan.mode());
         assertEquals(new Span(y, y), plan.span());
         assertEquals("resolved-top", plan.top());
         assertEquals("resolved-top", plan.materialAt(y));
         assertPlan(List.of(new Cell<>(y, "resolved-top")), plan);
      }
   }

   @Test void resolvedSandPatchReplacesExactBodyRunsAndPreservesKnownAirWaterAndIdentity() {
      TellusEarthColumnSnapshot base = shellBase();
      Plan<SemanticMaterial> patch = TellusEarthSurfaceColumnRules.standard(new Span(5, 3),
         SemanticMaterial.SAND, SemanticMaterial.SAND, SemanticMaterial.SANDSTONE);
      TellusEarthColumnSnapshot resolved = base.withResolvedSurface(patch.cells(), RESOLVED_BIOME).orElseThrow();
      assertSame(base.generation(), resolved.generation());
      assertSame(base.coordinate(), resolved.coordinate());
      assertSame(base.water(), resolved.water());
      assertEquals(base.terrainY(), resolved.terrainY());
      assertEquals(RESOLVED_BIOME, resolved.biome());
      assertEquals(new SurfaceDecision(SemanticMaterial.SAND, SemanticMaterial.SAND, 0), resolved.surface());
      assertTrue(resolved.completeLayers());
      assertEquals(List.of(
         new StrataDecision.Layer(-8, 1, SemanticMaterial.AIR),
         new StrataDecision.Layer(2, 2, SemanticMaterial.BEDROCK),
         new StrataDecision.Layer(3, 3, SemanticMaterial.SANDSTONE),
         new StrataDecision.Layer(4, 5, SemanticMaterial.SAND)), resolved.strata().layers());
      List<SemanticColumnCell> expected = new ArrayList<>();
      for (int y = -8; y <= 8; y++) {
         SemanticMaterial material = y <= 1 ? SemanticMaterial.AIR
            : y == 2 ? SemanticMaterial.BEDROCK : y == 3 ? SemanticMaterial.SANDSTONE
            : y <= 5 ? SemanticMaterial.SAND : SemanticMaterial.WATER;
         expected.add(new SemanticColumnCell(y, material));
      }
      assertEquals(expected, materialize(resolved).mappedWrites());
      assertEquals(SemanticMaterial.DEEPSLATE, base.strata().layers().get(2).material(),
         "overlaying the final patch must leave the immutable base intact");
   }

   @Test void sparsePatchUsesTheExistingFinalTopAndCoalescesAdjacentEqualRuns() {
      TellusEarthColumnSnapshot shell = shellBase();
      TellusEarthColumnSnapshot base = snapshot(new SurfaceDecision(SemanticMaterial.GRAVEL, SemanticMaterial.GRAVEL, 0),
         shell.water(), shell.strata());
      TellusEarthColumnSnapshot resolved = base.withResolvedSurface(List.of(
         new Cell<>(4, SemanticMaterial.DEEPSLATE)), RESOLVED_BIOME).orElseThrow();
      assertEquals(SemanticMaterial.GRAVEL, resolved.surface().top());
      assertEquals(0, resolved.surface().depth());
      assertEquals(List.of(new StrataDecision.Layer(-8, 1, SemanticMaterial.AIR),
         new StrataDecision.Layer(2, 2, SemanticMaterial.BEDROCK),
         new StrataDecision.Layer(3, 4, SemanticMaterial.DEEPSLATE),
         new StrataDecision.Layer(5, 5, SemanticMaterial.GRAVEL)), resolved.strata().layers());
      assertEquals(SemanticMaterial.GRAVEL, materialAt(materialize(resolved), 5));
   }

   @Test void knownAirInTheFinalPatchIsWrittenIncludingAtTheFinalTop() {
      TellusEarthColumnSnapshot resolved = shellBase().withResolvedSurface(List.of(
         new Cell<>(5, SemanticMaterial.AIR), new Cell<>(3, SemanticMaterial.AIR)), RESOLVED_BIOME).orElseThrow();
      assertEquals(new SurfaceDecision(SemanticMaterial.AIR, SemanticMaterial.AIR, 0), resolved.surface());
      WriteResult<SemanticColumnCell> result = materialize(resolved);
      assertEquals(SemanticMaterial.AIR, materialAt(result, 3));
      assertEquals(SemanticMaterial.AIR, materialAt(result, 5));
      assertEquals(SemanticMaterial.WATER, materialAt(result, 6));
      assertEquals(SemanticMaterial.WATER, materialAt(result, 8));
      assertTrue(result.mappedWrites().stream().noneMatch(cell -> cell.y() > 8), "no sky writes");
   }

   @Test void explicitFinalNativePatchMayReplaceMinYBedrockRatherThanApplyingGenericFiller() {
      var input = new TellusEarthColumnRules.Input(-8, 24, -8, OptionalInt.of(-5), true, -8, 0, 0);
      TellusEarthColumnSnapshot base = TellusEarthColumnSnapshot.baseColumn(
         GENERATION, COORDINATE, input, WaterDecision.Kind.INLAND, BIOME).orElseThrow();
      assertEquals(SemanticMaterial.BEDROCK, base.surface().top());
      TellusEarthColumnSnapshot resolved = base.withResolvedSurface(List.of(
         new Cell<>(-8, SemanticMaterial.GRAVEL)), RESOLVED_BIOME).orElseThrow();
      assertEquals(List.of(new StrataDecision.Layer(-8, -8, SemanticMaterial.GRAVEL)), resolved.strata().layers());
      assertEquals(new SurfaceDecision(SemanticMaterial.GRAVEL, SemanticMaterial.GRAVEL, 0), resolved.surface());
      assertEquals(List.of(new SemanticColumnCell(-8, SemanticMaterial.GRAVEL),
         new SemanticColumnCell(-7, SemanticMaterial.WATER), new SemanticColumnCell(-6, SemanticMaterial.WATER),
         new SemanticColumnCell(-5, SemanticMaterial.WATER)), materialize(resolved).mappedWrites());
   }

   @Test void emptyPatchChangesOnlyBiome() {
      TellusEarthColumnSnapshot base = shellBase();
      TellusEarthColumnSnapshot resolved = base.withResolvedSurface(List.of(), RESOLVED_BIOME).orElseThrow();
      assertEquals(new TellusEarthColumnSnapshot(base.generation(), base.coordinate(), base.terrainY(),
         base.surface(), base.water(), RESOLVED_BIOME, base.strata(), base.diagnostics()), resolved);
      assertEquals(materialize(base).mappedWrites(), materialize(resolved).mappedWrites());
   }

   @Test void unknownOrIncompleteBaseAndUnknownPatchNeverReceiveFallbackMaterials() {
      TellusEarthColumnSnapshot base = shellBase();
      List<TellusEarthColumnSnapshot> incomplete = List.of(
         snapshot(new SurfaceDecision(SemanticMaterial.STONE, SemanticMaterial.DIRT, 1), base.water(), base.strata()),
         snapshot(SurfaceDecision.unknown(), base.water(), base.strata()),
         snapshot(base.surface(), WaterDecision.unknown(), base.strata()),
         snapshot(base.surface(), base.water(), StrataDecision.unknown()),
         snapshot(base.surface(), base.water(), new StrataDecision(List.of(
            new StrataDecision.Layer(-8, 5, SemanticMaterial.UNKNOWN)), true)),
         snapshot(base.surface(), base.water(), new StrataDecision(List.of(
            new StrataDecision.Layer(-8, 1, SemanticMaterial.AIR),
            new StrataDecision.Layer(3, 5, SemanticMaterial.STONE)), true)),
         snapshot(base.surface(), base.water(), new StrataDecision(List.of(), true)));
      for (TellusEarthColumnSnapshot snapshot : incomplete) {
         assertTrue(snapshot.withResolvedSurface(List.of(new Cell<>(5, SemanticMaterial.SAND)), RESOLVED_BIOME).isEmpty());
         assertTrue(snapshot.withResolvedSurface(List.of(), RESOLVED_BIOME).isEmpty());
      }
      assertTrue(base.withResolvedSurface(List.of(new Cell<>(5, SemanticMaterial.UNKNOWN)), RESOLVED_BIOME).isEmpty());
   }

   @Test void patchCoordinatesAndOverlappingBodyAreRejectedBeforeAnyOverlay() {
      TellusEarthColumnSnapshot base = shellBase();
      for (int y : new int[]{-9, 6, 8, 24}) {
         assertThrows(IllegalArgumentException.class,
            () -> base.withResolvedSurface(List.of(new Cell<>(y, SemanticMaterial.SAND)), RESOLVED_BIOME));
      }
      List<Cell<SemanticMaterial>> duplicate = List.of(
         new Cell<>(5, SemanticMaterial.SAND), new Cell<>(5, SemanticMaterial.SAND));
      assertThrows(IllegalArgumentException.class, () -> base.withResolvedSurface(duplicate, RESOLVED_BIOME));
      TellusEarthColumnSnapshot unknown = snapshot(base.surface(), base.water(), StrataDecision.unknown());
      assertThrows(IllegalArgumentException.class, () -> unknown.withResolvedSurface(duplicate, RESOLVED_BIOME),
         "invalid patch positions must be rejected even before base completeness is checked");
      TellusEarthColumnSnapshot overlap = snapshot(base.surface(), base.water(), new StrataDecision(List.of(
         new StrataDecision.Layer(-8, 3, SemanticMaterial.STONE),
         new StrataDecision.Layer(3, 5, SemanticMaterial.STONE)), true));
      assertThrows(IllegalArgumentException.class,
         () -> overlap.withResolvedSurface(List.of(), RESOLVED_BIOME));
   }

   @Test void patchAndColumnBudgetsAreRejectedBeforeCellExpansionOrPatchReads() {
      List<Cell<SemanticMaterial>> oversized = new AbstractList<>() {
         @Override public int size() { return 1_048_577; }
         @Override public Cell<SemanticMaterial> get(int index) {
            fail("oversized patch must be rejected before reading its first cell");
            return new Cell<>(0, SemanticMaterial.STONE);
         }
      };
      assertThrows(IllegalArgumentException.class,
         () -> shellBase().withResolvedSurface(oversized, RESOLVED_BIOME));
      TellusGenerationContext huge = new TellusGenerationContext(
         42L, SCALE, 2, Integer.MIN_VALUE, Integer.MAX_VALUE, GEOMETRY);
      TellusEarthColumnSnapshot hugeBody = new TellusEarthColumnSnapshot(huge, COORDINATE, 0,
         new SurfaceDecision(SemanticMaterial.STONE, SemanticMaterial.STONE, 0), WaterDecision.dry(), BIOME,
         new StrataDecision(List.of(new StrataDecision.Layer(Integer.MIN_VALUE, 0, SemanticMaterial.STONE)), true), List.of());
      assertThrows(IllegalArgumentException.class,
         () -> hugeBody.withResolvedSurface(List.of(), RESOLVED_BIOME));
   }

   /** Original surfaceMaterialBottomY and thinShellSurfaceBottomY, retaining native int arithmetic. */
   private static int frozenBottom(int minY, int surface, int depth, boolean thinShell, int undergroundDepth) {
      int surfaceBottom = Math.max(minY + 1, surface - depth + 1);
      if (!thinShell) return surfaceBottom;
      int supportBottom = Math.max(minY, surface - undergroundDepth);
      return Math.min(surface, Math.max(supportBottom + 1, surfaceBottom));
   }

   /** Original fillSurfaceMaterialColumn loop; expected values never call Plan.materialAt(). */
   private static <M> List<Cell<M>> frozenStandard(Span span, M top, M shallow, M bottom) {
      List<Cell<M>> expected = new ArrayList<>();
      for (long position = span.surfaceY(); position >= span.bottomY(); position--) {
         int y = (int)position;
         M material = y == span.surfaceY() ? top : y == span.bottomY() ? bottom : shallow;
         expected.add(new Cell<>(y, material));
      }
      return expected;
   }

   private static <M> void assertPlan(List<Cell<M>> expected, Plan<M> plan) {
      assertEquals(expected.size(), plan.span().cellCount());
      assertEquals(expected, plan.cells());
      List<Cell<M>> emitted = new ArrayList<>();
      plan.forEach((y, material) -> emitted.add(new Cell<>(y, material)));
      assertEquals(expected, emitted);
      for (Cell<M> cell : expected) assertEquals(cell.material(), plan.materialAt(cell.y()));
   }

   private static TellusEarthColumnSnapshot shellBase() {
      var input = new TellusEarthColumnRules.Input(-8, 24, 5, OptionalInt.of(8), true, -8, 3, 4);
      return TellusEarthColumnSnapshot.baseColumn(
         GENERATION, COORDINATE, input, WaterDecision.Kind.OCEAN, BIOME).orElseThrow();
   }

   private static TellusEarthColumnSnapshot snapshot(SurfaceDecision surface, WaterDecision water, StrataDecision strata) {
      return new TellusEarthColumnSnapshot(GENERATION, COORDINATE, 5, surface, water, BIOME, strata,
         List.of("surface patch fixture"));
   }

   private static WriteResult<SemanticColumnCell> materialize(TellusEarthColumnSnapshot snapshot) {
      ColumnDecision decision = new ColumnDecision(snapshot.coordinate(), DecisionStatus.AVAILABLE,
         OptionalInt.of(snapshot.terrainY()), snapshot.surface(), snapshot.water(), snapshot.biome(),
         snapshot.strata(), snapshot.diagnostics());
      WriteResult<SemanticColumnCell> result = new TellusColumnMaterializer().materializeCells(
         WriteContext.canonical(snapshot.coordinate(), snapshot.generation(), "surface-column-test"), decision);
      assertEquals(WriteStatus.WRITTEN, result.status(), result.diagnostics().toString());
      return result;
   }

   private static SemanticMaterial materialAt(WriteResult<SemanticColumnCell> result, int y) {
      return result.mappedWrites().stream().filter(cell -> cell.y() == y).findFirst().orElseThrow().material();
   }
}
