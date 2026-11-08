package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.*;
import org.junit.jupiter.api.Test;

class CanonicalColumnPositionTest {
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1.0);
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(1, 1, 0, -4, 12);

   @Test void allSixCubeFacesResolveCanonicalCellCentersIncludingReflections() {
      int n = MODEL.config().faceBlocks();
      CubeNetLayout layout = CubeNetLayout.standard(n);
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         for (int i : new int[]{0, 15, 16, 32}) {
            int j = 48;
            var coordinate = TellusCoordinateContextFactory.fromCubeNetCanonical(MODEL, layout,
               new CubeNetLayout.FacePoint(face, i + 0.5, j + 0.5));
            CanonicalColumnPosition expected = switch (face) {
               case POS_X -> new CanonicalColumnPosition(2 * n - j - 1, i - n);
               case NEG_X -> new CanonicalColumnPosition(j - n, -i - 1);
               case POS_Y -> new CanonicalColumnPosition(i, j - n);
               case NEG_Y -> new CanonicalColumnPosition(i, j + n);
               case POS_Z -> new CanonicalColumnPosition(i, j - 2 * n);
               case NEG_Z -> new CanonicalColumnPosition(n - i - 1, n - j - 1);
            };
            assertEquals(expected, CanonicalColumnPosition.resolve(context(coordinate)), face.name());
         }
      }
      var onGridLine = TellusCoordinateContextFactory.fromCubeNetCanonical(MODEL, layout,
         new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 16.0, 32.0));
      assertEquals(new CanonicalColumnPosition(2 * n - 33, 16 - n),
         CanonicalColumnPosition.resolve(context(onGridLine)));
   }

   @Test void sphericalCanonicalIndicesResolveCenteredEngineCoordinates() {
      long offset = (MODEL.chunksPerFace() / 2L) * 16L;
      for (long cell : new long[]{0, offset - 1, offset, MODEL.config().faceBlocks() - 1L}) {
         var coordinate = TellusCoordinateContextFactory.fromSphericalBlock(MODEL,
            new SphereWorldModel.SphereBlockAddress(0, cell, offset));
         assertEquals(new CanonicalColumnPosition(Math.toIntExact(cell - offset), 0),
            CanonicalColumnPosition.resolve(context(coordinate)));
      }
   }

   @Test void explicitOddChunkModelUsesChunkBasedCenterAndRejectsDefaultGuess() {
      SphereWorldModel custom = new SphereWorldModel(new SphereWorldModel.Config(1.0, 48, 16, 3, 1.0e9));
      var coordinate = TellusCoordinateContextFactory.fromSphericalBlock(custom,
         new SphereWorldModel.SphereBlockAddress(0, 16, 16));
      assertEquals(new CanonicalColumnPosition(0, 0), CanonicalColumnPosition.resolve(context(coordinate), custom));
      assertThrows(IllegalArgumentException.class, () -> CanonicalColumnPosition.resolve(context(coordinate)));
   }

   private static WriteContext context(TellusCoordinateContext coordinate) {
      return WriteContext.canonical(coordinate, GENERATION, "position-test");
   }
}
