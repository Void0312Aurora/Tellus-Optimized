/* SPDX-License-Identifier: GPL-3.0-or-later. Research tools only; see COPYING. */
package com.yucareux.tellus.research;

import com.yucareux.tellus.worldgen.global.*;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded globe meshes plus sampled metre derivatives; no global metre mesh allocation. */
public final class ConformalCubeStudy {
   private static final double R=SphereWorldModel.EARTH_RADIUS_METERS;
   private static final ConformalCubedSphere MAP=new ConformalCubedSphere();
   private static final double[] CAPS={0,100,300,360};
   public static void main(String[] args) throws Exception {
      Locale.setDefault(Locale.ROOT);
      Path directory=Path.of(args.length==0?"build/rfc-conformal/results":args[0]);
      Files.createDirectories(directory);
      try (var csv=writer(directory.resolve("conformal-cap-sweep.csv"))) { meshStudy(csv); }
      try (var csv=writer(directory.resolve("conformal-local-metrics.csv"))) { localStudy(csv); }
      Files.writeString(directory.resolve("conformal-grid-version.txt"),
         "map_id="+MAP.id()+"\nsource_commit=02295ffb6decf764b7bf2ce518c412975ae68565\n"
         +"source_sha256=99cdf7d5caf75ad1712c31e6de9f99e89643ba523e03b56c5e191b7ffaf8862b\n"
         +"coefficient_count=30\narithmetic=IEEE754-binary64\nearth_radius_m="+R+"\n"
         +"reference_m_per_uv="+(Math.PI*R/4)+"\norientation=unrotated-six-face-v1\n"
         +"enclosure=octant-derivative-bound-v1-plus-0.1mm\ntransition_width_km=50\n"
         +"subdivisions=64,128\nlocal_grid=65x65-per-face-plus-181-rays-per-face-at-cap-plus-band-plus-1m\n"
         +"finite_difference_steps_m=1,0.5\ninverse_sample_stride=64\n"
         +"jvm="+System.getProperty("java.version")+"\n",StandardCharsets.UTF_8);
   }
   private static PrintWriter writer(Path path) throws Exception {
      return new PrintWriter(Files.newBufferedWriter(path,StandardCharsets.UTF_8));
   }
   private static void meshStudy(PrintWriter csv) {
      csv.println("mapping,subdivision,nominal_edge_km,cap_radius_km,transition_width_km,ordinary_cells,transition_cells,excluded_cells,ordinary_polygon_fraction,transition_polygon_fraction,excluded_polygon_fraction,ideal_eight_cap_fraction,min_ordinary_edge_scale,max_ordinary_edge_scale,max_ordinary_angle_error_degrees,min_ordinary_area_scale,max_ordinary_area_scale,max_enclosure_radius_km");
      for (int n:new int[]{64,128}) {
         var topology=ClosedQuadSphereTopology.create(n);
         var embedding=new ClosedQuadSphereEmbedding(topology,SphereWorldModel.forWorldScale(1),MAP);
         double[] polygonAreas=topology.cells().stream().mapToDouble(embedding::cellAreaMetersSquared).toArray();
         for (double cap:CAPS) {
            var policy=new SphericalIncisionPolicy(topology,embedding,new SphericalIncisionPolicy.Config(cap*1000,50000));
            int[] counts=new int[3]; double[] areas=new double[3]; double maxRadius=0;
            for (int i=0;i<topology.cells().size();i++) {
               var c=policy.classification(topology.cells().get(i));
               counts[c.status().ordinal()]++; areas[c.status().ordinal()]+=polygonAreas[i];
               maxRadius=Math.max(maxRadius,c.cellRadiusMeters());
            }
            var metrics=embedding.measureMetrics(policy::admitsOrdinaryMaterialization);
            double sphereArea=4*Math.PI*R*R;
            csv.printf("%s,%d,%.6f,%.0f,50,%d,%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.6f%n",
               MAP.id(),n,metrics.nominalEdgeMeters()/1000,cap,counts[0],counts[1],counts[2],
               areas[0]/sphereArea,areas[1]/sphereArea,areas[2]/sphereArea,8*Math.pow(Math.sin(cap*1000/R/2),2),
               metrics.minEdgeScale(),metrics.maxEdgeScale(),Math.toDegrees(metrics.maxAngleErrorRadians()),
               metrics.minAreaScale(),metrics.maxAreaScale(),maxRadius/1000);
            csv.flush();
            System.out.printf("mesh n=%d cap=%.0f km ordinary=%.6f%% angle=%.6f degrees%n",n,cap,areas[0]/sphereArea*100,Math.toDegrees(metrics.maxAngleErrorRadians()));
         }
      }
   }
   private static void localStudy(PrintWriter csv) {
      csv.println("mapping,cap_radius_km,transition_width_km,step_m,samples,min_local_scale,max_local_scale,max_angle_error_degrees,max_aspect,max_relative_analytic_scale_error,inverse_samples,max_inverse_error_m,min_sample_corner_clearance_km");
      for(double cap:CAPS) {
         List<Coordinate> points=new ArrayList<>();
         double limit=cap*1000+50000;
         for(int face=0;face<6;face++) {
            for(int i=0;i<=64;i++) for(int j=0;j<=64;j++) {
               double u=i/32.0-1,v=j/32.0-1;
               if(clearance(MAP.point(face,u,v))>limit) points.add(new Coordinate(face,u,v));
            }
            // Four corners are symmetry-equivalent; include all six global face bases.
            for(int i=0;i<=180;i++) {
               double theta=i*Math.PI/360;
               double low=0,high=1;
               var corner=MAP.point(face,1,1);
               for(int iteration=0;iteration<55;iteration++) {
                  double mid=(low+high)/2;
                  double distance=LocalMetricProbe.distance(corner,MAP.point(face,1-mid*Math.cos(theta),1-mid*Math.sin(theta)));
                  if(distance<limit+1) low=mid; else high=mid;
               }
               points.add(new Coordinate(face,1-high*Math.cos(theta),1-high*Math.sin(theta)));
            }
         }
         double minClearance=Double.POSITIVE_INFINITY,maxInverse=0; int inverseSamples=0;
         for(int i=0;i<points.size();i++) {
            var c=points.get(i); var point=MAP.point(c.face,c.u,c.v);
            minClearance=Math.min(minClearance,clearance(point));
            if(i%64==0) {
               var inverse=MAP.inverse(point);
               maxInverse=Math.max(maxInverse,LocalMetricProbe.distance(point,MAP.point(inverse.face(),inverse.u(),inverse.v())));
               inverseSamples++;
            }
         }
         for(double step:new double[]{1,.5}) {
            double min=Double.POSITIVE_INFINITY,max=0,maxAngle=0,maxAspect=1,maxRelative=0;
            for(var c:points) {
               var s=LocalMetricProbe.measure(MAP,c.face,c.u,c.v,step);
               double analytic=MAP.localScale(c.u,c.v);
               min=Math.min(min,Math.min(s.scaleU(),s.scaleV()));max=Math.max(max,Math.max(s.scaleU(),s.scaleV()));
               maxAngle=Math.max(maxAngle,s.angleErrorDegrees());maxAspect=Math.max(maxAspect,s.aspect());
               maxRelative=Math.max(maxRelative,Math.max(Math.abs(s.scaleU()-analytic),Math.abs(s.scaleV()-analytic))/analytic);
            }
            if(maxAngle>1e-4 || maxAspect>1+1e-5 || maxRelative>1e-5 || maxInverse>1e-6) {
               throw new IllegalStateException("provisional numerical gate failed: "+cap+" "+step+" "+maxAngle+" "+maxAspect+" "+maxRelative+" "+maxInverse);
            }
            csv.printf("%s,%.0f,50,%.1f,%d,%.9f,%.9f,%.9g,%.12f,%.9g,%d,%.9g,%.9f%n",
               MAP.id(),cap,step,points.size(),min,max,maxAngle,maxAspect,maxRelative,inverseSamples,maxInverse,minClearance/1000);
            System.out.printf("local cap=%.0f km step=%.1f m scale=%.6f..%.6f max angle=%.9g degrees%n",cap,step,min,max,maxAngle);
         }
      }
   }
   private static double clearance(SphereWorldModel.GeoPoint p) {
      double[] v=LocalMetricProbe.unit(p);
      // Maximum dot product over the eight sign combinations of (1,1,1)/sqrt(3).
      double dot=(Math.abs(v[0])+Math.abs(v[1])+Math.abs(v[2]))/Math.sqrt(3);
      return R*Math.acos(Math.max(-1,Math.min(1,dot)));
   }
   private record Coordinate(int face,double u,double v) { }
}
