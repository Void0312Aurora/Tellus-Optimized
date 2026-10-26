package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.ClosedQuadSphereTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosedQuadSphereTopologyTest {
   @Test
   void oneSubdivisionHasCubeEulerAndEightExtraordinaryVertices() {
      var topology = ClosedQuadSphereTopology.create(1);

      assertEquals(new ClosedQuadSphereTopology.Audit(8, 6, 12, 8, 0, 0, 8, 2), topology.audit());
      assertEquals(8, topology.vertices().size());
      for (var vertex : topology.vertices()) assertEquals(3, topology.vertexDegree(vertex));
   }

   @Test
   void fourSubdivisionsRemainClosedAndSatisfyQuadrilateralEulerDefect() {
      int n = 4;
      var topology = ClosedQuadSphereTopology.create(n);
      var audit = topology.audit();

      assertEquals(6 * n * n, audit.faces());
      assertEquals(6 * n * n + 2, audit.vertices());
      assertEquals(12 * n * n, audit.edges());
      assertEquals(8, audit.degreeThreeVertices());
      assertEquals(audit.vertices() - 8, audit.degreeFourVertices());
      assertEquals(0, audit.otherDegreeVertices());
      assertEquals(8, audit.defectSum());
      assertEquals(2, audit.eulerCharacteristic());
      assertEquals(audit.edges(), topology.edges().size());
      for (var edge : topology.edges()) assertTrue(edge.first().compareTo(edge.second()) < 0);
      for (var cell : topology.cells()) {
         for (var direction : ClosedQuadSphereTopology.EdgeDirection.values()) {
            var destination = topology.neighbor(cell, direction);
            assertTrue(topology.cells().contains(destination));
         }
      }
   }

   @Test
   void insertionOrderDoesNotChangeTheCanonicalTopology() {
      var forward = ClosedQuadSphereTopology.create(7, false);
      var reverse = ClosedQuadSphereTopology.create(7, true);

      assertEquals(forward.audit(), reverse.audit());
      assertEquals(forward.canonicalSignature(), reverse.canonicalSignature());
   }

   @Test
   void everyEdgeHasExactlyOneReciprocalDirection() {
      var topology = ClosedQuadSphereTopology.create(5);
      for (var cell : topology.cells()) {
         for (var direction : ClosedQuadSphereTopology.EdgeDirection.values()) {
            var destination = topology.neighbor(cell, direction);
            int returnDirections = 0;
            for (var returnDirection : ClosedQuadSphereTopology.EdgeDirection.values()) {
               if (topology.neighbor(destination, returnDirection).equals(cell)) returnDirections++;
            }
            assertEquals(1, returnDirections, () -> cell + " -> " + destination);
         }
      }
   }

   @Test
   void sharedCubeBoundaryVerticesMapToOneExactSphericalPoint() {
      int n = 5;
      var topology = ClosedQuadSphereTopology.create(n);
      var model = SphereWorldModel.forWorldScale(1.0);
      double faceBlocks = model.config().faceBlocks();
      for (var vertex : topology.vertices()) {
         var uses = topology.uses(vertex);
         assertTrue(uses.size() >= 3);
         var first = uses.get(0);
         var firstPoint = model.exactSurfacePoint(first.face(), first.i() * faceBlocks / n,
            first.j() * faceBlocks / n);
         Set<ClosedQuadSphereTopology.VertexKey> seen = new HashSet<>();
         for (var use : uses) {
            seen.add(ClosedQuadSphereTopology.cubeVertex(use, n));
            var point = model.exactSurfacePoint(use.face(), use.i() * faceBlocks / n,
               use.j() * faceBlocks / n);
            assertEquals(0.0, SphereWorldModel.greatCircleDistanceMeters(firstPoint, point), 1.0E-7,
               () -> "inconsistent shared vertex " + vertex + " uses=" + uses);
         }
         assertEquals(Set.of(vertex), seen);
      }
   }
}
