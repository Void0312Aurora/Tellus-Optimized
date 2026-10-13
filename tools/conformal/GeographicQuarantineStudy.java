/* SPDX-License-Identifier: GPL-3.0-or-later. Research tools only; see COPYING. */
package com.yucareux.tellus.research;

import com.yucareux.tellus.worldgen.global.*;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/** Exports the actual quarantine perimeter, not a guessed cap-plus-cell padding. */
public final class GeographicQuarantineStudy {
   record Arc(ClosedQuadSphereTopology.CellId cell,int index,
      ClosedQuadSphereTopology.VertexKey start,ClosedQuadSphereTopology.VertexKey end) { }
   private record CellEdge(ClosedQuadSphereTopology.CellId cell,int index) { }
   public static void main(String[] args) throws Exception {
      if(args.length<2) throw new IllegalArgumentException("rotation CSV and output directory required");
      int n=args.length>2?Integer.parseInt(args[2]):128;
      var map=RotatedConformalCubedSphere.read("candidate-eight-kabsch-sphere-v1",Path.of(args[0]));
      Path output=Path.of(args[1]);Files.createDirectories(output);
      var topology=ClosedQuadSphereTopology.create(n);
      var embedding=new ClosedQuadSphereEmbedding(topology,SphereWorldModel.forWorldScale(1),map);
      try(var csv=new PrintWriter(Files.newBufferedWriter(output.resolve("quarantine-mesh-summary.csv"),StandardCharsets.UTF_8));
          var coarse=new PrintWriter(Files.newBufferedWriter(output.resolve("quarantine-boundaries-8.geojson"),StandardCharsets.UTF_8));
          var fine=new PrintWriter(Files.newBufferedWriter(output.resolve("quarantine-boundaries-32.geojson"),StandardCharsets.UTF_8))) {
         csv.println("subdivision,cap_radius_km,transition_width_km,ordinary_cells,transition_cells,excluded_cells,min_ordinary_edge_scale,max_ordinary_edge_scale,min_sample_boundary_scale,max_center_scale,max_enclosure_radius_km");
         coarse.print("{\"type\":\"FeatureCollection\",\"features\":[");fine.print("{\"type\":\"FeatureCollection\",\"features\":[");
         boolean first=true;
         for(double cap:new double[]{50,100,150,175,200,250,300,360}) {
            var policy=new SphericalIncisionPolicy(topology,embedding,new SphericalIncisionPolicy.Config(cap*1000,50000));
            var metrics=embedding.measureMetrics(policy::admitsOrdinaryMaterialization);
            int[] counts=new int[3];double maxRadius=0;
            for(var cell:topology.cells()) {
               var c=policy.classification(cell);counts[c.status().ordinal()]++;
               maxRadius=Math.max(maxRadius,c.cellRadiusMeters());
            }
            csv.printf(Locale.ROOT,"%d,%.0f,50,%d,%d,%d,%.9f,%.9f,%.9f,%.9f,%.6f%n",n,cap,
               counts[0],counts[1],counts[2],metrics.minEdgeScale(),metrics.maxEdgeScale(),
               boundaryScale((cap+50)*1000),new ConformalCubedSphere().localScale(0,0),maxRadius/1000);
            csv.flush();
            for(String zone:List.of("excluded","quarantined")) {
               Predicate<ClosedQuadSphereTopology.CellId> include=zone.equals("excluded")?
                  cell->policy.status(cell)==SphericalIncisionPolicy.CellStatus.EXCLUDED:
                  cell->policy.status(cell)!=SphericalIncisionPolicy.CellStatus.ORDINARY;
               var loops=boundaryLoops(topology,include);
               // Conservative whole-cell masks can have point contacts and
               // holes. Export every perimeter; consumers use even/odd fill.
               for(var loop:loops) {
                  var sample=embedding.vertex(loop.get(0).start());
                  var key=policy.extraordinaryVertices().stream().min(Comparator.comparingDouble(k->LocalMetricProbe.distance(sample,embedding.vertex(k)))).orElseThrow();
                  if(!first) {coarse.print(',');fine.print(',');}first=false;
                  writeFeature(coarse,embedding,loop,key,cap,zone,n,8);
                  writeFeature(fine,embedding,loop,key,cap,zone,n,32);
               }
            }
            System.out.printf(Locale.ROOT,"quarantine perimeter n=%d cap=%.0f km ordinary=%d%n",n,cap,counts[0]);
         }
         coarse.println("]}");fine.println("]}");
      }
   }
   static List<List<Arc>> boundaryLoops(ClosedQuadSphereTopology topology,Predicate<ClosedQuadSphereTopology.CellId> include) {
      var arcs=new LinkedHashMap<CellEdge,Arc>();
      var directions=new ClosedQuadSphereTopology.EdgeDirection[]{ClosedQuadSphereTopology.EdgeDirection.SOUTH,
         ClosedQuadSphereTopology.EdgeDirection.EAST,ClosedQuadSphereTopology.EdgeDirection.NORTH,ClosedQuadSphereTopology.EdgeDirection.WEST};
      for(var cell:topology.cells()) if(include.test(cell)) {
         var keys=topology.vertices(cell);
         for(int i=0;i<4;i++) if(!include.test(topology.neighbor(cell,directions[i]))) {
            Arc arc=new Arc(cell,i,keys.get(i),keys.get((i+1)%4));
            arcs.put(new CellEdge(cell,i),arc);
         }
      }
      List<List<Arc>> loops=new ArrayList<>();
      while(!arcs.isEmpty()) {
         var first=arcs.values().iterator().next();var current=first;List<Arc> loop=new ArrayList<>();
         do {
            Arc arc=arcs.remove(new CellEdge(current.cell(),current.index()));
            if(arc==null) throw new IllegalStateException("unclosed quarantine boundary");
            loop.add(arc);
            var cell=arc.cell();var vertex=arc.end();Set<ClosedQuadSphereTopology.CellId> visited=new HashSet<>();
            while(true) {
               if(!visited.add(cell)) throw new IllegalStateException("boundary circulation lost its exterior");
               int index=topology.vertices(cell).indexOf(vertex);
               if(index<0) throw new IllegalStateException("boundary vertex not in incident cell");
               var other=topology.neighbor(cell,directions[index]);
               if(!include.test(other)) {
                  current=new Arc(cell,index,vertex,topology.vertices(cell).get((index+1)%4));
                  break;
               }
               cell=other;
            }
         } while(!current.equals(first));
         loops.add(List.copyOf(loop));
      }
      return loops;
   }
   private static void writeFeature(PrintWriter out,ClosedQuadSphereEmbedding embedding,List<Arc> loop,
      ClosedQuadSphereTopology.VertexKey key,double cap,String zone,int n,int samples) {
      out.printf(Locale.ROOT,"{\"type\":\"Feature\",\"properties\":{\"cap_radius_km\":%.0f,\"transition_width_km\":50,\"subdivision\":%d,\"samples_per_edge\":%d,\"zone\":\"%s\",\"canonical_signs\":[%d,%d,%d],\"topology_edges\":%d,\"fill_rule\":\"even_odd\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[",cap,n,samples,zone,Integer.signum(key.x()),Integer.signum(key.y()),Integer.signum(key.z()),loop.size());
      SphereWorldModel.GeoPoint first=null;
      for(var arc:loop) for(int index=0;index<samples;index++) {
         double t=index/(double)samples;
         var point=switch(arc.index()) {
            case 0->embedding.cellPoint(arc.cell(),t,0);
            case 1->embedding.cellPoint(arc.cell(),1,t);
            case 2->embedding.cellPoint(arc.cell(),1-t,1);
            case 3->embedding.cellPoint(arc.cell(),0,1-t);
            default->throw new AssertionError();
         };
         if(first==null) first=point;else out.print(',');
         out.printf(Locale.ROOT,"[%.12f,%.12f]",point.longitude(),point.latitude());
      }
      out.printf(Locale.ROOT,",[%.12f,%.12f]]}}",first.longitude(),first.latitude());
   }
   private static double boundaryScale(double distance) {
      var base=new ConformalCubedSphere();var corner=base.point(0,1,1);double min=Double.POSITIVE_INFINITY;
      for(int i=0;i<=180;i++) {
         double theta=i*Math.PI/360,low=0,high=1;
         for(int iteration=0;iteration<55;iteration++) {
            double mid=(low+high)/2;
            if(LocalMetricProbe.distance(corner,base.point(0,1-mid*Math.cos(theta),1-mid*Math.sin(theta)))<distance) low=mid;else high=mid;
         }
         min=Math.min(min,base.localScale(1-high*Math.cos(theta),1-high*Math.sin(theta)));
      }
      return min;
   }
}
