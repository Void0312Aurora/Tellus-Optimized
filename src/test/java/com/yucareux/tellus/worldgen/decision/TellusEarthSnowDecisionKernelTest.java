package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TellusEarthSnowDecisionKernelTest {
   private static final long SEED = 42L;

   @Test void partialCoverageUsesOneResolvedSurfaceCellNotAnAboveSurfaceLayer() {
      var kernel = new TellusEarthSnowDecisionKernel(SEED, -64, 128);
      var input = new TellusEarthSnowDecisionKernel.Input(123, -87, 32, false, false);
      assertEquals(List.of(cell(32, SemanticMaterial.SNOW)), kernel.resolve(input));
      assertEquals(kernel.resolve(input), kernel.resolveSections(input));
   }

   @Test void cursorAndSectionRetainTheirDifferentNativeCellOrders() {
      var kernel = new TellusEarthSnowDecisionKernel(SEED, -64);
      var input = new TellusEarthSnowDecisionKernel.Input(0, 0, 80, true, false);
      // Literal source snapshot: seed42/(0,0) has five ordinary snow blocks.
      assertEquals(List.of(cell(80, SemanticMaterial.SNOW), cell(79, SemanticMaterial.SNOW),
         cell(78, SemanticMaterial.SNOW), cell(77, SemanticMaterial.SNOW), cell(76, SemanticMaterial.SNOW)),
         kernel.resolveCursor(input));
      assertEquals(List.of(cell(76, SemanticMaterial.SNOW), cell(77, SemanticMaterial.SNOW),
         cell(78, SemanticMaterial.SNOW), cell(79, SemanticMaterial.SNOW), cell(80, SemanticMaterial.SNOW)),
         kernel.resolveSections(input));
   }

   @Test void minimumAndExclusiveMaximumClipOnlyEffectiveTargetCells() {
      var kernel = new TellusEarthSnowDecisionKernel(SEED, 78, 80);
      var input = new TellusEarthSnowDecisionKernel.Input(0, 0, 80, true, false);
      assertEquals(List.of(cell(79, SemanticMaterial.SNOW), cell(78, SemanticMaterial.SNOW)), kernel.resolve(input));
      assertEquals(List.of(cell(78, SemanticMaterial.SNOW), cell(79, SemanticMaterial.SNOW)), kernel.resolveSections(input));
      assertTrue(kernel.resolve(new TellusEarthSnowDecisionKernel.Input(0, 0, 77, true, false)).isEmpty());
      assertTrue(kernel.resolveSections(new TellusEarthSnowDecisionKernel.Input(0, 0, 77, true, false)).isEmpty());
      assertTrue(kernel.resolve(new TellusEarthSnowDecisionKernel.Input(0, 0, 100, true, false)).isEmpty());
      assertTrue(kernel.resolveSections(new TellusEarthSnowDecisionKernel.Input(0, 0, 100, true, false)).isEmpty());
   }

   @Test void glacierPaletteChangesNeitherDepthNorSurfaceAndNeverUsesPowderSnow() {
      var kernel = new TellusEarthSnowDecisionKernel(SEED, -64);
      // Literal frozen points cover all three actual glacier palette outcomes.
      int[][] points = {{0, 0, 5}, {123, -87, 7}, {-200, 219, 4}};
      SemanticMaterial[] materials = {SemanticMaterial.SNOW, SemanticMaterial.BLUE_ICE, SemanticMaterial.PACKED_ICE};
      for (int index = 0; index < points.length; index++) {
         int[] point = points[index];
         var input = new TellusEarthSnowDecisionKernel.Input(point[0], point[1], 20, true, true);
         var cells = kernel.resolve(input);
         assertEquals(point[2], cells.size());
         assertEquals(20, cells.get(0).y());
         assertEquals(20 - point[2] + 1, cells.get(cells.size() - 1).y());
         for (var cell : cells) assertEquals(materials[index], cell.material());
         assertEquals(1, kernel.resolve(new TellusEarthSnowDecisionKernel.Input(point[0], point[1], 20, false, true)).size());
      }
   }

   @Test void powderSnowIsTheNativeFullDefaultBlockMaterialNotSnowLayerOrIce() {
      var kernel = new TellusEarthSnowDecisionKernel(SEED, -64);
      assertEquals(List.of(cell(20, SemanticMaterial.POWDER_SNOW)),
         kernel.resolve(new TellusEarthSnowDecisionKernel.Input(37, -91, 20, false, false)));
      assertEquals(new TellusEarthSnowDecisionKernel.Column(6, SemanticMaterial.POWDER_SNOW),
         TellusEarthSnowDecisionKernel.resolveColumn(SEED,
            new TellusEarthSnowDecisionKernel.Input(37, -91, 20, true, false)));
   }

   @Test void extremeMinimumRetainsOriginalCursorWrapAndSectionBottomDivergence() {
      var kernel = new TellusEarthSnowDecisionKernel(SEED, Integer.MIN_VALUE);
      var input = new TellusEarthSnowDecisionKernel.Input(0, 0, Integer.MIN_VALUE, true, false);
      assertEquals(List.of(cell(Integer.MIN_VALUE, SemanticMaterial.SNOW), cell(Integer.MAX_VALUE, SemanticMaterial.SNOW),
         cell(Integer.MAX_VALUE - 1, SemanticMaterial.SNOW), cell(Integer.MAX_VALUE - 2, SemanticMaterial.SNOW),
         cell(Integer.MAX_VALUE - 3, SemanticMaterial.SNOW)), kernel.resolveCursor(input));
      assertEquals(Integer.MAX_VALUE - 3,
         TellusEarthSnowDecisionKernel.resolveColumn(SEED, input).sectionBottom(Integer.MIN_VALUE, Integer.MIN_VALUE));
      assertTrue(kernel.resolveSections(input).isEmpty());
   }

   @Test void maximumIntSurfaceTerminatesAndExtremeCoordinateNoiseIsRetained() {
      var kernel = new TellusEarthSnowDecisionKernel(SEED, Integer.MAX_VALUE - 2);
      var input = new TellusEarthSnowDecisionKernel.Input(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, true, true);
      assertEquals(new TellusEarthSnowDecisionKernel.Column(5, SemanticMaterial.PACKED_ICE),
         TellusEarthSnowDecisionKernel.resolveColumn(SEED, input));
      assertEquals(List.of(cell(Integer.MAX_VALUE, SemanticMaterial.PACKED_ICE),
         cell(Integer.MAX_VALUE - 1, SemanticMaterial.PACKED_ICE), cell(Integer.MAX_VALUE - 2, SemanticMaterial.PACKED_ICE)),
         kernel.resolveCursor(input));
      assertEquals(List.of(cell(Integer.MAX_VALUE - 2, SemanticMaterial.PACKED_ICE),
         cell(Integer.MAX_VALUE - 1, SemanticMaterial.PACKED_ICE), cell(Integer.MAX_VALUE, SemanticMaterial.PACKED_ICE)),
         kernel.resolveSections(input));
   }

   @Test void resultsAreImmutableAndNoTargetSourceOrLifecycleIsRetained() {
      var kernel = new TellusEarthSnowDecisionKernel(0, -64, 320);
      var input = new TellusEarthSnowDecisionKernel.Input(0, 0, 0, true, false);
      assertEquals(0, kernel.worldSeed());
      assertEquals(-64, kernel.minY());
      assertEquals(320, kernel.maxYExclusive());
      assertEquals(4, kernel.resolve(input).size());
      assertEquals(SemanticMaterial.POWDER_SNOW, kernel.resolve(input).get(0).material());
      assertThrows(UnsupportedOperationException.class, () -> kernel.resolve(input).clear());
      assertThrows(UnsupportedOperationException.class, () -> kernel.resolveSections(input).clear());
      assertThrows(NullPointerException.class, () -> kernel.resolve(null));
   }

   @Test void unsupportedMaterialsAndInvalidBoundsAreRejectedWithoutEnumChanges() {
      for (SemanticMaterial material : List.of(SemanticMaterial.UNKNOWN, SemanticMaterial.AIR,
         SemanticMaterial.WATER, SemanticMaterial.DIRT, SemanticMaterial.ICE)) {
         assertThrows(IllegalArgumentException.class, () -> cell(0, material));
         assertThrows(IllegalArgumentException.class, () -> new TellusEarthSnowDecisionKernel.Column(1, material));
      }
      assertThrows(IllegalArgumentException.class, () -> new TellusEarthSnowDecisionKernel.Column(0, SemanticMaterial.SNOW));
      assertThrows(IllegalArgumentException.class, () -> new TellusEarthSnowDecisionKernel(0, 10, 10));
      assertThrows(IllegalArgumentException.class, () -> new TellusEarthSnowDecisionKernel(0, 0, (long)Integer.MAX_VALUE + 2));
      assertDoesNotThrow(() -> cell(0, SemanticMaterial.STONE));
      assertEquals(21, SemanticMaterial.SNOW.ordinal());
      assertEquals(22, SemanticMaterial.POWDER_SNOW.ordinal());
      assertEquals(24, SemanticMaterial.PACKED_ICE.ordinal());
      assertEquals(25, SemanticMaterial.BLUE_ICE.ordinal());
   }

   private static TellusEarthSnowDecisionKernel.Cell cell(int y, SemanticMaterial material) {
      return new TellusEarthSnowDecisionKernel.Cell(y, material);
   }
}
