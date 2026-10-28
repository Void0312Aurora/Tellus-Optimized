package com.yucareux.tellus.worldgen;

import net.minecraft.core.Direction;

/** Directional-light mapping between adjacent cube-net charts. */
public final class CubeNetShadeTransform {
   private CubeNetShadeTransform() { }

   public static Direction rotate(Direction direction, int clockwiseQuarterTurns) {
      if (direction.getAxis() == Direction.Axis.Y) return direction;
      return switch (Math.floorMod(clockwiseQuarterTurns, 4)) {
         case 0 -> direction;
         case 1 -> clockwise(direction);
         case 2 -> clockwise(clockwise(direction));
         default -> clockwise(clockwise(clockwise(direction)));
      };
   }

   public static float fluidHorizontalBrightness(Direction direction) {
      return switch (direction.getAxis()) {
         case X -> 0.6F;
         case Z -> 0.8F;
         case Y -> 1.0F;
      };
   }

   private static Direction clockwise(Direction direction) {
      return switch (direction) {
         case NORTH -> Direction.EAST;
         case EAST -> Direction.SOUTH;
         case SOUTH -> Direction.WEST;
         case WEST -> Direction.NORTH;
         default -> direction;
      };
   }
}
