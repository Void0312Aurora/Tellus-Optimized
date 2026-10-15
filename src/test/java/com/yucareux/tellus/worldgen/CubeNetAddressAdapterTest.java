package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetAddressAdapter;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CubeNetAddressAdapterTest {
   @Test
   void everyFaceEdgeBlockHasOneReciprocalPhysicalNeighbor() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var adapter = new CubeNetAddressAdapter(model);
      int last = model.config().faceBlocks() - 1;
      for (int face = 0; face < SphereWorldModel.FACE_COUNT; face++) {
         // The eight cube vertices are outside the ordinary face domain; this
         // test covers edge interiors where a two-face seam is unambiguous.
         for (int t : new int[]{1, 2, last / 2, last - 2, last - 1}) {
            var sources = new SphereWorldModel.SphereBlockAddress[]{
               new SphereWorldModel.SphereBlockAddress(face, 0, t),
               new SphereWorldModel.SphereBlockAddress(face, last, t),
               new SphereWorldModel.SphereBlockAddress(face, t, 0),
               new SphereWorldModel.SphereBlockAddress(face, t, last)
            };
            int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int i = 0; i < sources.length; i++) {
               var neighbor = adapter.neighbor(sources[i], directions[i][0], directions[i][1]);
               CubeNetAddressAdapter.Neighbor back = null;
               for (int[] returnDirection : directions) {
                  var candidate = adapter.neighbor(neighbor.address(), returnDirection[0], returnDirection[1]);
                  if (candidate.address().equals(sources[i])) {
                     back = candidate;
                     break;
                  }
               }
               org.junit.jupiter.api.Assertions.assertNotNull(back);
               assertNotEquals(sources[i].face(), neighbor.address().face());
            }
         }
      }
   }

   @Test
   void directNetNeighborsRemainAdjacentInThePlanarView() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var adapter = new CubeNetAddressAdapter(model);
      int last = model.config().faceBlocks() - 1;
      var source = new SphereWorldModel.SphereBlockAddress(2, last, last / 2);
      var neighbor = adapter.neighbor(source, 1, 0);
      var a = adapter.planarCenter(source);
      var b = adapter.planarCenter(neighbor.address());
      assertEquals(1.0, Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()), 1.0E-9);
   }

   @Test
   void seamNeighborCarriesPhysicalSeamMetadata() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var adapter = new CubeNetAddressAdapter(model);
      int last = model.config().faceBlocks() - 1;
      var source = new SphereWorldModel.SphereBlockAddress(0, last, last / 2);
      var neighbor = adapter.neighbor(source, 1, 0);
      assertEquals(CubeNetLayout.Face.NEG_Z.index(), neighbor.address().face());
      assertEquals(CubeNetLayout.Face.POS_X, neighbor.seam().source());
      assertEquals(CubeNetLayout.Side.EAST, neighbor.seam().sourceSide());
      assertEquals(CubeNetLayout.Face.NEG_Z, neighbor.seam().target());
   }
}
