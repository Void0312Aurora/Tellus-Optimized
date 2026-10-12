package com.yucareux.tellus.worldgen;

import com.google.common.base.Suppliers;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.SphereFaceTopology;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * First engine entry point for one canonical spherical face, with radial column
 * storage. Faces are separate address domains until cross-face routing exists.
 */
public final class SphereFaceChunkGenerator extends ChunkGenerator {
   public static final Codec<SphereFaceChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.intRange(1, 1).fieldOf("geometry_version").forGetter(generator -> 1),
      FixedBiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> (FixedBiomeSource)generator.getBiomeSource()),
      Codec.intRange(0, 5).fieldOf("face").forGetter(SphereFaceChunkGenerator::face),
      Codec.DOUBLE.fieldOf("world_scale").forGetter(SphereFaceChunkGenerator::worldScale),
      Codec.INT.fieldOf("sea_level").forGetter(SphereFaceChunkGenerator::getSeaLevel),
      Codec.intRange(-2032, 2016).fieldOf("min_y").forGetter(SphereFaceChunkGenerator::getMinY),
      Codec.intRange(16, 4064).fieldOf("height").forGetter(SphereFaceChunkGenerator::getGenDepth)
   ).apply(instance, (version, biome, face, scale, sea, min, height) ->
      new SphereFaceChunkGenerator(biome, face, scale, sea, min, height)));

   private final int face;
   private final int seaLevel;
   private final int minY;
   private final int height;
   private final SphereWorldModel model;
   private final SphereFaceTopology topology;
   private final SphereTerrainMaterializer materializer;
   private final Supplier<SphereChunkSampler> sampler;
   private final Cache<Long, SphereChunkSampler.TerrainGrid> terrainCache = CacheBuilder.newBuilder()
      .maximumSize(128).build();

   public SphereFaceChunkGenerator(FixedBiomeSource biome, int face, double scale, int seaLevel, int minY, int height) {
      this(biome, face, scale, seaLevel, minY, height, null);
   }

   SphereFaceChunkGenerator(
      FixedBiomeSource biome, int face, double scale, int seaLevel, int minY, int height,
      SphereChunkSampler.PointSampler pointSampler
   ) {
      super(biome, ignored -> BiomeGenerationSettings.EMPTY);
      if (face < 0 || face >= 6) throw new IllegalArgumentException("face");
      if (minY % 16 != 0 || height % 16 != 0 || height < 16 || minY < -2032 || (long)minY + height > 2032) {
         throw new IllegalArgumentException("vertical range must be section-aligned within [-2032, 2032)");
      }
      this.face = face;
      this.seaLevel = seaLevel;
      this.minY = minY;
      this.height = height;
      this.model = SphereWorldModel.forWorldScale(scale);
      this.topology = new SphereFaceTopology(this.model);
      this.materializer = new SphereTerrainMaterializer(scale, seaLevel);
      // Codec decoding must not fetch datasets. Sources initialize on first use.
      this.sampler = pointSampler == null
         ? Suppliers.memoize(() -> TellusWorldgenSources.sphericalSampler(this.model))
         : () -> new SphereChunkSampler(this.model, pointSampler);
   }

   public int face() { return this.face; }
   public double worldScale() { return this.model.config().worldScale(); }
   public SphereWorldModel model() { return this.model; }
   public SphereFaceTopology topology() { return this.topology; }

   /** Engine coordinates are centered on this face, not a Mercator plane. */
   public SphereWorldModel.SphereChunkAddress address(ChunkPos pos) {
      return this.topology.address(this.face, pos.x, pos.z);
   }

   public SphereWorldModel.GeoPoint geographicPoint(int blockX, int blockZ) {
      return this.model.surfacePoint(this.topology.blockAddress(this.face, blockX, blockZ));
   }

   private boolean onFace(int chunkX, int chunkZ) {
      return this.topology.containsEngineChunk(chunkX, chunkZ);
   }

   @Override
   protected Codec<? extends ChunkGenerator> codec() { return CODEC; }

   @Override
   public CompletableFuture<ChunkAccess> createBiomes(
      Executor executor, RandomState random, Blender blender, StructureManager structures, ChunkAccess chunk
   ) {
      chunk.fillBiomesFromNoise(this.biomeSource, Climate.empty());
      return CompletableFuture.completedFuture(chunk);
   }

   @Override
   public CompletableFuture<ChunkAccess> fillFromNoise(
      Executor executor, Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk
   ) {
      if (!this.onFace(chunk.getPos().x, chunk.getPos().z)) return CompletableFuture.completedFuture(chunk);
      return CompletableFuture.supplyAsync(() -> {
         var grid = this.terrainGrid(chunk.getPos());
         this.materializer.fill(chunk, grid);
         return chunk;
      }, executor);
   }

   private SphereTerrainMaterializer.ColumnPlan column(int x, int z, LevelHeightAccessor accessor) {
      var grid = this.terrainGrid(new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
      return this.materializer.plan(grid.column(x & 15, z & 15).data(), accessor.getMinBuildHeight(), accessor.getMaxBuildHeight());
   }

   private SphereChunkSampler.TerrainGrid terrainGrid(ChunkPos pos) {
      try {
         return this.terrainCache.get(ChunkPos.asLong(pos.x, pos.z), () -> {
            var grid = this.sampler.get().sampleTerrain(this.address(pos));
            if (grid.columns().stream().anyMatch(column -> !column.data().elevationAvailable())) {
               throw new IllegalStateException("spherical terrain elevation is unavailable for " + pos);
            }
            return grid;
         });
      } catch (java.util.concurrent.ExecutionException error) {
         Throwable cause = error.getCause();
         if (cause instanceof RuntimeException runtime) throw runtime;
         if (cause instanceof Error serious) throw serious;
         throw new IllegalStateException("spherical terrain sampling failed", cause);
      }
   }

   @Override
   public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor accessor, RandomState random) {
      if (!this.onFace(Math.floorDiv(x, 16), Math.floorDiv(z, 16))) return accessor.getMinBuildHeight();
      var column = this.column(x, z, accessor);
      for (int y = column.topY(); y >= accessor.getMinBuildHeight(); y--) {
         if (type.isOpaque().test(column.stateAt(y, accessor.getMinBuildHeight()))) return y + 1;
      }
      return accessor.getMinBuildHeight();
   }

   @Override
   public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor accessor, RandomState random) {
      BlockState[] states = new BlockState[accessor.getHeight()];
      Arrays.fill(states, Blocks.AIR.defaultBlockState());
      if (this.onFace(Math.floorDiv(x, 16), Math.floorDiv(z, 16))) {
         var column = this.column(x, z, accessor);
         for (int i = 0; i < states.length; i++) states[i] = column.stateAt(accessor.getMinBuildHeight() + i, accessor.getMinBuildHeight());
      }
      return new NoiseColumn(accessor.getMinBuildHeight(), states);
   }

   @Override public int getGenDepth() { return this.height; }
   @Override public int getSeaLevel() { return this.seaLevel; }
   @Override public int getMinY() { return this.minY; }
   @Override public void buildSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk) { }
   @Override public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomes,
      StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) { }
   @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) { }
   @Override public void spawnOriginalMobs(WorldGenRegion level) { }
   @Override public void createStructures(RegistryAccess registry, ChunkGeneratorStructureState state,
      StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates) { }
   @Override public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) { }

   @Override
   public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
      info.add("Tellus sphere face: " + this.face);
      if (this.onFace(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16))) {
         var point = this.geographicPoint(pos.getX(), pos.getZ());
         info.add("Sphere latitude/longitude: " + point.latitude() + ", " + point.longitude());
      }
   }
}
