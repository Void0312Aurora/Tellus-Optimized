package com.yucareux.tellus.worldgen;

import java.util.function.IntBinaryOperator;

/** Pure footprint predicates shared by the Cube-Net generator variants. */
final class CubeNetStructurePlacement {
   @FunctionalInterface
   interface ColumnPredicate {
      boolean test(int x, int z);
   }

   private CubeNetStructurePlacement() {
   }

   static boolean surfaceTooSteep(
      int minX, int maxX, int minZ, int maxZ, int sampleStep, int maxHeightDelta,
      IntBinaryOperator surfaceY
   ) {
      int minHeight = Integer.MAX_VALUE;
      int maxHeight = Integer.MIN_VALUE;
      int stride = Math.max(1, sampleStep);
      for (int z = minZ; ; z = Math.min(z + stride, maxZ)) {
         for (int x = minX; ; x = Math.min(x + stride, maxX)) {
            int surface = surfaceY.applyAsInt(x, z);
            minHeight = Math.min(minHeight, surface);
            maxHeight = Math.max(maxHeight, surface);
            if ((long)maxHeight - minHeight > maxHeightDelta) return true;
            if (x == maxX) break;
         }
         if (z == maxZ) break;
      }
      return false;
   }

   static boolean footprintTouchesWater(
      int minX, int maxX, int minZ, int maxZ, int sampleStep, ColumnPredicate waterColumn
   ) {
      int stride = Math.max(1, sampleStep);
      for (int z = minZ; z <= maxZ; z += stride) {
         if (rowTouchesWater(minX, maxX, z, stride, waterColumn)) return true;
      }
      return (maxZ - minZ) % stride != 0 && rowTouchesWater(minX, maxX, maxZ, stride, waterColumn);
   }

   private static boolean rowTouchesWater(int minX, int maxX, int z, int stride, ColumnPredicate waterColumn) {
      for (int x = minX; x <= maxX; x += stride) {
         if (waterColumn.test(x, z)) return true;
      }
      return (maxX - minX) % stride != 0 && waterColumn.test(maxX, z);
   }
}
