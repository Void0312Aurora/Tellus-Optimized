package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetSeamTransform;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the same quarter-turn used by the seam grid transform is also
 * the inverse-safe block-state rotation used by the native apron write path.
 */
class CubeNetApronRotationContractTest {
   @BeforeAll
   static void bootstrapMinecraft() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @Test
   void everyCutSeamRoundTripsOrientationSensitiveBlockStates() {
      var layout = new CubeNetLayout(64);
      List<BlockState> states = List.of(
         Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH),
         Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.NORTH),
         Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
      );
      int cutSeams = 0;
      for (var face : CubeNetLayout.Face.values()) {
         for (var side : CubeNetLayout.Side.values()) {
            if (layout.arePlanarNeighbors(face, side)) continue;
            cutSeams++;
            var seam = layout.seam(face, side);
            int clockwiseTargetToSource = new CubeNetSeamTransform(layout, seam)
               .targetToSourceClockwiseQuarterTurns().orElseThrow();
            Rotation targetToSource = CubeNetApronChunkSync.rotation(clockwiseTargetToSource);
            Rotation sourceToTarget = CubeNetApronChunkSync.rotation(-clockwiseTargetToSource);
            for (Direction faceDirection : Direction.values()) {
               // A canonical model face and its source-apron copy must sample
               // the same directional shade, including the two vertical faces.
               Direction proxyDirection = targetToSource.rotate(faceDirection);
               assertEquals(proxyDirection,
                  CubeNetShadeTransform.rotate(faceDirection, clockwiseTargetToSource), seam.toString());
               assertEquals(faceDirection,
                  CubeNetShadeTransform.rotate(proxyDirection, -clockwiseTargetToSource), seam.toString());
            }
            for (BlockState canonical : states) {
               BlockState proxy = canonical.rotate(targetToSource);
               BlockState restored = proxy.rotate(sourceToTarget);
               assertEquals(canonical, restored, seam.toString());
            }
         }
      }
      assertEquals(14, cutSeams);
   }
}
