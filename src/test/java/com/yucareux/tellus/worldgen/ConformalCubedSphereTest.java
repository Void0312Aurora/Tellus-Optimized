package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.ConformalCubedSphere;
import com.yucareux.tellus.worldgen.global.SphereSurfaceMapping;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConformalCubedSphereTest {
   private static final double H = 1.0E-5;

   @Test
   void forwardAndInverseCloseOnEveryFaceAwayFromCorners() {
      var mapping = new ConformalCubedSphere();
      for (int face = 0; face < SphereWorldModel.FACE_COUNT; face++) {
         for (double u : new double[]{-0.8, -0.2, 0.3, 0.85}) {
            for (double v : new double[]{-0.75, -0.1, 0.4, 0.82}) {
               var point = mapping.point(face, u, v);
               var recovered = mapping.inverseOnFace(face, point);
               assertEquals(u, recovered.u(), 2.0E-11);
               assertEquals(v, recovered.v(), 2.0E-11);
            }
         }
      }
   }

   @Test
   void localMetricIsConformalOnRegularFaceSamples() {
      var mapping = new ConformalCubedSphere();
      for (double u : new double[]{-0.7, -0.25, 0.2, 0.7}) {
         for (double v : new double[]{-0.65, -0.15, 0.35, 0.68}) {
            Vector center = Vector.from(mapping.point(0, u, v));
            Vector du = tangent(center, Vector.from(mapping.point(0, u + H, v))
               .subtract(Vector.from(mapping.point(0, u - H, v))).scale(1.0 / (2.0 * H)));
            Vector dv = tangent(center, Vector.from(mapping.point(0, u, v + H))
               .subtract(Vector.from(mapping.point(0, u, v - H))).scale(1.0 / (2.0 * H)));
            double duLength = du.length(), dvLength = dv.length();
            assertTrue(Math.abs(du.dot(dv)) <= 2.0E-8 * duLength * dvLength,
               () -> "non-orthogonal local basis at " + u + "," + v);
            assertTrue(Math.abs(duLength / dvLength - 1.0) <= 2.0E-7,
               () -> "unequal local scales at " + u + "," + v);
         }
      }
   }

   private static Vector tangent(Vector center, Vector derivative) {
      return derivative.subtract(center.scale(center.dot(derivative)));
   }

   private record Vector(double x, double y, double z) {
      private static Vector from(SphereWorldModel.GeoPoint point) {
         double lat = Math.toRadians(point.latitude()), lon = Math.toRadians(point.longitude());
         double cos = Math.cos(lat);
         return new Vector(cos * Math.cos(lon), Math.sin(lat), cos * Math.sin(lon));
      }
      private Vector subtract(Vector other) { return new Vector(x - other.x, y - other.y, z - other.z); }
      private Vector scale(double factor) { return new Vector(x * factor, y * factor, z * factor); }
      private double dot(Vector other) { return x * other.x + y * other.y + z * other.z; }
      private double length() { return Math.sqrt(dot(this)); }
   }
}
