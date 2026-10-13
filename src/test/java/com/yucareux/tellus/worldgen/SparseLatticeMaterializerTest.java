package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SparseLatticeMaterializer;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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

   @Test
   void editedStateSurvivesEvictionAndFreshMaterializerRestore() {
      var first = new SparseLatticeMaterializer(atlas(), SparseLatticeMaterializerTest::generate);
      var id = first.atlas().patch(new SphericalLatticePrototype.PatchKey(0, 1, 0)).cell(1, 2, -17);

      assertEquals(42, first.setState(id, 42).state());
      assertTrue(first.evict(id));
      assertEquals(42, first.request(id).state());

      var restored = new SparseLatticeMaterializer(atlas(), SparseLatticeMaterializerTest::generate);
      restored.restoreEdits(first.edits());
      restored.evictAll();
      assertEquals(42, restored.request(id).state());
      assertEquals(first.edits(), restored.edits());
   }

   @Test
   void chunkAndSectionIdentityUsesStableFloorDivisionForNegativeRadialCells() {
      var materializer = new SparseLatticeMaterializer(
         new SphericalLatticePrototype.Atlas(List.of(patch(1, 25_000, 64)), List.of()),
         SparseLatticeMaterializerTest::generate);
      var id = materializer.atlas().patch(new SphericalLatticePrototype.PatchKey(0, 1, 0)).cell(33, 31, -17);

      var chunk = materializer.chunkOf(id, 16);
      assertEquals(2, chunk.x());
      assertEquals(1, chunk.z());
      var section = materializer.sectionOf(id, 16, 16);
      assertEquals(-2, section.sectionY());
      assertEquals(chunk, section.chunk());
   }

   @Test
   void concurrentFirstRequestsPublishOneCanonicalResult() throws Exception {
      AtomicInteger generated = new AtomicInteger();
      var materializer = new SparseLatticeMaterializer(atlas(), id -> {
         generated.incrementAndGet();
         Thread.yield();
         return new SparseLatticeMaterializer.CellData(id, 7);
      });
      var id = materializer.atlas().patch(new SphericalLatticePrototype.PatchKey(0, 1, 0)).cell(1, 2, 3);
      int workers = 8;
      ExecutorService executor = Executors.newFixedThreadPool(workers);
      CountDownLatch start = new CountDownLatch(1);
      try {
         List<Callable<SparseLatticeMaterializer.CellData>> calls = new ArrayList<>();
         for (int i = 0; i < workers; i++) {
            calls.add(() -> {
               start.await();
               return materializer.request(id);
            });
         }
         List<Future<SparseLatticeMaterializer.CellData>> futures = new ArrayList<>();
         for (Callable<SparseLatticeMaterializer.CellData> call : calls) futures.add(executor.submit(call));
         start.countDown();
         for (Future<SparseLatticeMaterializer.CellData> future : futures) {
            assertEquals(new SparseLatticeMaterializer.CellData(id, 7), future.get());
         }
         assertEquals(1, generated.get());
         assertEquals(1, materializer.size());
      } finally {
         executor.shutdownNow();
      }
   }

   @Test
   void restoreEditsValidatesTheWholeBatchBeforeMutation() {
      var materializer = new SparseLatticeMaterializer(overlappingAtlas(), SparseLatticeMaterializerTest::generate);
      var valid = materializer.atlas().patch(new SphericalLatticePrototype.PatchKey(0, 0, 0)).cell(1, 1, 0);
      var ghost = materializer.atlas().patch(new SphericalLatticePrototype.PatchKey(0, 1, 0)).cell(1, 1, 0);

      assertThrows(IllegalArgumentException.class, () -> materializer.restoreEdits(List.of(
         new com.yucareux.tellus.worldgen.global.SphericalLatticePersistence.CellEdit(valid, 11),
         new com.yucareux.tellus.worldgen.global.SphericalLatticePersistence.CellEdit(ghost, 12))));
      assertTrue(materializer.edits().isEmpty());
      assertEquals(generate(valid).state(), materializer.request(valid).state());
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
      return patch(x, east, 4);
   }

   private static SphericalLatticePrototype.PatchDescriptor patch(long x, double east, int cellsPerSide) {
      return new SphericalLatticePrototype.PatchDescriptor(GRID,
         new SphericalLatticePrototype.PatchKey(0, x, 0),
         new SphereWorldModel.GeoPoint(0, Math.toDegrees(2.0 * Math.atan(east / (2.0 * EARTH_R))), 0),
         50_000, cellsPerSide, SphericalLatticePrototype.ChartKind.STEREOGRAPHIC);
   }

   private static SphereWorldModel.GeoPoint point(double eastMeters) {
      return new SphereWorldModel.GeoPoint(0, Math.toDegrees(eastMeters / EARTH_R), 0);
   }
}
