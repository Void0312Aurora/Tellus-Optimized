package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubeNetSphericalCaveFieldTest {
   private static CubeNetSphericalCaveField field;

   static CubeNetSphericalCaveField field(long seed) { return new CubeNetSphericalCaveField(seed); }

   static { field = field(1234L); }

   @Test
   void allTwentyFourDirectedSeamsShareTheSameVolumeAndApproachContinuously() {
      for (double scale : new double[]{1.0, 1000.0}) {
         var model = SphereWorldModel.forWorldScale(scale);
         var layout = CubeNetLayout.standard(model.config().faceBlocks());
         for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
            for (double fraction : new double[]{0.15, 0.5, 0.85}) {
               double edge = layout.faceSize() * fraction;
               var a = edgePoint(layout, face, side, edge, 0.0);
               var b = layout.mapAcross(face, side, edge, 0.0);
               var aNear = edgePoint(layout, face, side, edge, 1.0E-4);
               var bNear = layout.mapAcross(face, side, edge, 1.0E-4);
               for (int height : new int[]{-128, 0, 100}) {
                  var p = position(model, a, height);
                  var q = position(model, b, height);
                  String label = scale + " " + face + " " + side + " " + fraction;
                  assertEquals(field.caveSignal(p), field.caveSignal(q), 1.0E-7, label);
                  assertEquals(field.rockFeature(p, 40, true, true),
                     field.rockFeature(q, 40, true, true), label);
                  assertEquals(field.cavity(p, height, 40, height + 64, -512),
                     field.cavity(q, height, 40, height + 64, -512), label);
                  assertEquals(field.caveSignal(position(model, aNear, height)),
                     field.caveSignal(position(model, bNear, height)), 1.0E-3, label);
               }
            }
         }
      }
   }

   @Test
   void physicalHeightDoesNotDependOnSurfaceElevationOrLongitudeWrap() {
      var a = field.column(new SphereWorldModel.GeoPoint(30, -180, 0), 1.0).atHeight(-50);
      var b = field.column(new SphereWorldModel.GeoPoint(30, 180, 4000), 1.0).atHeight(-50);
      assertEquals(a, b);
      var c = field.column(new SphereWorldModel.GeoPoint(30, 180, 0), 1.0).atHeight(-49);
      assertEquals(1.0, Math.sqrt(Math.pow(a.x()-c.x(), 2) + Math.pow(a.y()-c.y(), 2)
         + Math.pow(a.z()-c.z(), 2)), 1.0E-8);
   }

   @Test
   void producesDryWetLavaCavesAndCoherentOreHostsWithDeterministicSeeds() {
      var cavities = new EnumMap<CubeNetSphericalCaveField.Cavity, Integer>(CubeNetSphericalCaveField.Cavity.class);
      var rocks = new EnumMap<CubeNetSphericalCaveField.Feature, Integer>(CubeNetSphericalCaveField.Feature.class);
      var repeated = field(1234L);
      var different = field(4321L);
      boolean changed = false;
      for (int lat = -75; lat <= 75; lat += 15) for (int lon = -165; lon <= 165; lon += 15) {
         var point = new SphereWorldModel.GeoPoint(lat, lon, 0);
         var column = field.column(point, 1000.0);
         for (int height = -126; height <= 64; height += 5) {
            var p = column.atHeight(height);
            var cavity = field.cavity(p, height, 100, height + 64, -64);
            cavities.merge(cavity, 1, Integer::sum);
            rocks.merge(field.rockFeature(p, 100, true, true), 1, Integer::sum);
            assertEquals(field.caveSignal(p), repeated.caveSignal(p));
            changed |= field.caveSignal(p) != different.caveSignal(p);
            assertEquals(CubeNetSphericalCaveField.Feature.NONE, field.rockFeature(p, 100, false, false));
            if (height > 0) assertNotEquals(CubeNetSphericalCaveField.Cavity.WATER, cavity);
         }
      }
      for (var type : CubeNetSphericalCaveField.Cavity.values()) assertTrue(cavities.getOrDefault(type, 0) > 0, cavities.toString());
      for (var type : new CubeNetSphericalCaveField.Feature[]{CubeNetSphericalCaveField.Feature.COPPER_ORE,
         CubeNetSphericalCaveField.Feature.DEEPSLATE_IRON_ORE, CubeNetSphericalCaveField.Feature.GRANITE,
         CubeNetSphericalCaveField.Feature.TUFF}) assertTrue(rocks.getOrDefault(type, 0) > 0, rocks.toString());
      assertTrue(changed);
   }

   private static CubeNetSphericalCaveField.Position position(
      SphereWorldModel model, CubeNetLayout.FacePoint point, int height
   ) {
      return field.column(model.exactSurfacePoint(point.face().index(), point.u(), point.v()),
         model.config().worldScale()).atHeight(height);
   }

   private static CubeNetLayout.FacePoint edgePoint(CubeNetLayout layout, CubeNetLayout.Face face,
      CubeNetLayout.Side side, double edge, double inset) {
      return new CubeNetLayout.FacePoint(face, switch(side) {
         case WEST -> inset; case EAST -> layout.faceSize()-inset; default -> edge;
      }, switch(side) {
         case NORTH -> inset; case SOUTH -> layout.faceSize()-inset; default -> edge;
      });
   }
}
