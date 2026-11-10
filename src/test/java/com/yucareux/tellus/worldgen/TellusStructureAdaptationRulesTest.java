package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.TellusStructureAdaptationRules.Bounds;
import com.yucareux.tellus.worldgen.TellusStructureAdaptationRules.CenterColumn;
import com.yucareux.tellus.worldgen.TellusStructureAdaptationRules.Decision;
import com.yucareux.tellus.worldgen.TellusStructureAdaptationRules.Outcome;
import com.yucareux.tellus.worldgen.TellusStructureAdaptationRules.UndergroundKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class TellusStructureAdaptationRulesTest {
   private static final Bounds SMALL = new Bounds(-9, 0, -9, 0, 9, 0);
   private static final Bounds MONUMENT = new Bounds(0, 20, 0, 56, 42, 56);

   @Test
   void boundsMatchNativeUpperMiddleForEvenSpansAndNegativeCoordinates() {
      assertEquals(-4, SMALL.centerX());
      assertEquals(-4, SMALL.centerZ());
      assertEquals(5, SMALL.centerY());
      assertEquals(10, SMALL.ySpan());
      Bounds odd = new Bounds(-8, -8, -8, 0, 0, 0);
      assertEquals(-4, odd.centerX());
      assertEquals(-4, odd.centerY());
      assertEquals(-4, odd.centerZ());
   }

   @Test
   void absentOrInvalidStartsNeverSampleOrInvalidateAgain() {
      for (UndergroundKind kind : UndergroundKind.values()) {
         assertEquals(new Decision(Outcome.KEEP, 0), TellusStructureAdaptationRules.underground(
            kind, false, true, null, 0, 0, 0L, -64, 319,
            (x, z) -> { throw new AssertionError("center sampled"); },
            (x, z) -> { throw new AssertionError("surface sampled"); }
         ));
      }
      assertEquals(new Decision(Outcome.KEEP, 0), TellusStructureAdaptationRules.monument(
         false, true, null, 63, -64, 319, (x, z) -> { throw new AssertionError("water sampled"); }
      ));
   }

   @Test
   void cubeMesaWithoutTerrainShellKeepsStartWithoutAnySampling() {
      assertEquals(new Decision(Outcome.KEEP, 0), TellusStructureAdaptationRules.underground(
         UndergroundKind.MESA_MINESHAFT, true, false, null, 7, 11, 17L, -64, 319,
         (x, z) -> { throw new AssertionError("center sampled"); },
         (x, z) -> { throw new AssertionError("surface sampled"); }
      ));
   }

   @Test
   void normalMineshaftUsesSurfaceCenterThenFullFootprintIncludingFarEdges() {
      List<String> calls = new ArrayList<>();
      TellusStructureAdaptationRules.underground(
         UndergroundKind.NORMAL_MINESHAFT, true, false, SMALL, 0, 0, 0L, -64, 319,
         (x, z) -> { throw new AssertionError("mineshaft sampled water column"); },
         (x, z) -> { calls.add(x + "," + z); return 100; }
      );
      assertEquals(List.of("-4,-4", "-9,-9", "-1,-9", "0,-9", "-9,-1", "-1,-1", "0,-1",
         "-9,0", "-1,0", "0,0"), calls);
   }

   @Test
   void strongholdAtTwentyBlockDepthKeepsOnlyCenterSampling() {
      int[] centerCalls = {0};
      Decision decision = TellusStructureAdaptationRules.underground(
         UndergroundKind.STRONGHOLD, true, false, SMALL, 0, 0, 0L, -64, 319,
         (x, z) -> { centerCalls[0]++; return new CenterColumn(25, false); },
         (x, z) -> { throw new AssertionError("unneeded footprint"); }
      );
      assertEquals(new Decision(Outcome.KEEP, 0), decision);
      assertEquals(1, centerCalls[0]);
      assertUndergroundParity(UndergroundKind.STRONGHOLD, false, SMALL, 0, 0, 0L, -64, 319, 24, false, (x, z) -> 24);
      assertUndergroundParity(UndergroundKind.STRONGHOLD, false, SMALL, 0, 0, 0L, -64, 319, 25, true, (x, z) -> 25);
      assertUndergroundParity(UndergroundKind.STRONGHOLD, true, SMALL, 0, 0, 0L, -64, 319, 25, false, (x, z) -> 25);
   }

   @Test
   void citiesAndTrialsSampleCenterThenEnvelopeEvenWhenAlreadyBuriedOrNoRoom() {
      for (UndergroundKind kind : List.of(UndergroundKind.ANCIENT_CITY, UndergroundKind.TRIAL_CHAMBERS)) {
         List<String> calls = new ArrayList<>();
         Bounds box = new Bounds(0, 10, 0, 9, 20, 9);
         Decision decision = TellusStructureAdaptationRules.underground(
            kind, true, false, box, 123, 456, 0L, 10, 20,
            (x, z) -> { calls.add("C" + x + "," + z); return new CenterColumn(90, true); },
            (x, z) -> { calls.add("S" + x + "," + z); return 28; }
         );
         assertEquals(new Decision(Outcome.KEEP, 0), decision, kind.name());
         assertEquals(List.of("C5,5", "S0,0", "S8,0", "S9,0", "S0,8", "S8,8", "S9,8",
            "S0,9", "S8,9", "S9,9"), calls);
         assertEquals(Outcome.INVALIDATE, TellusStructureAdaptationRules.underground(
            kind, true, false, box, 123, 456, 0L, 10, 20,
            (x, z) -> new CenterColumn(90, false), (x, z) -> 27
         ).outcome());
      }
   }

   @Test
   void everyRetargetedKindRejectsUnfittableProtectedVerticalBounds() {
      for (UndergroundKind kind : UndergroundKind.values()) {
         Decision result = TellusStructureAdaptationRules.underground(
            kind, true, true, new Bounds(0, -60, 0, 9, 80, 9), 13, 27, 99L, -64, 80,
            (x, z) -> new CenterColumn(20, true), (x, z) -> 20
         );
         assertEquals(new Decision(Outcome.INVALIDATE, 0), result, kind.name());
      }
   }

   @Test
   void normalAndMesaUseDifferentDepthRangesAndCoverButTheSameSeedSalt() {
      Bounds box = new Bounds(0, 0, 0, 8, 0, 8);
      for (UndergroundKind kind : List.of(UndergroundKind.NORMAL_MINESHAFT, UndergroundKind.MESA_MINESHAFT)) {
         int depth = oldDepth(kind, 1234567, -9876543, 123L);
         int base = kind == UndergroundKind.NORMAL_MINESHAFT ? 30 : 8;
         int range = kind == UndergroundKind.NORMAL_MINESHAFT ? 21 : 25;
         assertTrue(depth >= base && depth < base + range);
         Decision result = TellusStructureAdaptationRules.underground(
            kind, true, true, box, 1234567, -9876543, 123L, -1000, 2000,
            (x, z) -> { throw new AssertionError("column sampled"); }, (x, z) -> 1000
         );
         assertEquals(1000 - depth, result.offsetY());
      }
      Decision normal = TellusStructureAdaptationRules.underground(
         UndergroundKind.NORMAL_MINESHAFT, true, true, box, 0, 0, 0L, 0, 12,
         null, (x, z) -> 100
      );
      Decision mesa = TellusStructureAdaptationRules.underground(
         UndergroundKind.MESA_MINESHAFT, true, true, box, 0, 0, 0L, 0, 12,
         null, (x, z) -> 100
      );
      assertEquals(Outcome.INVALIDATE, normal.outcome());
      assertEquals(new Decision(Outcome.MOVE, 8), mesa);
   }

   @Test
   void zeroOffsetIsKeepRatherThanRebuildingUnmovedPieces() {
      Bounds box = new Bounds(0, 0, 0, 8, 0, 8);
      for (UndergroundKind kind : UndergroundKind.values()) {
         int surface = oldDepth(kind, 13, -19, 71L);
         assertEquals(new Decision(Outcome.KEEP, 0), TellusStructureAdaptationRules.underground(
            kind, true, true, box, 13, -19, 71L, -1000, 2000,
            (x, z) -> new CenterColumn(surface, false), (x, z) -> surface
         ));
      }
   }

   @Test
   void fixedOldUndergroundOracleMatchesConditionsFitClampsAndSamplingTraces() {
      Bounds[] boxes = {SMALL, new Bounds(-18, -80, 13, 5, -20, 30), new Bounds(1, -60, 1, 17, 140, 9)};
      for (UndergroundKind kind : UndergroundKind.values()) {
         for (boolean shell : new boolean[]{false, true}) {
            for (boolean ocean : new boolean[]{false, true}) {
               for (Bounds box : boxes) {
                  for (int surface : new int[]{-80, -10, 50, 240}) {
                     for (long seed : new long[]{0L, 71L, Long.MIN_VALUE}) {
                        assertUndergroundParity(kind, shell, box, -29999999, 17000001, seed, -64, 319,
                           surface, ocean, (x, z) -> surface - Math.floorMod(x * 3 + z, 13));
                     }
                  }
               }
            }
         }
      }
   }

   @Test
   void depthMatchesIndependentJavaRandomAcrossSeedsCoordinatesOverflowAndProjection() {
      Bounds box = new Bounds(-9, 0, -9, 0, 0, 0);
      int[] coordinates = {0, 1, -1, 686, 29999999, Integer.MIN_VALUE, Integer.MAX_VALUE};
      for (UndergroundKind kind : UndergroundKind.values()) {
         for (int earthX : coordinates) {
            for (int earthZ : coordinates) {
               for (long seed : new long[]{0L, -1L, 1234567891011L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                  Decision result = TellusStructureAdaptationRules.underground(
                     kind, true, true, box, earthX, earthZ, seed, -1000, 2000,
                     (x, z) -> new CenterColumn(1000, false), (x, z) -> 1000
                  );
                  assertEquals(1000 - oldDepth(kind, earthX, earthZ, seed), result.offsetY(),
                     kind + " " + earthX + "," + earthZ + " seed=" + seed);
                  assertEquals(result, TellusStructureAdaptationRules.underground(
                     kind, true, true, box, earthX, earthZ, seed, -1000, 2000,
                     (x, z) -> new CenterColumn(1000, false), (x, z) -> 1000
                  ));
               }
            }
         }
      }
   }

   @Test
   void undergroundBoundsIncludeFiveBlockFloorAndExactTopCoverAtFitBoundary() {
      Bounds box = new Bounds(0, 0, 0, 8, 10, 8);
      // min bottom -59 and maximum top -49 leave exactly one offset (-59).
      assertEquals(new Decision(Outcome.MOVE, -59), TellusStructureAdaptationRules.underground(
         UndergroundKind.STRONGHOLD, true, true, box, 0, 0, 0L, -64, 319,
         (x, z) -> new CenterColumn(100, true), (x, z) -> -41
      ));
      assertEquals(Outcome.INVALIDATE, TellusStructureAdaptationRules.underground(
         UndergroundKind.STRONGHOLD, true, true, box, 0, 0, 0L, -64, 319,
         (x, z) -> new CenterColumn(100, true), (x, z) -> -42
      ).outcome());
      // World ceiling is covered too, even when the sampled surface is higher.
      assertEquals(new Decision(Outcome.MOVE, 32), TellusStructureAdaptationRules.underground(
         UndergroundKind.STRONGHOLD, true, true, box, 0, 0, 0L, -64, 50,
         (x, z) -> new CenterColumn(1000, true), (x, z) -> 1000
      ));
   }

   @Test
   void waterDisabledSkipsViabilityButStillOffsetsAndChecksVerticalBounds() {
      Decision result = TellusStructureAdaptationRules.monument(
         true, false, MONUMENT, 63, -64, 319,
         (x, z) -> { throw new AssertionError("water disabled"); }
      );
      assertEquals(new Decision(Outcome.MOVE, 19), result);
      assertEquals(new Decision(Outcome.INVALIDATE, 0), TellusStructureAdaptationRules.monument(
         true, false, new Bounds(0, -64, 0, 56, 65, 56), 63, -64, 319, null
      ));
   }

   @Test
   void monumentOceanAndDeepRatiosAreInclusiveAtExactBoundary() {
      assertEquals(Outcome.MOVE, assertMonumentParity(true, MONUMENT, 63, -64, 319, ratioDepths(15, 15)).outcome());
      assertEquals(Outcome.INVALIDATE, assertMonumentParity(true, MONUMENT, 63, -64, 319, ratioDepths(16, 14)).outcome());
      assertEquals(Outcome.INVALIDATE, assertMonumentParity(true, MONUMENT, 63, -64, 319, ratioDepths(15, 16)).outcome());
   }

   @Test
   void monumentCoreDepthUsesBothNineteenMinimumAndStructureHeightMinusFour() {
      assertEquals(Outcome.MOVE, assertMonumentParity(true, MONUMENT, 63, -64, 319, (x, z) -> 19).outcome());
      assertEquals(Outcome.INVALIDATE, assertMonumentParity(true, MONUMENT, 63, -64, 319, (x, z) -> 18).outcome());
      Bounds tall = new Bounds(0, 20, 0, 56, 49, 56);
      assertEquals(Outcome.MOVE, assertMonumentParity(true, tall, 63, -64, 319, (x, z) -> 26).outcome());
      assertEquals(Outcome.INVALIDATE, assertMonumentParity(true, tall, 63, -64, 319, (x, z) -> 25).outcome());
      // The deep-ocean threshold is 12, not 19: only the core needs 19.
      assertEquals(Outcome.MOVE, assertMonumentParity(true, MONUMENT, 63, -64, 319,
         (x, z) -> inCore(MONUMENT, x, z) ? 19 : 12).outcome());
      assertEquals(Outcome.INVALIDATE, assertMonumentParity(true, MONUMENT, 63, -64, 319,
         (x, z) -> inCore(MONUMENT, x, z) ? 19 : 11).outcome());
   }

   @Test
   void monumentStopsAtFirstBadCoreSampleAndRequiresAtLeastOneCoreSample() {
      List<String> calls = new ArrayList<>();
      Decision result = TellusStructureAdaptationRules.monument(true, true, MONUMENT, 63, -64, 319,
         (x, z) -> { calls.add(x + "," + z); return x == 8 && z == 8 ? 0 : 40; });
      assertEquals(Outcome.INVALIDATE, result.outcome());
      assertEquals(23, calls.size());
      assertEquals("8,8", calls.get(calls.size() - 1));
      assertEquals(Outcome.INVALIDATE, assertMonumentParity(true,
         new Bounds(0, 20, 0, 15, 42, 15), 63, -64, 319, (x, z) -> 40).outcome());
   }

   @Test
   void monumentIncludesMarginAndNonStrideFarEdgesInSourceOrder() {
      Bounds irregular = new Bounds(-3, 20, 5, 54, 42, 62);
      List<String> calls = new ArrayList<>();
      Decision result = assertMonumentParity(true, irregular, 63, -64, 319,
         (x, z) -> { calls.add(x + "," + z); return 40; });
      assertEquals(Outcome.MOVE, result.outcome());
      // The callback above is invoked once by each independent implementation.
      assertEquals(242, calls.size());
      assertEquals("-11,-3", calls.get(0));
      assertEquals("62,70", calls.get(120));
   }

   @Test
   void monumentOffsetsFollowSeaLevelWorldFloorCeilingAndZeroOffsetRules() {
      assertEquals(new Decision(Outcome.MOVE, 19), assertMonumentParity(false, MONUMENT, 63, -64, 319, null));
      assertEquals(new Decision(Outcome.MOVE, 38), assertMonumentParity(false, MONUMENT, 82, -64, 319, null));
      assertEquals(new Decision(Outcome.MOVE, 8), assertMonumentParity(false, MONUMENT, 63, -64, 50, null));
      assertEquals(new Decision(Outcome.KEEP, 0), assertMonumentParity(false,
         new Bounds(0, 39, 0, 56, 61, 56), 63, -64, 319, null));
      assertEquals(Outcome.INVALIDATE, assertMonumentParity(false,
         new Bounds(0, -64, 0, 56, 65, 56), 63, -64, 319, null).outcome());
      assertEquals(Outcome.INVALIDATE, assertMonumentParity(false,
         new Bounds(0, -64, 0, 56, 80, 56), 63, -64, 64, null).outcome());
      assertEquals(Outcome.INVALIDATE, assertMonumentParity(true, MONUMENT, 63, -64, 319, (x, z) -> 0).outcome());
   }

   @Test
   void fixedOldMonumentOracleMatchesVariedDepthFieldsAndVerticalIntervals() {
      for (Bounds box : new Bounds[]{MONUMENT, new Bounds(-19, -60, 3, 40, -31, 60), new Bounds(0, -64, 0, 56, 80, 56)}) {
         for (boolean water : new boolean[]{false, true}) {
            for (int sea : new int[]{-40, 63, 170}) {
               for (int ceiling : new int[]{50, 319}) {
                  for (int field = 0; field < 6; field++) {
                     int salt = field;
                     assertMonumentParity(water, box, sea, -64, ceiling,
                        (x, z) -> salt == 0 ? 40 : Math.floorMod(x * 19 + z * 7 + salt * 11, 50));
                  }
               }
            }
         }
      }
   }

   private static void assertUndergroundParity(
      UndergroundKind kind, boolean shell, Bounds box, int earthX, int earthZ, long seed,
      int minY, int maxY, int centerSurface, boolean ocean, IntBinaryOperator surface
   ) {
      List<String> oldCalls = new ArrayList<>();
      List<String> calls = new ArrayList<>();
      OldDecision expected = oldUnderground(kind, shell, box, earthX, earthZ, seed, minY, maxY,
         centerSurface, ocean, surface, oldCalls);
      Decision actual = TellusStructureAdaptationRules.underground(
         kind, true, shell, box, earthX, earthZ, seed, minY, maxY,
         (x, z) -> { calls.add("C" + x + "," + z); return new CenterColumn(centerSurface, ocean); },
         (x, z) -> { calls.add("S" + x + "," + z); return surface.applyAsInt(x, z); }
      );
      String context = kind + " shell=" + shell + " ocean=" + ocean + " surface=" + centerSurface + " box=" + box;
      assertEquals(expected.outcome(), actual.outcome().name(), context);
      assertEquals(expected.offset(), actual.offsetY(), context);
      assertEquals(oldCalls, calls, context);
   }

   private static Decision assertMonumentParity(
      boolean water, Bounds box, int sea, int minY, int maxY, IntBinaryOperator depths
   ) {
      List<String> oldCalls = new ArrayList<>();
      List<String> calls = new ArrayList<>();
      OldDecision expected = oldMonument(water, box, sea, minY, maxY,
         (x, z) -> { oldCalls.add(x + "," + z); return depths.applyAsInt(x, z); });
      Decision actual = TellusStructureAdaptationRules.monument(true, water, box, sea, minY, maxY,
         (x, z) -> { calls.add(x + "," + z); return depths.applyAsInt(x, z); });
      assertEquals(expected.outcome(), actual.outcome().name());
      assertEquals(expected.offset(), actual.offsetY());
      assertEquals(oldCalls, calls);
      return actual;
   }

   /** 100 samples: the 36 core cells are always deep, other cells set the ratios. */
   private static IntBinaryOperator ratioDepths(int dry, int shallow) {
      List<String> outer = new ArrayList<>();
      for (int z = -8; z <= 64; z += 8) {
         for (int x = -8; x <= 64; x += 8) {
            if (!inCore(MONUMENT, x, z)) {
               outer.add(x + "," + z);
            }
         }
      }
      return (x, z) -> {
         int index = outer.indexOf(x + "," + z);
         return index < 0 ? 40 : index < dry ? 0 : index < dry + shallow ? 1 : 40;
      };
   }

   private static boolean inCore(Bounds box, int x, int z) {
      return x >= box.minX() + 8 && x <= box.maxX() - 8 && z >= box.minZ() + 8 && z <= box.maxZ() - 8;
   }

   /*
    * Frozen old-Earth oracle. It never invokes a production rule, center/span
    * method, bounds helper, hash helper or random helper. Random(seed).nextInt
    * independently checks the production allocation-free LCG implementation.
    */
   private record OldDecision(String outcome, int offset) {
   }

   private static OldDecision oldUnderground(
      UndergroundKind kind, boolean shell, Bounds b, int earthX, int earthZ, long seed,
      int minY, int maxY, int columnSurface, boolean ocean, IntBinaryOperator surface, List<String> calls
   ) {
      if (kind == UndergroundKind.MESA_MINESHAFT && !shell) {
         return new OldDecision("KEEP", 0);
      }
      int centerX = b.minX() + (b.maxX() - b.minX() + 1) / 2;
      int centerY = b.minY() + (b.maxY() - b.minY() + 1) / 2;
      int centerZ = b.minZ() + (b.maxZ() - b.minZ() + 1) / 2;
      int height;
      if (kind == UndergroundKind.NORMAL_MINESHAFT || kind == UndergroundKind.MESA_MINESHAFT) {
         calls.add("S" + centerX + "," + centerZ);
         height = surface.applyAsInt(centerX, centerZ);
      } else {
         calls.add("C" + centerX + "," + centerZ);
         height = columnSurface;
         if (kind == UndergroundKind.STRONGHOLD && !shell && !ocean && centerY <= height - 20) {
            return new OldDecision("KEEP", 0);
         }
      }
      int lowest = Integer.MAX_VALUE;
      for (int z = b.minZ(); ; z = Math.min(z + 8, b.maxZ())) {
         for (int x = b.minX(); ; x = Math.min(x + 8, b.maxX())) {
            calls.add("S" + x + "," + z);
            lowest = Math.min(lowest, surface.applyAsInt(x, z));
            if (x == b.maxX()) break;
         }
         if (z == b.maxZ()) break;
      }
      if (kind == UndergroundKind.ANCIENT_CITY || kind == UndergroundKind.TRIAL_CHAMBERS) {
         int requiredTopY = lowest - 8;
         if (!(shell || b.maxY() > requiredTopY)) {
            return new OldDecision("KEEP", 0);
         }
      }
      int topCover = kind == UndergroundKind.NORMAL_MINESHAFT ? 14 : kind == UndergroundKind.MESA_MINESHAFT ? 4 : 8;
      int minimumBottom = Math.max(minY + 4, minY + 1 + 4);
      int maximumTop = Math.min(maxY - topCover, lowest - topCover);
      int minimumOffset = minimumBottom - b.minY();
      int maximumOffset = maximumTop - b.maxY();
      if (minimumOffset > maximumOffset) {
         return new OldDecision("INVALIDATE", 0);
      }
      int requested = height - oldDepth(kind, earthX, earthZ, seed) - centerY;
      int offset = requested < minimumOffset ? minimumOffset : Math.min(requested, maximumOffset);
      return new OldDecision(offset == 0 ? "KEEP" : "MOVE", offset);
   }

   private static int oldDepth(UndergroundKind kind, int x, int z, long worldSeed) {
      int y = switch (kind) {
         case STRONGHOLD -> 19;
         case NORMAL_MINESHAFT, MESA_MINESHAFT -> 14;
         case ANCIENT_CITY -> 23;
         case TRIAL_CHAMBERS -> 17;
      };
      long salt = switch (kind) {
         case STRONGHOLD -> 8927292519556160640L;
         case NORMAL_MINESHAFT, MESA_MINESHAFT -> 2115183054018638465L;
         case ANCIENT_CITY -> 7099176517079344131L;
         case TRIAL_CHAMBERS -> 4235900233171027605L;
      };
      int base = switch (kind) {
         case STRONGHOLD -> 42;
         case NORMAL_MINESHAFT -> 30;
         case MESA_MINESHAFT -> 8;
         case ANCIENT_CITY -> 92;
         case TRIAL_CHAMBERS -> 72;
      };
      int range = switch (kind) {
         case STRONGHOLD -> 17;
         case NORMAL_MINESHAFT -> 21;
         case MESA_MINESHAFT -> 25;
         case ANCIENT_CITY -> 29;
         case TRIAL_CHAMBERS -> 25;
      };
      long coordinateSeed = x * 3129871 ^ z * 116129781L ^ y;
      coordinateSeed = coordinateSeed * coordinateSeed * 42317861L + coordinateSeed * 11L;
      return base + new Random((coordinateSeed >> 16) ^ worldSeed ^ salt).nextInt(range);
   }

   private static OldDecision oldMonument(boolean water, Bounds b, int sea, int minY, int maxY, IntBinaryOperator depths) {
      if (water) {
         int minX = b.minX() - 8;
         int maxX = b.maxX() + 8;
         int minZ = b.minZ() - 8;
         int maxZ = b.maxZ() + 8;
         int coreMinX = b.minX() + 8;
         int coreMaxX = b.maxX() - 8;
         int coreMinZ = b.minZ() + 8;
         int coreMaxZ = b.maxZ() - 8;
         int requiredCoreDepth = Math.max(12 + 7, b.maxY() - b.minY() + 1 - 4);
         int totalSamples = 0;
         int oceanSamples = 0;
         int deepOceanSamples = 0;
         int coreSamples = 0;
         for (int z = minZ; ; z = Math.min(z + 8, maxZ)) {
            for (int x = minX; ; x = Math.min(x + 8, maxX)) {
               int waterDepth = depths.applyAsInt(x, z);
               boolean oceanColumn = waterDepth > 0;
               totalSamples++;
               if (oceanColumn) oceanSamples++;
               if (waterDepth >= 12) deepOceanSamples++;
               if (x >= coreMinX && x <= coreMaxX && z >= coreMinZ && z <= coreMaxZ) {
                  coreSamples++;
                  if (!oceanColumn || waterDepth < requiredCoreDepth) return new OldDecision("INVALIDATE", 0);
               }
               if (x == maxX) break;
            }
            if (z == maxZ) break;
         }
         if (!(coreSamples > 0 && (double)oceanSamples / totalSamples >= 0.85
            && (double)deepOceanSamples / totalSamples >= 0.7)) {
            return new OldDecision("INVALIDATE", 0);
         }
      }
      int targetTopY = sea - 2;
      int desiredMinY = targetTopY - (b.maxY() - b.minY() + 1) + 1;
      int minimumOffset = minY + 1 - b.minY();
      int maximumOffset = maxY - b.maxY();
      int requested = desiredMinY - b.minY();
      int offset = requested < minimumOffset ? minimumOffset : Math.min(requested, maximumOffset);
      if (b.maxY() + offset > targetTopY) return new OldDecision("INVALIDATE", 0);
      return new OldDecision(offset == 0 ? "KEEP" : "MOVE", offset);
   }
}
