package com.yucareux.tellus.worldgen;

import com.google.common.base.Preconditions;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.worldgen.global.CubeNetAddressAdapter;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.server.level.WorldGenRegion;
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
 * One Level backed by a planar cube-net view. The six face samplers remain the
 * authority for terrain; this generator only resolves a planar chunk to one
 * canonical face chunk and leaves cut-edge movement to CubeNetRuntime.
 */
public final class CubeNetChunkGenerator extends ChunkGenerator {
   public static final Codec<CubeNetChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.intRange(2, 2).fieldOf("geometry_version").forGetter(generator -> 2),
      Codec.STRING.fieldOf("mapping").forGetter(generator -> generator.model().mappingId()),
      Codec.DOUBLE.optionalFieldOf("excluded_radius_meters",
         SphereSingularityPolicy.DEFAULT_CONFIG.excludedRadiusMeters())
         .forGetter(generator -> generator.singularityConfig().excludedRadiusMeters()),
      Codec.DOUBLE.optionalFieldOf("transition_width_meters",
         SphereSingularityPolicy.DEFAULT_CONFIG.transitionWidthMeters())
         .forGetter(generator -> generator.singularityConfig().transitionWidthMeters()),
      FixedBiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> (FixedBiomeSource)generator.getBiomeSource()),
      Codec.DOUBLE.fieldOf("world_scale").forGetter(CubeNetChunkGenerator::worldScale),
      Codec.INT.fieldOf("sea_level").forGetter(CubeNetChunkGenerator::getSeaLevel),
      Codec.intRange(-2032, 2016).fieldOf("min_y").forGetter(CubeNetChunkGenerator::getMinY),
      Codec.intRange(16, 4064).fieldOf("height").forGetter(CubeNetChunkGenerator::getGenDepth)
   ).apply(instance, (version, mapping, excludedRadius, transitionWidth, biome, scale, sea, min, height) -> {
      if (!SphereWorldModel.MAPPING_ID.equals(mapping)) {
         throw new IllegalArgumentException("unsupported spherical mapping: " + mapping);
      }
      return new CubeNetChunkGenerator(biome, scale, sea, min, height,
         new SphereSingularityPolicy.Config(excludedRadius, transitionWidth));
   }));

   private final int seaLevel;
   private final int minY;
   private final int height;
   private final SphereWorldModel model;
   private final CubeNetAddressAdapter adapter;
   private final SphereFaceChunkGenerator[] faces;
   private final SphereTerrainMaterializer materializer;
   private final SphereSingularityPolicy.Config singularityConfig;

   public CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height) {
      this(biome, scale, seaLevel, minY, height, SphereSingularityPolicy.DEFAULT_CONFIG);
   }

   CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height,
      SphereChunkSampler.PointSampler pointSampler) {
      this(biome, scale, seaLevel, minY, height, SphereSingularityPolicy.DEFAULT_CONFIG, pointSampler);
   }

   private CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height,
      SphereSingularityPolicy.Config singularityConfig) {
      this(biome, scale, seaLevel, minY, height, singularityConfig, null);
   }

   private CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height,
      SphereSingularityPolicy.Config singularityConfig, SphereChunkSampler.PointSampler pointSampler) {
      super(biome, ignored -> BiomeGenerationSettings.EMPTY);
      Preconditions.checkArgument(minY % 16 == 0 && height % 16 == 0 && height >= 16
         && minY >= -2032 && (long)minY + height <= 2032,
         "vertical range must be section-aligned within [-2032, 2032)");
      this.seaLevel = seaLevel;
      this.minY = minY;
      this.height = height;
      this.model = SphereWorldModel.forWorldScale(scale);
      this.adapter = new CubeNetAddressAdapter(this.model);
      this.materializer = new SphereTerrainMaterializer(scale, seaLevel);
      this.singularityConfig = singularityConfig;
      this.faces = new SphereFaceChunkGenerator[CubeNetLayout.FACE_COUNT];
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         this.faces[face.index()] = pointSampler == null
            ? new SphereFaceChunkGenerator(biome, face.index(), scale, seaLevel, minY, height,
               "", singularityConfig)
            : new SphereFaceChunkGenerator(biome, face.index(), scale, seaLevel, minY, height, pointSampler);
      }
   }

   public SphereWorldModel model() { return this.model; }
   public CubeNetAddressAdapter adapter() { return this.adapter; }
   public double worldScale() { return this.model.config().worldScale(); }
   SphereSingularityPolicy.Config singularityConfig() { return this.singularityConfig; }

   public Optional<SphereWorldModel.SphereChunkAddress> address(ChunkPos pos) {
      return this.adapter.chunkAddress(pos.x, pos.z);
   }

   public Optional<CubeNetLayout.Face> faceAt(double x, double z) {
      try {
         return Optional.of(this.adapter.layout().fromPlanar(new CubeNetLayout.PlanarPoint(x, z)).face());
      } catch (IllegalArgumentException ignored) {
         return Optional.empty();
      }
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
      return this.address(chunk.getPos()).map(address -> CompletableFuture.supplyAsync(() -> {
         var canonical = this.faces[address.face()].terrainGrid(address);
         var columns = new ArrayList<SphereChunkSampler.Column>(256);
         var face = CubeNetLayout.Face.of(address.face());
         for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            // Chunk origins and all face offsets are section aligned. The
            // inverse basis therefore also determines the local cell order;
            // floorMod handles reflected axes (cell 0 maps to cell 15).
            double[] local = this.adapter.layout().localVector(face, x + 0.5, z + 0.5);
            columns.add(canonical.column(Math.floorMod((int)Math.floor(local[0]), 16),
               Math.floorMod((int)Math.floor(local[1]), 16)));
         }
         this.materializer.fill(chunk, new SphereChunkSampler.TerrainGrid(address, columns));
         return chunk;
      }, executor)
      ).orElseGet(() -> CompletableFuture.completedFuture(chunk));
   }

   @Override
   public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor accessor, RandomState random) {
      return this.resolveBlock(x, z).map(block -> this.faces[block.address().face()].getBaseHeightAt(
         block.address(), block.x(), block.z(), type, accessor)).orElse(accessor.getMinBuildHeight());
   }

   @Override
   public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor accessor, RandomState random) {
      return this.resolveBlock(x, z).map(block -> this.faces[block.address().face()].getBaseColumnAt(
         block.address(), block.x(), block.z(), accessor)).orElseGet(() -> {
            BlockState[] states = new BlockState[accessor.getHeight()];
            Arrays.fill(states, Blocks.AIR.defaultBlockState());
            return new NoiseColumn(accessor.getMinBuildHeight(), states);
         });
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
      info.add("Tellus cube-net");
      this.faceAt(pos.getX() + 0.5, pos.getZ() + 0.5).ifPresent(face -> info.add("Tellus face: " + face));
   }

   private Optional<BlockAddress> resolveBlock(int x, int z) {
      int chunkX = Math.floorDiv(x, 16), chunkZ = Math.floorDiv(z, 16);
      Optional<SphereWorldModel.SphereChunkAddress> address = this.address(new ChunkPos(chunkX, chunkZ));
      if (address.isEmpty()) return Optional.empty();
      try {
         CubeNetLayout.FacePoint point = this.adapter.layout().fromPlanar(
            new CubeNetLayout.PlanarPoint(x + 0.5, z + 0.5));
         SphereWorldModel.SphereChunkAddress resolved = address.get();
         if (point.face().index() != resolved.face()) return Optional.empty();
         return Optional.of(new BlockAddress(resolved, (int)Math.floor(point.u()), (int)Math.floor(point.v())));
      } catch (IllegalArgumentException ignored) {
         return Optional.empty();
      }
   }

   private record BlockAddress(SphereWorldModel.SphereChunkAddress address, int x, int z) { }
}
