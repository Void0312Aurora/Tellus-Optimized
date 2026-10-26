package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.ClosedQuadSphereEmbedding;
import com.yucareux.tellus.worldgen.global.ClosedQuadSphereTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphericalIncisionPolicy;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SphericalIncisionPolicyTest {
   @Test
   void zeroRadiusQuarantinesEverySingularIncidentCell() {
      var topology = ClosedQuadSphereTopology.create(5);
      var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0),
         ClosedQuadSphereEmbedding.Mapping.SPHERIFIED_CUBE);
      var policy = new SphericalIncisionPolicy(topology, embedding,
         new SphericalIncisionPolicy.Config(0.0, 0.0));

      assertEquals(8, policy.extraordinaryVertices().size());
      assertEquals(24, policy.transitionCells().size());
      assertEquals(topology.cells().size() - 24, policy.ordinaryCells().size());
      assertTrue(policy.excludedCells().isEmpty());
      for (var cell : policy.transitionCells()) {
         assertTrue(topology.vertices(cell).stream().anyMatch(policy.extraordinaryVertices()::contains),
            () -> "transition cell is not incident to a singular vertex: " + cell);
      }
      for (var cell : policy.ordinaryCells()) {
         assertTrue(topology.vertices(cell).stream().noneMatch(policy.extraordinaryVertices()::contains),
            () -> "ordinary cell touches a singular vertex: " + cell);
      }
   }

   @Test
   void positiveCapMovesIncidentCellsToExcludedAndNeverToOrdinary() {
      var topology = ClosedQuadSphereTopology.create(5);
      var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0));
      var policy = new SphericalIncisionPolicy(topology, embedding,
         new SphericalIncisionPolicy.Config(300_000.0, 500_000.0));

      assertEquals(24, policy.excludedCells().size());
      assertTrue(policy.transitionCells().isEmpty(),
         "the coarse subdivision puts the first transition width inside the incident cells");
      assertEquals(topology.cells().size() - 24, policy.ordinaryCells().size());
      for (var cell : policy.excludedCells()) assertFalse(policy.admitsOrdinaryMaterialization(cell));
   }

   @Test
   void largerTransitionWidthCanOnlyRemoveOrdinaryCells() {
      var topology = ClosedQuadSphereTopology.create(17);
      var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0));
      var narrow = new SphericalIncisionPolicy(topology, embedding,
         new SphericalIncisionPolicy.Config(0.0, 100_000.0));
      var wide = new SphericalIncisionPolicy(topology, embedding,
         new SphericalIncisionPolicy.Config(0.0, 1_000_000.0));

      assertTrue(wide.ordinaryCells().size() <= narrow.ordinaryCells().size());
      assertTrue(wide.transitionCells().containsAll(narrow.transitionCells()));
      for (var cell : wide.ordinaryCells()) assertFalse(wide.transitionCells().contains(cell));
   }

   @Test
   void classificationAndTopologyIdentityAreStableAcrossInsertionOrder() {
      var forward = ClosedQuadSphereTopology.create(7, false);
      var reverse = ClosedQuadSphereTopology.create(7, true);
      var model = SphereWorldModel.forWorldScale(1.0);
      var forwardPolicy = new SphericalIncisionPolicy(forward,
         new ClosedQuadSphereEmbedding(forward, model),
         new SphericalIncisionPolicy.Config(0.0, 700_000.0));
      var reversePolicy = new SphericalIncisionPolicy(reverse,
         new ClosedQuadSphereEmbedding(reverse, model),
         new SphericalIncisionPolicy.Config(0.0, 700_000.0));

      assertEquals(forwardPolicy.ordinaryCells(), reversePolicy.ordinaryCells());
      assertEquals(forwardPolicy.transitionCells(), reversePolicy.transitionCells());
      assertEquals(forwardPolicy.excludedCells(), reversePolicy.excludedCells());
      for (var cell : forward.cells()) {
         assertEquals(cell, forwardPolicy.classification(cell).cell());
         assertNotNull(forwardPolicy.classification(cell).nearestExtraordinaryVertex());
      }
      assertEquals(forwardPolicy.classifications(), reversePolicy.classifications());
   }

   @Test
   void policyRejectsForeignEmbeddingAndInvalidConfiguration() {
      var topology = ClosedQuadSphereTopology.create(3);
      var foreign = ClosedQuadSphereTopology.create(3, true);
      var model = SphereWorldModel.forWorldScale(1.0);
      assertThrows(IllegalArgumentException.class, () -> new SphericalIncisionPolicy(topology,
         new ClosedQuadSphereEmbedding(foreign, model),
         new SphericalIncisionPolicy.Config(0.0, 0.0)));
      assertThrows(IllegalArgumentException.class,
         () -> new SphericalIncisionPolicy.Config(-1.0, 0.0));
      assertThrows(IllegalArgumentException.class,
         () -> new SphericalIncisionPolicy.Config(0.0, Double.NaN));
      assertThrows(IllegalArgumentException.class,
         () -> new SphericalIncisionPolicy.Config(Double.MAX_VALUE, Double.MAX_VALUE));
   }

   @Test
   void enclosuresCoverCurvedEdgesAndCellInteriorsInBothEmbeddings() {
      var topology = ClosedQuadSphereTopology.create(7);
      for (var mapping : ClosedQuadSphereEmbedding.Mapping.values()) {
         var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0), mapping);
         for (var cell : topology.cells()) {
            var bound = embedding.cellEnclosure(cell);
            for (int i = 0; i <= 8; i++) {
               for (int j = 0; j <= 8; j++) {
                  var sample = embedding.cellPoint(cell, i / 8.0, j / 8.0);
                  assertTrue(SphereWorldModel.greatCircleDistanceMeters(bound.center(), sample) <= bound.radiusMeters(),
                     () -> mapping + " enclosure missed " + cell);
               }
            }
         }
      }
   }

   @Test
   void ordinaryCellsStayOutsideTheWholeTransitionBand() {
      var topology = ClosedQuadSphereTopology.create(17);
      for (var mapping : ClosedQuadSphereEmbedding.Mapping.values()) {
         var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0), mapping);
         var policy = new SphericalIncisionPolicy(topology, embedding,
            new SphericalIncisionPolicy.Config(300_000, 200_000));
         for (var cell : policy.ordinaryCells()) {
            for (int i = 0; i <= 4; i++) {
               for (int j = 0; j <= 4; j++) {
                  var sample = embedding.cellPoint(cell, i / 4.0, j / 4.0);
                  for (var vertex : policy.extraordinaryVertices()) {
                     assertTrue(SphereWorldModel.greatCircleDistanceMeters(sample, embedding.vertex(vertex)) > 500_000,
                        () -> mapping + " ordinary footprint intersects transition region: " + cell);
                  }
               }
            }
         }
      }
   }

   @Test
   void ordinaryTraversalRefusesExceptionalDestinationsAndKeepsReciprocalSeams() {
      var topology = ClosedQuadSphereTopology.create(7);
      var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0));
      var policy = new SphericalIncisionPolicy(topology, embedding, new SphericalIncisionPolicy.Config(300_000, 200_000));
      int blocked = 0, crossFace = 0;
      for (var source : topology.cells()) {
         if (policy.status(source) != SphericalIncisionPolicy.CellStatus.ORDINARY) {
            assertThrows(IllegalStateException.class, () -> policy.requireOrdinary(source));
            continue;
         }
         for (var direction : ClosedQuadSphereTopology.EdgeDirection.values()) {
            var destination = topology.neighbor(source, direction);
            if (policy.status(destination) != SphericalIncisionPolicy.CellStatus.ORDINARY) {
               blocked++;
               assertThrows(IllegalStateException.class, () -> policy.ordinaryNeighbor(source, direction));
            } else {
               assertEquals(destination, policy.ordinaryNeighbor(source, direction));
               int returns = 0;
               for (var back : ClosedQuadSphereTopology.EdgeDirection.values()) {
                  if (topology.neighbor(destination, back).equals(source)) {
                     assertEquals(source, policy.ordinaryNeighbor(destination, back));
                     returns++;
                  }
               }
               assertEquals(1, returns);
               if (source.face() != destination.face()) crossFace++;
            }
         }
      }
      assertTrue(blocked > 0);
      assertTrue(crossFace > 0);
   }

   @Test
   void statusSetsPartitionEveryCellAndAllExcludedMetricRequestsFailClearly() {
      var topology = ClosedQuadSphereTopology.create(5);
      var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0));
      var policy = new SphericalIncisionPolicy(topology, embedding,
         new SphericalIncisionPolicy.Config(Math.PI * SphereWorldModel.EARTH_RADIUS_METERS, 0));
      Set<ClosedQuadSphereTopology.CellId> seen = new HashSet<>();
      for (var status : SphericalIncisionPolicy.CellStatus.values()) {
         for (var cell : policy.cells(status)) assertTrue(seen.add(cell));
      }
      assertEquals(Set.copyOf(topology.cells()), seen);
      assertEquals(topology.cells().size(), policy.excludedCells().size());
      assertThrows(IllegalArgumentException.class, () -> embedding.measureMetrics(policy::admitsOrdinaryMaterialization));
      assertThrows(IllegalArgumentException.class,
         () -> policy.status(new ClosedQuadSphereTopology.CellId(6, 0, 0)));
      assertThrows(IllegalArgumentException.class,
         () -> embedding.cellPoint(new ClosedQuadSphereTopology.CellId(0, -1, 0), 0.5, 0.5));
   }

   @Test
   void filteredMetricsMeasureOnlyOrdinaryCellsAndTheirUniqueEdges() {
      var topology = ClosedQuadSphereTopology.create(5);
      for (var mapping : ClosedQuadSphereEmbedding.Mapping.values()) {
         var embedding = new ClosedQuadSphereEmbedding(topology, SphereWorldModel.forWorldScale(1.0), mapping);
         var policy = new SphericalIncisionPolicy(topology, embedding, new SphericalIncisionPolicy.Config(0, 0));
         var metrics = embedding.measureMetrics(policy::admitsOrdinaryMaterialization);
         Set<ClosedQuadSphereTopology.EdgeKey> edges = new HashSet<>();
         for (var cell : policy.ordinaryCells()) {
            var vertices = topology.vertices(cell);
            for (int index = 0; index < vertices.size(); index++) {
               edges.add(new ClosedQuadSphereTopology.EdgeKey(vertices.get(index), vertices.get((index + 1) % 4)));
            }
         }
         assertEquals(policy.ordinaryCells().size(), metrics.cells());
         assertEquals(edges.size(), metrics.edges());
         assertEquals(0, metrics.extraordinaryCells());
         assertEquals(Double.POSITIVE_INFINITY, metrics.extraordinaryMinEdgeScale());
         assertTrue(metrics.minEdgeScale() > 0);
      }
   }
}
