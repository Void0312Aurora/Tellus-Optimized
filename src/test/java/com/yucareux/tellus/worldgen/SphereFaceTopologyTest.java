package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereFaceTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SphereFaceTopologyTest {
   @Test
   void continuousCrossingsPreserveSphericalPositionOnAllEdgesAndCornerOvershoots() {
      for (double scale : new double[]{1.0, 30.0, 1000.0}) {
         var model = SphereWorldModel.forWorldScale(scale);
         var topology = new SphereFaceTopology(model);
         double min = topology.minEngineBlock(), max = topology.maxEngineBlock();
         for (int face = 0; face < 6; face++) {
            for (double t : new double[]{min, min * 0.4, 0, max * 0.4, max}) {
               for (double overshoot : new double[]{0.125, 0.375, 15.75}) {
                  double[][] points = {{min - overshoot, t}, {max + overshoot, t}, {t, min - overshoot}, {t, max + overshoot},
                     {min - overshoot, min - overshoot}, {max + overshoot, max + overshoot},
                     {min - overshoot, max + overshoot}, {max + overshoot, min - overshoot}};
                  for (double[] point : points) {
                     var transition = topology.transition(face, point[0], point[1]);
                     var target = topology.enginePoint(transition.destination());
                     assertNotEquals(face, target.face());
                     var original = model.continuedSurfacePoint(face, point[0] - min, point[1] - min);
                     var mapped = model.exactSurfacePoint(target.face(), target.x() - min, target.z() - min);
                     assertTrue(SphereWorldModel.greatCircleDistanceMeters(original, mapped) < 1.0E-7,
                        () -> "lost fractional spherical position at " + transition);
                  }
               }
            }
         }
      }
   }

   @Test
   void analyticTransportPreservesTangentMotionAndYawOnEveryFaceEdge() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var topology = new SphereFaceTopology(model);
      double min = topology.minEngineBlock(), max = topology.maxEngineBlock();
      double step = 0.125;
      for (int face = 0; face < 6; face++) {
         for (double t : new double[]{min + 10, min * 0.4, 0, max * 0.4, max - 10}) {
            for (double[] p : new double[][]{{min - 0.375, t}, {max + 0.375, t}, {t, min - 0.375}, {t, max + 0.375}}) {
               var frame = topology.transition(face, p[0], p[1]);
               var target = topology.enginePoint(frame.destination());
               for (double[] velocity : new double[][]{{1, 0}, {0, 1}, {0.6, -0.8}}) {
                  var mapped = frame.transport(velocity[0], velocity[1]);
                  var sourceStep = model.continuedSurfacePoint(face, p[0] - min + step * velocity[0], p[1] - min + step * velocity[1]);
                  var targetStep = model.continuedSurfacePoint(target.face(), target.x() - min + step * mapped.x(), target.z() - min + step * mapped.z());
                  assertTrue(SphereWorldModel.greatCircleDistanceMeters(sourceStep, targetStep) < 1.0E-7,
                     "analytic Jacobian must agree with spherical displacement to second order");
               }
               for (float yaw : new float[]{0, 90, -90, 179, 37}) {
                  var vector = frame.transport(-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw)));
                  double mappedYaw = Math.toRadians(frame.transportYaw(yaw));
                  double norm = Math.hypot(vector.x(), vector.z());
                  assertEquals(vector.x() / norm, -Math.sin(mappedYaw), 1.0E-6);
                  assertEquals(vector.z() / norm, Math.cos(mappedYaw), 1.0E-6);
               }
            }
         }
      }
   }

   @Test
   void seamTransferBundlesPositionYawAndVelocityForOneSharedHandoffContract() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var topology = new SphereFaceTopology(model);
      double x = 0.0;
      double z = topology.maxEngineBlock() + 0.375;
      float yaw = 27.0F;
      double velocityX = 0.07;
      double velocityZ = 0.2;

      var transfer = topology.transfer(0, x, z, yaw, velocityX, velocityZ);
      var frame = topology.transition(0, x, z);
      var expectedPoint = topology.enginePoint(frame.destination());
      var expectedVelocity = frame.transport(velocityX, velocityZ);

      assertEquals(expectedPoint, transfer.destination());
      assertEquals(frame, transfer.frame());
      assertEquals(frame.transportYaw(yaw), transfer.yaw(), 1.0E-6F);
      assertEquals(expectedVelocity, transfer.velocity());
      assertEquals(2, transfer.destination().face());
   }

   @Test
   void exactFaceBoundaryHasNoCrossFaceDestinationAndMustBeHeldInSourceDomain() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var topology = new SphereFaceTopology(model);
      var transfer = topology.transfer(0, 0.0, topology.maxEngineBlock(), 0.0F, 0.0, 1.0);

      assertFalse(topology.containsEnginePoint(0.0, topology.maxEngineBlock()));
      assertEquals(0, transfer.destination().face());
      assertEquals(model.config().faceBlocks(), transfer.frame().destination().blockZ(), 0.0);
   }

   @Test
   void continuousAdapterHandlesOddFaceSizesAndRejectsTangentSingularities() {
      var model = new SphereWorldModel(new SphereWorldModel.Config(1, 48, 16, 12, 100));
      var topology = new SphereFaceTopology(model);
      assertEquals(-16, topology.minEngineBlock());
      assertEquals(32, topology.maxEngineBlock());
      assertTrue(topology.containsEnginePoint(-16, -16));
      assertFalse(topology.containsEnginePoint(32, 0));
      assertFalse(topology.containsEnginePoint(Double.NaN, 0));
      assertThrows(IllegalArgumentException.class, () -> model.transition(0, -24, 0));
      assertThrows(IllegalArgumentException.class, () -> model.transition(0, 72, 0));
      assertThrows(IllegalArgumentException.class, () -> model.transition(6, 1, 1));
   }

   @Test
   void edgeNeighborsHaveExactlyOneReturnDirectionIncludingCorners() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var topology = new SphereFaceTopology(model);
      long last = model.chunksPerFace() - 1;
      int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
      for (int face = 0; face < 6; face++) {
         for (long t : new long[]{0, last / 3, last / 2, 2 * last / 3, last}) {
            var edges = new SphereWorldModel.SphereChunkAddress[]{
               new SphereWorldModel.SphereChunkAddress(face, 0, t),
               new SphereWorldModel.SphereChunkAddress(face, last, t),
               new SphereWorldModel.SphereChunkAddress(face, t, 0),
               new SphereWorldModel.SphereChunkAddress(face, t, last)
            };
            for (int i = 0; i < edges.length; i++) {
               var source = edges[i];
               var dest = topology.neighbor(source, directions[i][0], directions[i][1]);
               assertNotEquals(source.face(), dest.face());
               int returnDirections = 0;
               for (int[] direction : directions) {
                  if (topology.neighbor(dest, direction[0], direction[1]).equals(source)) returnDirections++;
               }
               assertEquals(1, returnDirections, () -> "non-reciprocal chunk edge: " + source + " -> " + dest);
               double distance = SphereWorldModel.greatCircleDistanceMeters(model.exactSurfacePoint(source), model.exactSurfacePoint(dest));
               assertTrue(distance < 40.0, () -> "edge jumped " + distance + " metres");
            }
         }
      }
   }

   @Test
   void centeredStorageIncludesEveryChunkWhenTheFaceHasAnOddChunkCount() {
      var model = new SphereWorldModel(new SphereWorldModel.Config(1.0, 48, 16, 12, 100.0));
      var topology = new SphereFaceTopology(model);
      for (long x = -1; x <= 1; x++) {
         for (long z = -1; z <= 1; z++) {
            var address = topology.address(0, x, z);
            assertEquals(x + 1, address.chunkX());
            assertEquals(z + 1, address.chunkZ());
            var block = topology.blockAddress(0, x * 16, z * 16);
            assertEquals(address.blockX(), block.blockX());
            assertEquals(address.blockZ(), block.blockZ());
         }
      }
      assertThrows(IllegalArgumentException.class, () -> topology.address(6, 0, 0));
      assertThrows(IllegalArgumentException.class, () -> topology.address(0, 2, 0));
   }
}
