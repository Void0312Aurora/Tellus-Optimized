package com.yucareux.tellus.worldgen;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CubeNetShadeTransformTest {
   @Test
   void rotatesHorizontalShadeDirectionsAndPreservesVerticalDirections() {
      assertEquals(Direction.EAST, CubeNetShadeTransform.rotate(Direction.NORTH, 1));
      assertEquals(Direction.SOUTH, CubeNetShadeTransform.rotate(Direction.NORTH, 2));
      assertEquals(Direction.WEST, CubeNetShadeTransform.rotate(Direction.NORTH, 3));
      assertEquals(Direction.NORTH, CubeNetShadeTransform.rotate(Direction.NORTH, 4));
      assertEquals(Direction.UP, CubeNetShadeTransform.rotate(Direction.UP, 3));
      assertEquals(Direction.DOWN, CubeNetShadeTransform.rotate(Direction.DOWN, -1));
   }

   @Test
   void fluidBrightnessTracksTheMappedHorizontalAxis() {
      assertEquals(0.8F, CubeNetShadeTransform.fluidHorizontalBrightness(Direction.NORTH));
      assertEquals(0.6F, CubeNetShadeTransform.fluidHorizontalBrightness(Direction.WEST));
      assertEquals(0.6F, CubeNetShadeTransform.fluidHorizontalBrightness(
         CubeNetShadeTransform.rotate(Direction.NORTH, 1)));
      assertEquals(1.0F, CubeNetShadeTransform.fluidHorizontalBrightness(Direction.UP));
   }
}
