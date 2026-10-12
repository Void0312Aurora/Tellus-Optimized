package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphericalLatticePersistence;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SphericalLatticePersistenceTest {
   private static final double EARTH_R = SphereWorldModel.EARTH_RADIUS_METERS;
   private static final SphericalLatticePrototype.GridVersion GRID =
      new SphericalLatticePrototype.GridVersion("tellus:earth", 1);

   @Test
   void canonicalEncodingIsIndependentOfInputOrder() {
      var atlas = atlas();
      var points = ownerPoints();
      var edits = edits(atlas);
      var forward = SphericalLatticePersistence.capture(atlas, points, edits);
      var patches = new ArrayList<>(forward.patches());
      var links = new ArrayList<>(forward.links());
      var owners = new ArrayList<>(forward.owners());
      var reversedEdits = new ArrayList<>(forward.edits());
      Collections.reverse(patches);
      Collections.reverse(links);
      Collections.reverse(owners);
      Collections.reverse(reversedEdits);
      var reversed = new SphericalLatticePersistence.Snapshot(forward.grid(), patches, links, owners, reversedEdits);

      assertArrayEquals(SphericalLatticePersistence.encode(forward), SphericalLatticePersistence.encode(reversed));
   }

   @Test
   void roundTripPreservesOwnersIdsAndCrossSeamEdits() throws IOException {
      var atlas = atlas();
      var snapshot = SphericalLatticePersistence.capture(atlas, ownerPoints(), edits(atlas));
      var restored = SphericalLatticePersistence.decode(SphericalLatticePersistence.encode(snapshot));
      var restoredAtlas = SphericalLatticePersistence.restoreAtlas(restored);

      assertEquals(snapshot, restored);
      assertEquals(snapshot.edits(), restored.edits());
      for (var owner : snapshot.owners()) assertEquals(owner.owner(), restoredAtlas.owner(owner.point()));
      var source = atlas.patch(new SphericalLatticePrototype.PatchKey(0, 0, 0)).cell(3, 2, 17);
      var destination = restoredAtlas.neighbor(source, 1, 0);
      assertEquals(new SphericalLatticePrototype.PatchKey(0, 1, 0), destination.patch());
      assertEquals(source, restoredAtlas.neighbor(destination, -1, 0));
   }

   @Test
   void decoderRejectsTrailingOrCorruptedBytes() {
      var snapshot = SphericalLatticePersistence.capture(atlas(), ownerPoints(), edits(atlas()));
      byte[] encoded = SphericalLatticePersistence.encode(snapshot);
      byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
      trailing[trailing.length - 1] = 7;
      assertThrows(IOException.class, () -> SphericalLatticePersistence.decode(trailing));
      byte[] corrupted = encoded.clone();
      corrupted[0] = 0;
      assertThrows(IOException.class, () -> SphericalLatticePersistence.decode(corrupted));
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

   private static List<SphereWorldModel.GeoPoint> ownerPoints() {
      return List.of(point(-24_000), point(-1_000), point(0), point(1_000), point(24_000));
   }

   private static List<SphericalLatticePersistence.CellEdit> edits(SphericalLatticePrototype.Atlas atlas) {
      var west = atlas.patch(new SphericalLatticePrototype.PatchKey(0, 0, 0));
      var east = atlas.patch(new SphericalLatticePrototype.PatchKey(0, 1, 0));
      return List.of(new SphericalLatticePersistence.CellEdit(east.cell(0, 2, 17), 91),
         new SphericalLatticePersistence.CellEdit(west.cell(3, 2, 17), 42));
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
