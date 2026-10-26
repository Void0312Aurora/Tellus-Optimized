package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ParallelSolidSectionFillTest {
   private static Holder<Biome> biome;
   private static IdMapper<Holder<Biome>> biomeIds;

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      biome = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
      biomeIds = new IdMapper<>();
      biomeIds.add(biome);
   }

   @Test
   void parallelTallChunkMatchesSerialBlocksCountsBiomesAndSubsequentEdits() {
      int minY = -2032;
      LevelChunkSection[] serial = sections(254);
      LevelChunkSection[] parallel = sections(254);
      boolean[] serialSolid = new boolean[254];
      boolean[] parallelSolid = new boolean[254];
      var serialWriter = new EarthChunkGenerator.ChunkSectionWriter(serial, minY);
      var parallelWriter = new EarthChunkGenerator.ChunkSectionWriter(parallel, minY);
      try {
         fill(serialWriter, serialSolid, minY, 253, false);
         fill(parallelWriter, parallelSolid, minY, 253, true);
         editColumns(serialWriter, minY);
         editColumns(parallelWriter, minY);
         assertEquals(serialWriter.finish().dirtySectionCount(), parallelWriter.finish().dirtySectionCount());
         assertArrayEquals(serialSolid, parallelSolid);
         assertSectionsEqual(serial, parallel);
      } finally {
         serialWriter.close();
         parallelWriter.close();
      }
   }

   @Test
   void respectsPartialSolidLimitAndLeavesUpperSectionsAir() {
      LevelChunkSection[] serial = sections(16);
      LevelChunkSection[] parallel = sections(16);
      boolean[] serialSolid = new boolean[16];
      boolean[] parallelSolid = new boolean[16];
      var serialWriter = new EarthChunkGenerator.ChunkSectionWriter(serial, -64);
      var parallelWriter = new EarthChunkGenerator.ChunkSectionWriter(parallel, -64);
      try {
         fill(serialWriter, serialSolid, -64, 7, false);
         fill(parallelWriter, parallelSolid, -64, 7, true);
         assertEquals(8, serialWriter.finish().dirtySectionCount());
         assertEquals(8, parallelWriter.finish().dirtySectionCount());
         assertArrayEquals(serialSolid, parallelSolid);
         assertSectionsEqual(serial, parallel);
         for (int i = 8; i < parallel.length; i++) assertTrue(parallel[i].hasOnlyAir());
      } finally {
         serialWriter.close();
         parallelWriter.close();
      }
   }

   @Test
   void taskFailureJoinsAllWritersAndReleasesEverySectionLock() {
      LevelChunkSection[] sections = sections(16);
      sections[7] = null;
      var writer = new EarthChunkGenerator.ChunkSectionWriter(sections, -64);
      try {
         assertThrows(CompletionException.class, () -> fill(writer, new boolean[16], -64, 15, true));
         for (LevelChunkSection section : sections) {
            if (section == null) continue;
            section.acquire();
            try {
               assertFalse(section.getStates().get(0, 0, 0).isAir());
            } finally {
               section.release();
            }
         }
      } finally {
         writer.close();
      }
   }

   private static void fill(EarthChunkGenerator.ChunkSectionWriter writer, boolean[] solid, int minY, int max, boolean parallel) {
      int[] tops = new int[solid.length];
      Arrays.setAll(tops, i -> minY + i * 16 + 15);
      EarthChunkGenerator.fillSolidSections(
         writer, solid, tops, max, Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
         minY + 64, new EarthChunkGenerator.SolidSectionFillProfiler(), parallel
      );
   }

   private static void editColumns(EarthChunkGenerator.ChunkSectionWriter writer, int minY) {
      writer.fillColumnConstant(0, 0, minY + 10, minY + 36, Blocks.WATER.defaultBlockState());
      writer.fillSurfaceColumn(7, 13, minY + 100, minY + 84,
         Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
      writer.setBlock(4, 5, minY, Blocks.BEDROCK.defaultBlockState());
      writer.setBlock(15, 15, minY + 4063, Blocks.AIR.defaultBlockState());
   }

   private static LevelChunkSection[] sections(int count) {
      LevelChunkSection[] sections = new LevelChunkSection[count];
      Arrays.setAll(sections, ignored -> new LevelChunkSection(
         new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
         new PalettedContainer<>(biomeIds, biome, PalettedContainer.Strategy.SECTION_BIOMES)
      ));
      return sections;
   }

   private static void assertSectionsEqual(LevelChunkSection[] expected, LevelChunkSection[] actual) {
      for (int i = 0; i < expected.length; i++) {
         var left = expected[i];
         var right = actual[i];
         assertEquals(left.hasOnlyAir(), right.hasOnlyAir());
         assertEquals(left.isRandomlyTickingBlocks(), right.isRandomlyTickingBlocks());
         assertEquals(left.isRandomlyTickingFluids(), right.isRandomlyTickingFluids());
         assertEquals(left.getSerializedSize(), right.getSerializedSize());
         Map<BlockState, Integer> leftCounts = new HashMap<>();
         Map<BlockState, Integer> rightCounts = new HashMap<>();
         left.getStates().count(leftCounts::put);
         right.getStates().count(rightCounts::put);
         assertEquals(leftCounts, rightCounts, "state counts in section " + i);
         for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
               for (int x = 0; x < 16; x++) {
                  if (left.getBlockState(x, y, z) != right.getBlockState(x, y, z)) {
                     fail("block mismatch: section=" + i + " x=" + x + " y=" + y + " z=" + z);
                  }
               }
            }
         }
         assertSame(left.getNoiseBiome(0, 0, 0), right.getNoiseBiome(0, 0, 0));
      }
   }
}
