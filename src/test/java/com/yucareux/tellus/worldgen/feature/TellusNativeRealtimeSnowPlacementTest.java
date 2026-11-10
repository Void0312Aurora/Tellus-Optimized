package com.yucareux.tellus.worldgen.feature;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.*;

class TellusNativeRealtimeSnowPlacementTest {
   static final BlockPos GROUND = new BlockPos(-21, 70, 37);
   @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

   @Test void sharedProductionSinkMatchesOriginalReadsWritesStatesAndFlags() {
      for (BlockState ground : List.of(Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
         Blocks.LAVA.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
         Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true))) {
         for (BlockState above : List.of(Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(),
            Blocks.SNOW.defaultBlockState(), Blocks.STONE.defaultBlockState())) {
            for (int flags : new int[]{2, 260}) for (boolean accepted : new boolean[]{false, true}) {
               World old = new World(ground, above, accepted), actual = new World(ground, above, accepted);
               legacy(old.level, flags);
               TellusNativeRealtimeSnowPlacement.place(actual.level, GROUND.mutable(), flags);
               assertEquals(old.trace, actual.trace);
               assertEquals(old.blocks, actual.blocks);
            }
         }
      }
   }

   // Frozen pre-migration production body, not derived from the new kernel.
   static void legacy(WorldGenLevel level, int flags) {
      BlockState state = level.getBlockState(GROUND);
      if (!state.getFluidState().isEmpty()) return;
      BlockPos above = GROUND.above();
      BlockState snow = Blocks.SNOW.defaultBlockState();
      if (level.getBlockState(above).isAir() && snow.canSurvive(level, above)) {
         level.setBlock(above, snow, flags);
         if (state.hasProperty(BlockStateProperties.SNOWY) && !state.getValue(BlockStateProperties.SNOWY))
            level.setBlock(GROUND, state.setValue(BlockStateProperties.SNOWY, true), flags);
      }
   }

   static final class World {
      final Map<BlockPos, BlockState> blocks = new HashMap<>();
      final List<String> trace = new ArrayList<>();
      final WorldGenLevel level;
      World(BlockState ground, BlockState above, boolean accepted) {
         blocks.put(GROUND, ground); blocks.put(GROUND.above(), above);
         level = (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
            (proxy, method, args) -> {
               BlockPos position;
               switch (method.getName()) {
                  case "getBlockState":
                     position = ((BlockPos)args[0]).immutable(); trace.add("read:" + position);
                     return blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
                  case "setBlock":
                     position = ((BlockPos)args[0]).immutable(); trace.add("write:" + position + ":" + args[1] + ":" + args[2]);
                     if (accepted) blocks.put(position, (BlockState)args[1]);
                     return accepted;
                  case "getFluidState": return blocks.getOrDefault(((BlockPos)args[0]), Blocks.AIR.defaultBlockState()).getFluidState();
                  default: throw new AssertionError("unexpected native call: " + method);
               }
            });
      }
   }
}
