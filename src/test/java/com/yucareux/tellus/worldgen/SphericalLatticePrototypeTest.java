package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SphericalLatticePrototypeTest {
   private static final double EARTH_R = SphereWorldModel.EARTH_RADIUS_METERS;
   private static final SphericalLatticePrototype.GridVersion GRID =
      new SphericalLatticePrototype.GridVersion("tellus:earth", 1);

   @Test
   void isolatedChartsHaveQuantifiedBoundedMetricError() {
      for (SphericalLatticePrototype.ChartKind kind : SphericalLatticePrototype.ChartKind.values()) {
         var patch = new SphericalLatticePrototype.PatchDescriptor(GRID,
            new SphericalLatticePrototype.PatchKey(0, 0, 0),
            new SphereWorldModel.GeoPoint(0, 0, 0), 50_000, 1024, kind);
         var report = SphericalLatticePrototype.measurePatch(patch, 17);
         assertTrue(report.minScale() > 0.99998, () -> kind + " min scale=" + report.minScale());
         assertTrue(report.maxScale() < 1.00001, () -> kind + " max scale=" + report.maxScale());
         assertTrue(report.maxCondition() < 1.00002, () -> kind + " condition=" + report.maxCondition());
         assertTrue(report.maxAngleErrorRadians() < 1.0E-5, () -> kind + " angle=" + report.maxAngleErrorRadians());
      }
   }

   @Test
   void currentCubedSphereBaselineExhibitsBoundedButNonzeroMetricDistortion() {
      var report = SphericalLatticePrototype.measureCubedSphere(17);
      assertTrue(report.minScale() < 0.68, () -> "baseline min scale=" + report.minScale());
      assertTrue(report.maxScale() > 1.14, () -> "baseline max scale=" + report.maxScale());
      assertTrue(report.maxCondition() > 1.70, () -> "baseline condition=" + report.maxCondition());
      assertTrue(report.minAreaScale() < 0.72, () -> "baseline min area=" + report.minAreaScale());
      assertTrue(report.maxAngleErrorRadians() > 0.10, () -> "baseline angle error=" + report.maxAngleErrorRadians());
   }

   @Test
   void stereographicMetricMatchesTheRFCClosedFormAtTheSquareCorner() {
      var patch = new SphericalLatticePrototype.PatchDescriptor(GRID,
         new SphericalLatticePrototype.PatchKey(0, 0, 0), new SphereWorldModel.GeoPoint(0, 0, 0),
         50_000, 1024, SphericalLatticePrototype.ChartKind.STEREOGRAPHIC);
      var report = SphericalLatticePrototype.measurePatch(patch, 3);
      double expected = patch.sideMeters() * patch.sideMeters()
         / (8.0 * EARTH_R * EARTH_R + patch.sideMeters() * patch.sideMeters());
      assertEquals(expected, report.maxRelativeScaleError(), 2.0E-7);
      assertEquals(7.7E-6, expected, 2.0E-7);
   }

   @Test
   void ownerIsStableWhenPatchDescriptorsArriveInDifferentOrders() {
      var west = patch(0, -25_000, 0);
      var east = patch(1, 25_000, 0);
      var forward = new SphericalLatticePrototype.Atlas(List.of(west, east), links());
      var reversed = new SphericalLatticePrototype.Atlas(List.of(east, west), links());
      for (double eastMeters : new double[]{-24_000, -1_000, 0.0, 1_000, 24_000}) {
         var point = new SphereWorldModel.GeoPoint(0, Math.toDegrees(eastMeters / EARTH_R), 0);
         assertEquals(forward.owner(point), reversed.owner(point), () -> "eastMeters=" + eastMeters);
      }
      var seam = new SphereWorldModel.GeoPoint(0, 0, 0);
      assertEquals(west.key(), forward.owner(seam).patch()); // lowest-key tie-break is canonical
      assertNotEquals(forward.owner(new SphereWorldModel.GeoPoint(0, Math.toDegrees(30_000 / EARTH_R), 0)).patch(), west.key());
   }

   @Test
   void linkedPatchNeighborsAreReciprocalAndPersistVerticalIdentity() {
      var atlas = new SphericalLatticePrototype.Atlas(List.of(patch(0, -25_000, 0), patch(1, 25_000, 0)), links());
      var source = atlas.patch(new SphericalLatticePrototype.PatchKey(0, 0, 0)).cell(1023, 777, 41);
      var destination = atlas.neighbor(source, 1, 0);
      assertEquals(new SphericalLatticePrototype.PatchKey(0, 1, 0), destination.patch());
      assertEquals(0, destination.i());
      assertEquals(source.j(), destination.j());
      assertEquals(41, destination.k());
      assertEquals(source, atlas.neighbor(destination, -1, 0));
   }

   @Test
   void missingOrNonreciprocalBoundariesFailBeforeAnyMaterialization() {
      var west = patch(0, -25_000, 0);
      var east = patch(1, 25_000, 0);
      var oneWay = List.of(new SphericalLatticePrototype.BoundaryLink(
         west.key(), SphericalLatticePrototype.Edge.EAST, east.key(), SphericalLatticePrototype.Edge.WEST, false));
      assertThrows(IllegalArgumentException.class, () -> new SphericalLatticePrototype.Atlas(List.of(west, east), oneWay));
      assertThrows(IllegalStateException.class, () -> new SphericalLatticePrototype.Atlas(
         List.of(west, east), links()).neighbor(patch(0, -25_000, 0).cell(10, 1023, 0), 0, 1));
   }

   @Test
   void coarseLocatorFiltersDistantPatchesAndHandlesTheDateline() {
      List<SphericalLatticePrototype.PatchDescriptor> descriptors = new ArrayList<>();
      for (int index = 0; index <= 180; index++) {
         double longitude = -180.0 + index * 2.0;
         descriptors.add(patchAt(index, longitude, 0.0, 10_000));
      }
      var dateline = patchAt(1000, 179.9, 0.0, 50_000);
      descriptors.add(dateline);
      var atlas = new SphericalLatticePrototype.Atlas(descriptors, List.of());

      var center = new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0);
      assertTrue(atlas.candidatePatchCount(center) < descriptors.size() / 10);
      assertEquals(new SphericalLatticePrototype.PatchKey(0, 90, 0), atlas.owner(center).patch());
      assertEquals(dateline.key(), atlas.owner(new SphereWorldModel.GeoPoint(0.0, -179.9, 0.0)).patch());
      assertThrows(IllegalArgumentException.class,
         () -> atlas.owner(new SphereWorldModel.GeoPoint(0.0, 90.5, 0.0)));
   }

   private static SphericalLatticePrototype.PatchDescriptor patch(long x, double east, double north) {
      return patchAt(x, Math.toDegrees(2.0 * Math.atan(east / (2.0 * EARTH_R))),
         Math.toDegrees(2.0 * Math.atan(north / (2.0 * EARTH_R))), 50_000);
   }

   private static SphericalLatticePrototype.PatchDescriptor patchAt(long x, double longitude,
      double latitude, double sideMeters) {
      return new SphericalLatticePrototype.PatchDescriptor(GRID,
         new SphericalLatticePrototype.PatchKey(0, x, 0),
         new SphereWorldModel.GeoPoint(latitude, longitude, 0), sideMeters, 1024,
         SphericalLatticePrototype.ChartKind.STEREOGRAPHIC);
   }

   private static List<SphericalLatticePrototype.BoundaryLink> links() {
      return List.of(
         new SphericalLatticePrototype.BoundaryLink(new SphericalLatticePrototype.PatchKey(0, 0, 0),
            SphericalLatticePrototype.Edge.EAST, new SphericalLatticePrototype.PatchKey(0, 1, 0),
            SphericalLatticePrototype.Edge.WEST, false),
         new SphericalLatticePrototype.BoundaryLink(new SphericalLatticePrototype.PatchKey(0, 1, 0),
            SphericalLatticePrototype.Edge.WEST, new SphericalLatticePrototype.PatchKey(0, 0, 0),
            SphericalLatticePrototype.Edge.EAST, false));
   }
}
