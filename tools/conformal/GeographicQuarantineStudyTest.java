/* SPDX-License-Identifier: GPL-3.0-or-later. Research tools only; see COPYING. */
package com.yucareux.tellus.research;
import com.yucareux.tellus.worldgen.global.ClosedQuadSphereTopology;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeographicQuarantineStudyTest {
   @Test void diagonalPointContactsRemainSeparatePerimeters() {
      var topology=ClosedQuadSphereTopology.create(4);
      var a=new ClosedQuadSphereTopology.CellId(0,1,1);var b=new ClosedQuadSphereTopology.CellId(0,2,2);
      var loops=GeographicQuarantineStudy.boundaryLoops(topology,cell->cell.equals(a)||cell.equals(b));
      assertEquals(2,loops.size());
      for(var loop:loops) {
         assertEquals(4,loop.size());
         for(int i=0;i<loop.size();i++) assertEquals(loop.get(i).end(),loop.get((i+1)%loop.size()).start());
      }
   }
   @Test void interiorHoleIsPreservedAndNotFilledByOuterBoundary() {
      var topology=ClosedQuadSphereTopology.create(5);
      var loops=GeographicQuarantineStudy.boundaryLoops(topology,cell->cell.face()==0&&cell.i()>=1&&cell.i()<=3&&cell.j()>=1&&cell.j()<=3&&!(cell.i()==2&&cell.j()==2));
      assertEquals(2,loops.size());
      assertEquals(java.util.List.of(4,12),loops.stream().map(java.util.List::size).sorted().toList());
   }
}
