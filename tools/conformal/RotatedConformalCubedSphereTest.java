/* SPDX-License-Identifier: GPL-3.0-or-later. Research tools only; see COPYING. */
package com.yucareux.tellus.research;
import com.yucareux.tellus.worldgen.global.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RotatedConformalCubedSphereTest {
   private static final double[][] R={{-.5187679068420676,-.807114107659033,-.28186286745235056},
      {.7781588802211578,-.3092525966192103,-.5466549081616554},
      {.35404606474815076,-.5029211157846107,.788493332460275}};
   @Test void fittedCornerIsGeographicCandidateAndMatrixIsDefensivelyCopied() {
      double[][] matrix=java.util.Arrays.stream(R).map(double[]::clone).toArray(double[][]::new);
      var map=new RotatedConformalCubedSphere("candidate-eight-kabsch-sphere-v1",matrix);
      matrix[0][0]=999;
      // Face 1 with local u=-1,v=-1 maps to canonical (-1,-1,-1).
      var point=map.point(1,-1,-1);
      assertEquals(2.5727682914294054,point.latitude(),1e-11);
      assertEquals(-21.694427791658,point.longitude(),1e-11);
      assertTrue(map.id().contains("candidate-eight-kabsch-sphere-v1"));
   }
   @Test void rotatedInverseAndSeamsStayContinuous() {
      var map=new RotatedConformalCubedSphere("candidate-eight-kabsch-sphere-v1",R);
      for(int face=0;face<6;face++) for(double u:new double[]{-1,-.4,0,.4,1}) for(double v:new double[]{-.8,0,.8}) {
         var p=map.point(face,u,v); var inverse=map.inverseOnFace(face,p);
         assertEquals(u,inverse.u(),1e-10);assertEquals(v,inverse.v(),1e-10);
         var owner=map.inverse(p);
         assertTrue(LocalMetricProbe.distance(p,map.point(owner.face(),owner.u(),owner.v()))<1e-6);
      }
      var embedding=new ClosedQuadSphereEmbedding(ClosedQuadSphereTopology.create(7),SphereWorldModel.forWorldScale(1),map);
      assertTrue(embedding.auditSeams(65).maxInteriorMismatchMeters()<1e-7);
   }
   @Test void rotationPreservesCellEnclosuresAndClassification() {
      var base=new ConformalCubedSphere();var map=new RotatedConformalCubedSphere("fixture",R);
      var topology=ClosedQuadSphereTopology.create(17);
      var a=new ClosedQuadSphereEmbedding(topology,SphereWorldModel.forWorldScale(1),base);
      var b=new ClosedQuadSphereEmbedding(topology,SphereWorldModel.forWorldScale(1),map);
      var configuration=new SphericalIncisionPolicy.Config(300000,50000);
      var before=new SphericalIncisionPolicy(topology,a,configuration);
      var after=new SphericalIncisionPolicy(topology,b,configuration);
      for(var cell:topology.cells()) {
         assertEquals(a.cellEnclosure(cell).radiusMeters(),b.cellEnclosure(cell).radiusMeters());
         assertEquals(before.status(cell),after.status(cell));
         var enclosure=b.cellEnclosure(cell);
         for(int i=0;i<=2;i++) for(int j=0;j<=2;j++) assertTrue(LocalMetricProbe.distance(enclosure.center(),b.cellPoint(cell,i/2.0,j/2.0))<=enclosure.radiusMeters());
      }
   }
   @Test void improperAndMalformedRotationsAreRejected() {
      assertThrows(IllegalArgumentException.class,()->new RotatedConformalCubedSphere("x",new double[][]{{-1,0,0},{0,1,0},{0,0,1}}));
      assertThrows(IllegalArgumentException.class,()->new RotatedConformalCubedSphere("x",new double[][]{{2,0,0},{0,1,0},{0,0,1}}));
      assertThrows(IllegalArgumentException.class,()->new RotatedConformalCubedSphere("",R));
      assertThrows(IllegalArgumentException.class,()->new RotatedConformalCubedSphere("x",new double[][]{{Double.NaN,0,0},{0,1,0},{0,0,1}}));
   }
}
