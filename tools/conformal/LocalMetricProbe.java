/* SPDX-License-Identifier: GPL-3.0-or-later. Research tools only; see COPYING. */
package com.yucareux.tellus.research;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;

/** Measures actual forward-map derivatives independently of the series derivative. */
public final class LocalMetricProbe {
   private static final double R = SphereWorldModel.EARTH_RADIUS_METERS;
   public record Sample(double scaleU, double scaleV, double angleErrorDegrees, double aspect) { }
   public static Sample measure(ConformalCubedSphere map, int face, double u, double v, double stepMeters) {
      double h = stepMeters / (Math.PI * R / 4);
      double[] center = unit(map.point(face, u, v));
      double[] du = derivative(map, face, u, v, h, true, center);
      double[] dv = derivative(map, face, u, v, h, false, center);
      double lu = length(du), lv = length(dv);
      double cosine = Math.max(-1, Math.min(1, dot(du, dv) / (lu * lv)));
      return new Sample(lu * 4 / Math.PI, lv * 4 / Math.PI,
         Math.toDegrees(Math.abs(Math.acos(cosine) - Math.PI / 2)), Math.max(lu, lv) / Math.min(lu, lv));
   }
   /** Unit-sphere coordinate derivative, with radial numerical error removed. */
   public static double[] tangent(ConformalCubedSphere map,int face,double u,double v,double stepMeters,boolean first) {
      return derivative(map,face,u,v,stepMeters/(Math.PI*R/4),first,unit(map.point(face,u,v)));
   }
   private static double[] derivative(ConformalCubedSphere map, int face, double u, double v,
      double h, boolean first, double[] center) {
      double coordinate = first ? u : v;
      double[] result = new double[3];
      if (coordinate - h >= -1 && coordinate + h <= 1) {
         double[] a = unit(map.point(face, first ? u-h : u, first ? v : v-h));
         double[] b = unit(map.point(face, first ? u+h : u, first ? v : v+h));
         for (int i=0; i<3; i++) result[i] = (b[i]-a[i]) / (2*h);
      } else {
         double direction = coordinate > 0 ? -1 : 1;
         double[] a = unit(map.point(face, first ? u+direction*h : u, first ? v : v+direction*h));
         double[] b = unit(map.point(face, first ? u+direction*2*h : u, first ? v : v+direction*2*h));
         for (int i=0; i<3; i++) result[i] = direction * (-3*center[i]+4*a[i]-b[i]) / (2*h);
      }
      double radial = dot(result, center);
      for (int i=0; i<3; i++) result[i] -= radial*center[i];
      return result;
   }
   public static double[] unit(SphereWorldModel.GeoPoint p) {
      double lat=Math.toRadians(p.latitude()), lon=Math.toRadians(p.longitude());
      return new double[]{Math.cos(lat)*Math.cos(lon), Math.sin(lat), Math.cos(lat)*Math.sin(lon)};
   }
   public static double distance(SphereWorldModel.GeoPoint a, SphereWorldModel.GeoPoint b) {
      double[] x=unit(a), y=unit(b);
      double[] cross={x[1]*y[2]-x[2]*y[1], x[2]*y[0]-x[0]*y[2], x[0]*y[1]-x[1]*y[0]};
      return R*Math.atan2(length(cross),dot(x,y));
   }
   private static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
   private static double length(double[] a) { return Math.sqrt(dot(a,a)); }
}
