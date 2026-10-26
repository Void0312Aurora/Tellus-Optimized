package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SparseLatticeMaterializer;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparseLatticeMaterializerTest {
   private static final double EARTH_R = SphereWorldModel.EARTH_RADIUS_METERS;
   private static final SphericalLatticePrototype.GridVersion GRID =
      new SphericalLatticePrototype.GridVersion("tellus:earth", 1);

   @Test
   void globalAtlasAndSparseMaterializationHaveDifferentCardinalities() {
      var materializer = new SparseLatticeMaterializer(atlas(), SparseLatticeMaterializerTest::generate);
      materializer.request(point(-24_000), 3);
      materializer.request(point(24_000), 3);

      assertEquals(2, materializer.size());
      assertEquals(32, materializer.atlas().patches().stream()
         .mapToInt(patch -> patch.cellsPerSide() * patch.cellsPerSide()).sum());
      assertTrue(materializer.size() < 32);
      assertFalse(materializer.isMaterialized(materializer.atlas().patch(
         new SphericalLatticePrototype.PatchKey(0, 0, 0)).cell(1, 1, 3)));
   }

   @Test
   void requestOrderDoesNotChangeSparseIdentityOrGeneratedState() {
      var first = new SparseLatticeMaterializer(atlas(), SparseLatticeMaterializerTest::generate);
      var second = new SparseLatticeMaterializer(atlas(), SparseLatticeMaterializerTest::generate);
      var west = first.atlas().patch(new SphericalLatticePrototype.PatchKey(0, 0, 0)).cell(3, 2, 7);
      var east = first.atlas().patch(new SphericalLatticePrototype.PatchKey(0, 1, 0)).cell(0, 2, 7);
      first.request(west);
      first.request(east);
      second.request(east);
      second.request(west);

      assertEquals(first.materializedCells(), second.materializedCells());
   }

   @Test
   void neighborRequestMaterializesBothSidesOfASeamWithVerticalIdentity() {
      var materializer = new SparseLatticeMaterializer(atlas(), SparseLatticeMaterializerTest::generate);
      var west = materializer.atlas().patch(new SphericalLatticePrototype.PatchKey(0, 0, 0)).cell(3, 2, 17);
      var east = materializer.requestNeighbor(west, 1, 0).id();

      assertEquals(new SphericalLatticePrototype.PatchKey(0, 1, 0), east.patch());
      assertEquals(0, east.i());
      assertEquals(west.j(), east.j());
      assertEquals(west.k(), east.k());
      assertEquals(west, materializer.atlas().neighbor(east, -1, 0));
      assertEquals(1, materializer.size());
      materializer.request(west);
      assertEquals(2, materializer.size());
   }

   @Test
   void directRequestsRejectGhostCellsInAnOverlappingPatch() {
      var materializer = new SparseLatticeMaterializer(overlappingAtlas(), SparseLatticeMaterializerTest::generate);
      var ghost = materializer.atlas().patch(new SphericalLatticePrototype.PatchKey(0, 1, 0)).cell(1, 1, 0);

      assertFalse(materializer.atlas().isCanonical(ghost));
      assertThrows(IllegalArgumentException.class, () -> materializer.request(ghost));
   }

   @Test
   void directRequestsRejectPartiallyOwnedFootprints() {
      var lower = patch(0, 20_000);
      var higher = patch(1, 0);
      var atlas = new SphericalLatticePrototype.Atlas(List.of(lower, higher), List.of());
      var materializer = new SparseLatticeMaterializer(atlas, SparseLatticeMaterializerTest::generate);
      var partial = higher.cell(1, 1, 0);

      assertEquals(SphericalLatticePrototype.FootprintOwnership.PARTIAL,
         atlas.footprint(partial).ownership());
      assertThrows(IllegalArgumentException.class, () -> materializer.request(partial));
   }

   private static SparseLatticeMaterializer.CellData generate(SphericalLatticePrototype.BlockId id) {
      int state = Math.floorMod((int)(id.patch().x() * 31 + id.patch().y() * 17
         + id.i() * 7 + id.j() * 3 + id.k()), 256);
      return new SparseLatticeMaterializer.CellData(id, state);
   }

   private static SphericalLatticePrototype.Atlas atlas() {
      var west = patch(0, -25_000);
      var east = patch(1, 25_000);
      return new SphericalLatticePrototype.Atlas(List.of(west, east), List.of(
         new SphericalLatticePrototype.BoundaryLink(west.key(), SphericalLatticePrototype.Edge.EAST,
            east.key(), SphericalLatticePrototype.Edge.WEST, false),
         new SphericalLatticePrototype.BoundaryLink(east.key(), SphericalLatticePrototype.Edge.WEST,
            west.key(), SphericalLatticePrototype.Edge.EAST, false)));
   }

   private static SphericalLatticePrototype.Atlas overlappingAtlas() {
      var first = patch(0, 0);
      var second = patch(1, 0);
      return new SphericalLatticePrototype.Atlas(List.of(first, second), List.of());
   }

   private static SphericalLatticePrototype.PatchDescriptor patch(long x, double east) {
      return new SphericalLatticePrototype.PatchDescriptor(GRID,
         new SphericalLatticePrototype.PatchKey(0, x, 0),
         new SphereWorldModel.GeoPoint(0, Math.toDegrees(2.0 * Math.atan(east / (2.0 * EARTH_R))), 0),
         50_000, 4, SphericalLatticePrototype.ChartKind.STEREOGRAPHIC);
   }

   private static SphereWorldModel.GeoPoint point(double eastMeters) {
      return new SphereWorldModel.GeoPoint(0, Math.toDegrees(eastMeters / EARTH_R), 0);
   }
}
