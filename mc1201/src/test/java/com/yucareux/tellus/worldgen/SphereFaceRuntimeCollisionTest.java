package com.yucareux.tellus.worldgen;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SphereFaceRuntimeCollisionTest {
   @Test
   void destinationProbePreservesVanillaBoxShapeAndOffsetsItsFeet() {
      AABB source = new AABB(9.7, 64.0, 19.7, 10.3, 65.8, 20.3);
      AABB destination = SphereFaceRuntime.destinationBox(source, 10.0, 64.0, 20.0,
         -4.0, 80.0, 7.0);

      assertEquals(-4.3, destination.minX, 1.0E-9);
      assertEquals(80.0, destination.minY, 1.0E-9);
      assertEquals(6.7, destination.minZ, 1.0E-9);
      assertEquals(-3.7, destination.maxX, 1.0E-9);
      assertEquals(81.8, destination.maxY, 1.0E-9);
      assertEquals(7.3, destination.maxZ, 1.0E-9);
   }
}
