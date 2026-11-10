package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/** Native Earth vegetation and built-up-cover options, without changing spherical ownership. */
final class CubeNetTravelTerrainRules {
   private static final Map<TreeKey, List<WorldgenApi.TreeFeatureHandle>> TREE_FEATURES = new ConcurrentHashMap<>();

   private CubeNetTravelTerrainRules() { }

   static int surfaceCover(int rawCover, int visualCover, Holder<Biome> biome, boolean climateBased) {
      if (!climateBased || rawCover != MountainSurfaceRules.ESA_BUILT) return visualCover;
      if (biome == null) return MountainSurfaceRules.ESA_GRASSLAND;
      if (biome.is(BiomeTags.IS_BADLANDS) || biome.is(Biomes.DESERT) || biome.is(Biomes.STONY_PEAKS)
         || biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) return MountainSurfaceRules.ESA_BARE;
      if (biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.FOREST)
         || biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.DARK_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)
         || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)) {
         return MountainSurfaceRules.ESA_TREE_COVER;
      }
      return biome.is(BiomeTags.IS_SAVANNA) ? MountainSurfaceRules.ESA_SHRUBLAND : MountainSurfaceRules.ESA_GRASSLAND;
   }

   static int treeCellSize(boolean customTrees) {
      return customTrees ? TellusProceduralTreeGenerator.PLACEMENT_CELL_SIZE : 5;
   }

   static TellusProceduralTreeGenerator.TreeAnchor treeAnchor(int cellX, int cellZ, long seed, boolean customTrees) {
      if (customTrees) return TellusProceduralTreeGenerator.anchorForCell(cellX, cellZ, seed);
      // Match Earth's intentional int overflow in x * 3129871 and signed shift.
      long anchorSeed = cellX * 3129871 ^ cellZ * 116129781L;
      anchorSeed = anchorSeed * anchorSeed * 42317861L + anchorSeed * 11L;
      anchorSeed = (anchorSeed >> 16) ^ seed;
      RandomSource random = RandomSource.create(anchorSeed);
      return new TellusProceduralTreeGenerator.TreeAnchor(cellX * 5 + random.nextInt(5),
         cellZ * 5 + random.nextInt(5), anchorSeed);
   }

   static List<WorldgenApi.TreeFeatureHandle> treeFeatures(Holder<Biome> biome, boolean hugeRedMushrooms) {
      return TREE_FEATURES.computeIfAbsent(new TreeKey(biome, hugeRedMushrooms), key -> {
         List<WorldgenApi.TreeFeatureHandle> result = new ArrayList<>();
         for (var set : biome.value().getGenerationSettings().features()) {
            for (var feature : set) {
               var placed = feature.value();
               if (!WorldgenApi.features(placed).anyMatch(WorldgenApi.TreeFeatureHandle::isTree)) continue;
               if (hugeRedMushrooms || !WorldgenApi.features(placed).anyMatch(WorldgenApi.TreeFeatureHandle::isHugeRedMushroom)) {
                  result.add(WorldgenApi.primaryFeature(placed));
               } else {
                  WorldgenApi.features(placed).filter(tree -> tree.isTree() && !tree.isHugeRedMushroom()).forEach(result::add);
               }
            }
         }
         return List.copyOf(result);
      });
   }

   private record TreeKey(Holder<Biome> biome, boolean hugeRedMushrooms) { }
}
