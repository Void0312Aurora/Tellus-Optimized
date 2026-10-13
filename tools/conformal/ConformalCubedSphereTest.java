/* SPDX-License-Identifier: GPL-3.0-or-later. Research tools only; see COPYING. */
package com.yucareux.tellus.research;

import com.yucareux.tellus.worldgen.global.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConformalCubedSphereTest {
   private final ConformalCubedSphere map = new ConformalCubedSphere();

   @Test void independentHighPrecisionSourceFixtures() throws Exception {
      for (String line : Files.readAllLines(Path.of("tools/conformal/reference-fixtures.csv")).subList(1,5)) {
         double[] q = java.util.Arrays.stream(line.split(",")).mapToDouble(Double::parseDouble).toArray();
         double[] p = LocalMetricProbe.unit(map.point(0,q[0],q[1]));
         assertEquals(q[2],p[0],2e-15);
         assertEquals(q[3],-p[2],2e-15);
         assertEquals(q[4],p[1],2e-15);
      }
   }

   @Test void faceCentersAndSharedCubeCorners() {
      for (int face=0; face<6; face++) {
         assertEquals(1.0356103925319096,map.localScale(0,0),1e-14);
         for (double u : new double[]{-1,1}) for (double v : new double[]{-1,1}) {
            for (double component : LocalMetricProbe.unit(map.point(face,u,v))) {
               assertEquals(1/Math.sqrt(3),Math.abs(component),2e-15);
            }
            assertEquals(0,map.localScale(u,v));
         }
      }
   }

   @Test void allSharedEdgesIncludingInteriorsAndOddFaceAxes() {
      for (int n : new int[]{5,7}) {
         var embedding = embedding(ClosedQuadSphereTopology.create(n));
         var seams = embedding.auditSeams(65);
         assertTrue(seams.maxVertexMismatchMeters()<1e-7,seams.toString());
         assertTrue(seams.maxInteriorMismatchMeters()<1e-7,seams.toString());
         assertEquals(ConformalCubedSphere.MAP_ID,embedding.mappingId());
         assertThrows(IllegalStateException.class,embedding::mapping);
      }
   }

   @Test void inverseRoundTripsAxesDiagonalsSeamsCornersAndRandomPoints() {
      var random = new Random(3512026);
      for (int face=0; face<6; face++) {
         for (int i=0;i<=10;i++) for (int j=0;j<=10;j++) checkInverse(face, i/5.0-1,j/5.0-1);
         for (int i=0;i<160;i++) checkInverse(face,random.nextDouble()*2-1,random.nextDouble()*2-1);
         for (double epsilon : new double[]{1e-2,1e-4,1e-6,1e-8}) {
            checkInverse(face,1-epsilon,1-epsilon);
            checkInverse(face,-1+epsilon,1);
         }
      }
   }

   @Test void normalTangentsJoinAcrossAllTwelveCubeSeams() {
      var topology=ClosedQuadSphereTopology.create(7);
      int directedSeamCells=0;
      for (var cell:topology.cells()) for (var direction:ClosedQuadSphereTopology.EdgeDirection.values()) {
         var other=topology.neighbor(cell,direction);
         if (other.face()==cell.face()) continue;
         var reciprocal=java.util.Arrays.stream(ClosedQuadSphereTopology.EdgeDirection.values())
            .filter(d->topology.neighbor(other,d).equals(cell)).findFirst().orElseThrow();
         double[] a=inwardTangent(cell,direction,7), b=inwardTangent(other,reciprocal,7);
         double la=Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]);
         double lb=Math.sqrt(b[0]*b[0]+b[1]*b[1]+b[2]*b[2]);
         double error=0;
         for (int i=0;i<3;i++) error+=Math.pow(a[i]/la+b[i]/lb,2);
         assertTrue(Math.sqrt(error)<1e-6,cell+" "+direction+" frame error="+Math.sqrt(error));
         assertEquals(1,la/lb,1e-6);
         directedSeamCells++;
      }
      assertEquals(12*7*2,directedSeamCells);
   }

   private double[] inwardTangent(ClosedQuadSphereTopology.CellId cell,ClosedQuadSphereTopology.EdgeDirection direction,int n) {
      double u=2*(cell.i()+.5)/n-1,v=2*(cell.j()+.5)/n-1;
      boolean first=direction==ClosedQuadSphereTopology.EdgeDirection.EAST||direction==ClosedQuadSphereTopology.EdgeDirection.WEST;
      double sign;
      switch(direction) {
         case EAST -> {u=1;sign=-1;}
         case WEST -> {u=-1;sign=1;}
         case NORTH -> {v=1;sign=-1;}
         case SOUTH -> {v=-1;sign=1;}
         default -> throw new AssertionError();
      }
      double[] tangent=LocalMetricProbe.tangent(map,cell.face(),u,v,1,first);
      for(int i=0;i<3;i++) tangent[i]*=sign;
      return tangent;
   }

   private void checkInverse(int face,double u,double v) {
      var p=map.point(face,u,v);
      var inverse=map.inverseOnFace(face,p);
      assertEquals(u,inverse.u(),1e-10,"u for "+p);
      assertEquals(v,inverse.v(),1e-10,"v for "+p);
      assertTrue(LocalMetricProbe.distance(p,map.point(face,inverse.u(),inverse.v()))<1e-6);
   }

   @Test void canonicalInverseUsesLowestIncidentFaceAndIgnoresElevation() {
      var topology=ClosedQuadSphereTopology.create(7);
      var embedding=embedding(topology);
      for (var key : topology.vertices()) {
         var point=embedding.vertex(key);
         int owner=topology.uses(key).stream().mapToInt(ClosedQuadSphereTopology.FaceVertex::face).min().orElseThrow();
         assertEquals(owner,map.inverse(point).face());
         assertEquals(map.inverse(point),map.inverse(new SphereWorldModel.GeoPoint(point.latitude(),point.longitude(),-11000)));
      }
   }

   @Test void meterAndHalfMeterTangentsAreOrthogonalAndMatchAnalyticScale() {
      for (int face=0;face<6;face++) for (double u : new double[]{-1,-.93,-.5,0,.5,.93,1}) {
         for (double v : new double[]{-.93,-.5,0,.5,.93}) for (double step : new double[]{1,.5}) {
            var sample=LocalMetricProbe.measure(map,face,u,v,step);
            assertTrue(sample.angleErrorDegrees()<1e-4,sample.toString());
            assertTrue(sample.aspect()<1+1e-5,sample.toString());
            assertEquals(map.localScale(u,v),sample.scaleU(),1e-5);
            assertEquals(map.localScale(u,v),sample.scaleV(),1e-5);
         }
      }
   }

   @Test void cornerScaleCollapseHasOneThirdPowerAsymptotic() {
      double first=map.localScale(1-1e-3,1-1e-3);
      double second=map.localScale(1-1e-6,1-1e-6);
      assertEquals(.1,second/first,1e-5);
      assertTrue(second>0);
      assertEquals(0,map.localScale(1,1));
   }

   @Test void enclosuresCoverCurvedEdgesInteriorsAndEveryAnalyticFold() {
      for (int n : new int[]{1,3,7,17}) {
         var topology=ClosedQuadSphereTopology.create(n);
         var embedding=embedding(topology);
         for (var cell : topology.cells()) {
            var enclosure=embedding.cellEnclosure(cell);
            for (int i=0;i<=6;i++) for (int j=0;j<=6;j++) {
               assertTrue(LocalMetricProbe.distance(enclosure.center(),embedding.cellPoint(cell,i/6.0,j/6.0))
                  <=enclosure.radiusMeters(),cell+" "+i+","+j);
            }
         }
      }
      for (int i=0;i<=40;i++) for (int j=0;j<=40;j++) {
         // |dr/du| = pi*scale/4 <= |dw/dzeta| for inverse stereographic metric.
         assertTrue(Math.PI/4*map.localScale(i/20.0-1,j/20.0-1)<=map.stereoDerivativeBound());
      }
   }

   @Test void capPolicyPreservesOrdinaryClearanceAndRejectsSingularTraversal() {
      var topology=ClosedQuadSphereTopology.create(24);
      var embedding=embedding(topology);
      var policy=new SphericalIncisionPolicy(topology,embedding,new SphericalIncisionPolicy.Config(300000,50000));
      assertFalse(policy.ordinaryCells().isEmpty());
      for (var cell : topology.cells()) {
         if (topology.vertices(cell).stream().anyMatch(policy.extraordinaryVertices()::contains)) {
            assertEquals(SphericalIncisionPolicy.CellStatus.EXCLUDED,policy.status(cell));
            assertThrows(IllegalStateException.class,()->policy.requireOrdinary(cell));
         }
         if (!policy.admitsOrdinaryMaterialization(cell)) continue;
         for (int i=0;i<=2;i++) for (int j=0;j<=2;j++) {
            var point=embedding.cellPoint(cell,i/2.0,j/2.0);
            for (var vertex : policy.extraordinaryVertices()) {
               assertTrue(LocalMetricProbe.distance(point,embedding.vertex(vertex))>350000);
            }
         }
      }
   }

   @Test void invalidDomainAndEnclosureInputsFailExplicitly() {
      assertThrows(IllegalArgumentException.class,()->map.point(-1,0,0));
      assertThrows(IllegalArgumentException.class,()->map.point(6,0,0));
      assertThrows(IllegalArgumentException.class,()->map.point(0,Double.NaN,0));
      assertThrows(IllegalArgumentException.class,()->map.localScale(1.001,0));
      assertThrows(IllegalArgumentException.class,()->map.inverseOnFace(0,map.point(1,0,0)));
      assertThrows(IllegalArgumentException.class,()->map.enclosure(0,.5,-.5,0,.2));
      assertThrows(IllegalArgumentException.class,()->new ClosedQuadSphereEmbedding.CellEnclosure(map.point(0,0,0),-1));
      assertThrows(IllegalArgumentException.class,()->new ClosedQuadSphereEmbedding.CellEnclosure(map.point(0,0,0),Double.NaN));
      assertThrows(NullPointerException.class,()->map.inverse(null));
   }

   private ClosedQuadSphereEmbedding embedding(ClosedQuadSphereTopology topology) {
      return new ClosedQuadSphereEmbedding(topology,SphereWorldModel.forWorldScale(1),map);
   }
}
