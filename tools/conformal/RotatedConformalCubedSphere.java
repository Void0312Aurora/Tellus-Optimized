/* SPDX-License-Identifier: GPL-3.0-or-later. Research tools only; see COPYING. */
package com.yucareux.tellus.research;

import com.yucareux.tellus.worldgen.global.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Applies a versioned proper rigid rotation to the reference map and its enclosure. */
public final class RotatedConformalCubedSphere implements ClosedQuadSphereEmbedding.SurfaceMapping {
   private final ConformalCubedSphere base=new ConformalCubedSphere();
   private final double[][] rotation;
   private final String orientationId;
   public RotatedConformalCubedSphere(String orientationId,double[][] rotation) {
      this.orientationId=Objects.requireNonNull(orientationId,"orientationId");
      if(orientationId.isBlank()) throw new IllegalArgumentException("orientation ID");
      if(rotation==null||rotation.length!=3) throw new IllegalArgumentException("rotation dimension");
      this.rotation=new double[3][3];
      for(int i=0;i<3;i++) {
         if(rotation[i]==null||rotation[i].length!=3) throw new IllegalArgumentException("rotation dimension");
         for(int j=0;j<3;j++) {
            if(!Double.isFinite(rotation[i][j])) throw new IllegalArgumentException("rotation value");
            this.rotation[i][j]=rotation[i][j];
         }
      }
      for(int i=0;i<3;i++) for(int j=0;j<3;j++) {
         double dot=0;
         for(int k=0;k<3;k++) dot+=this.rotation[k][i]*this.rotation[k][j];
         if(Math.abs(dot-(i==j?1:0))>1e-12) throw new IllegalArgumentException("nonorthogonal rotation");
      }
      double[][] m=this.rotation;
      double determinant=m[0][0]*(m[1][1]*m[2][2]-m[1][2]*m[2][1])
         -m[0][1]*(m[1][0]*m[2][2]-m[1][2]*m[2][0])+m[0][2]*(m[1][0]*m[2][1]-m[1][1]*m[2][0]);
      if(Math.abs(determinant-1)>1e-12) throw new IllegalArgumentException("rotation must preserve orientation");
   }
   public static RotatedConformalCubedSphere read(String orientationId,Path path) throws IOException {
      var lines=Files.readAllLines(path);
      if(lines.size()!=3) throw new IllegalArgumentException("rotation needs three rows");
      double[][] m=new double[3][];
      for(int i=0;i<3;i++) m[i]=java.util.Arrays.stream(lines.get(i).split(",")).mapToDouble(Double::parseDouble).toArray();
      return new RotatedConformalCubedSphere(orientationId,m);
   }
   @Override public String id() {return base.id()+"+"+orientationId;}
   @Override public SphereWorldModel.GeoPoint point(int face,double u,double v) {return rotate(base.point(face,u,v),false);}
   @Override public ClosedQuadSphereEmbedding.CellEnclosure enclosure(int face,double uLow,double uHigh,double vLow,double vHigh) {
      var enclosure=base.enclosure(face,uLow,uHigh,vLow,vHigh);
      return new ClosedQuadSphereEmbedding.CellEnclosure(rotate(enclosure.center(),false),enclosure.radiusMeters());
   }
   public ConformalCubedSphere.FaceCoordinate inverse(SphereWorldModel.GeoPoint point) {return base.inverse(rotate(point,true));}
   public ConformalCubedSphere.FaceCoordinate inverseOnFace(int face,SphereWorldModel.GeoPoint point) {return base.inverseOnFace(face,rotate(point,true));}
   public SphereWorldModel.GeoPoint rotate(SphereWorldModel.GeoPoint point,boolean inverse) {
      double[] p=LocalMetricProbe.unit(Objects.requireNonNull(point,"point")),q=new double[3];
      for(int i=0;i<3;i++) for(int j=0;j<3;j++) q[i]+=(inverse?rotation[j][i]:rotation[i][j])*p[j];
      return new SphereWorldModel.GeoPoint(Math.toDegrees(Math.atan2(q[1],Math.hypot(q[0],q[2]))),
         Math.toDegrees(Math.atan2(q[2],q[0])),point.elevationMeters());
   }
}
