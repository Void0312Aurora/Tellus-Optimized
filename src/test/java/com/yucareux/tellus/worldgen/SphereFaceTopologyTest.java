package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereFaceTopology;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SphereFaceTopologyTest {
   @Test
   void edgeNeighborsHaveExactlyOneReturnDirectionIncludingCorners() {
      var model = SphereWorldModel.forWorldScale(1.0);
      var topology = new SphereFaceTopology(model);
      long last = model.chunksPerFace() - 1;
      int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
      for (int face = 0; face < 6; face++) {
         for (long t : new long[]{0, last / 3, last / 2, 2 * last / 3, last}) {
            var edges = new SphereWorldModel.SphereChunkAddress[]{
               new SphereWorldModel.SphereChunkAddress(face, 0, t),
               new SphereWorldModel.SphereChunkAddress(face, last, t),
               new SphereWorldModel.SphereChunkAddress(face, t, 0),
               new SphereWorldModel.SphereChunkAddress(face, t, last)
            };
            for (int i = 0; i < edges.length; i++) {
               var source = edges[i];
               var dest = topology.neighbor(source, directions[i][0], directions[i][1]);
               assertNotEquals(source.face(), dest.face());
               int returnDirections = 0;
               for (int[] direction : directions) {
                  if (topology.neighbor(dest, direction[0], direction[1]).equals(source)) returnDirections++;
               }
               assertEquals(1, returnDirections, () -> "non-reciprocal chunk edge: " + source + " -> " + dest);
               double distance = SphereWorldModel.greatCircleDistanceMeters(model.exactSurfacePoint(source), model.exactSurfacePoint(dest));
               assertTrue(distance < 40.0, () -> "edge jumped " + distance + " metres");
            }
         }
      }
   }

   @Test
   void centeredStorageIncludesEveryChunkWhenTheFaceHasAnOddChunkCount() {
      var model = new SphereWorldModel(new SphereWorldModel.Config(1.0, 48, 16, 12, 100.0));
      var topology = new SphereFaceTopology(model);
      for (long x = -1; x <= 1; x++) {
         for (long z = -1; z <= 1; z++) {
            var address = topology.address(0, x, z);
            assertEquals(x + 1, address.chunkX());
            assertEquals(z + 1, address.chunkZ());
            var block = topology.blockAddress(0, x * 16, z * 16);
            assertEquals(address.blockX(), block.blockX());
            assertEquals(address.blockZ(), block.blockZ());
         }
      }
      assertThrows(IllegalArgumentException.class, () -> topology.address(6, 0, 0));
      assertThrows(IllegalArgumentException.class, () -> topology.address(0, 2, 0));
   }
}
