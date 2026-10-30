package com.yucareux.tellus.worldgen;

import com.google.common.base.Preconditions;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.worldgen.global.CubeNetAddressAdapter;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetRemoteProjection;
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
 * authority for terrain; ordinary planar chunks and derived cut-edge apron
 * chunks both read canonical face columns. Movement ownership is handled by
 * CubeNetRuntime.
 */
public final class CubeNetChunkGenerator extends ChunkGenerator implements CubeNetLodSurface {
   public static final int GEOMETRY_VERSION = 3;
   public static final Codec<CubeNetChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.intRange(GEOMETRY_VERSION, GEOMETRY_VERSION).fieldOf("geometry_version").forGetter(generator -> GEOMETRY_VERSION),
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
      Codec.intRange(16, 4064).fieldOf("height").forGetter(CubeNetChunkGenerator::getGenDepth),
      Codec.intRange(0, 35).optionalFieldOf("experimental_apron_chunks", CubeNetViewCoverage.DEFAULT_APRON_CHUNKS)
         .forGetter(generator -> generator.apronWidthChunks)
   ).apply(instance, (version, mapping, excludedRadius, transitionWidth, biome, scale, sea, min, height, apronChunks) -> {
      if (!SphereWorldModel.MAPPING_ID.equals(mapping)) {
         throw new IllegalArgumentException("unsupported spherical mapping: " + mapping);
      }
      return new CubeNetChunkGenerator(biome, scale, sea, min, height,
         new SphereSingularityPolicy.Config(excludedRadius, transitionWidth), null, apronChunks);
   }));

   private final int seaLevel;
   private final int minY;
   private final int height;
   private final SphereWorldModel model;
   private final CubeNetAddressAdapter adapter;
   private final CubeNetRemoteProjection remoteProjection;
   private final CubeNetApronResolver apronResolver;
   private final int apronWidthChunks;
   private final SphereFaceChunkGenerator[] faces;
   private final SphereTerrainMaterializer materializer;
   private final SphereSingularityPolicy.Config singularityConfig;
   private final SphereSingularityPolicy singularityPolicy;

   public CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height) {
      this(biome, scale, seaLevel, minY, height, SphereSingularityPolicy.DEFAULT_CONFIG, null,
         CubeNetViewCoverage.DEFAULT_APRON_CHUNKS);
   }

   CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height,
      SphereChunkSampler.PointSampler pointSampler) {
      this(biome, scale, seaLevel, minY, height, pointSampler, CubeNetViewCoverage.DEFAULT_APRON_CHUNKS);
   }

   CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height,
      SphereChunkSampler.PointSampler pointSampler, int apronChunks) {
      this(biome, scale, seaLevel, minY, height, SphereSingularityPolicy.DEFAULT_CONFIG, pointSampler, apronChunks);
   }

   private CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height,
      SphereSingularityPolicy.Config singularityConfig, SphereChunkSampler.PointSampler pointSampler, int apronChunks) {
      super(biome, ignored -> BiomeGenerationSettings.EMPTY);
      Preconditions.checkArgument(minY % 16 == 0 && height % 16 == 0 && height >= 16
         && minY >= -2032 && (long)minY + height <= 2032,
         "vertical range must be section-aligned within [-2032, 2032)");
      this.seaLevel = seaLevel;
      this.minY = minY;
      this.height = height;
      this.model = SphereWorldModel.forWorldScale(scale);
      this.adapter = new CubeNetAddressAdapter(this.model);
      Preconditions.checkArgument(apronChunks >= 0 && apronChunks <= 35
         && apronChunks * 16 <= this.model.config().faceBlocks(), "apron width exceeds supported face size");
      this.apronWidthChunks = apronChunks;
      this.remoteProjection = new CubeNetRemoteProjection(this.adapter.layout(), apronChunks * 16.0);
      this.apronResolver = new CubeNetApronResolver(this.adapter.layout(), Math.max(16, apronChunks * 16));
      this.materializer = new SphereTerrainMaterializer(scale, seaLevel);
      this.singularityConfig = singularityConfig;
      this.singularityPolicy = new SphereSingularityPolicy(this.model, singularityConfig);
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
   public CubeNetApronResolver apronResolver() { return this.apronResolver; }
   public boolean nativeApronEnabled() { return this.apronWidthChunks > 0; }
   public int apronWidthChunks() { return this.apronWidthChunks; }
   public double worldScale() { return this.model.config().worldScale(); }
   @Override public int minY() { return this.minY; }
   @Override public int maxY() { return this.minY + this.height; }
   SphereSingularityPolicy.Config singularityConfig() { return this.singularityConfig; }

   boolean admitsOrdinaryMaterialization(SphereWorldModel.SphereChunkAddress address) {
      return this.singularityPolicy.admitsOrdinaryMaterialization(address);
   }

   boolean admitsOrdinaryFacePoint(CubeNetLayout.FacePoint point) {
      double offset = this.model.config().faceBlocks() * 0.5;
      return this.singularityPolicy.admitsOrdinaryEnginePoint(
         point.face().index(), point.u() - offset, point.v() - offset);
   }

   /** Cheap distance gate before searching for the nearby visual boundary. */
   boolean nearOrdinaryBoundary(double x, double z, double searchRadiusBlocks) {
      try {
         var point = this.adapter.layout().fromPlanar(new CubeNetLayout.PlanarPoint(x, z));
         double offset = this.model.config().faceBlocks() * 0.5;
         var classification = this.singularityPolicy.classifyEnginePoint(
            point.face().index(), point.u() - offset, point.v() - offset);
         // Twice the nominal scale leaves room for the conformal map's local scale.
         return classification.status() == SphereSingularityPolicy.Status.ORDINARY
            && classification.nearestCornerDistanceMeters() - this.singularityConfig.transitionLimitMeters()
               <= (searchRadiusBlocks * 2.0 + 48.0) * this.model.nominalBlockScaleMeters();
      } catch (IllegalArgumentException ignored) {
         return false;
      }
   }

   /** Matches the existing native movement gate, including its chunk allowance. */
   boolean admitsBoundaryHintPoint(double x, double z) {
      if (!this.admitsOrdinaryPlanarPoint(x, z)) return false;
      if (!this.nativeApronEnabled()) return true;
      var address = this.address(new ChunkPos((int)Math.floor(x / 16.0), (int)Math.floor(z / 16.0)));
      return address.isEmpty() || this.admitsOrdinaryMaterialization(address.get());
   }

   boolean admitsOrdinaryPlanarPoint(double x, double z) {
      try {
         return admitsOrdinaryFacePoint(this.adapter.layout().fromPlanar(
            new CubeNetLayout.PlanarPoint(x, z)));
      } catch (IllegalArgumentException ignored) {
         var projection = this.apronResolver.resolve(x, z).orElse(null);
         return projection != null && admitsOrdinaryMaterialization(
            new SphereWorldModel.SphereChunkAddress(projection.targetFace().index(),
               Math.floorDiv(projection.blockX(), 16), Math.floorDiv(projection.blockZ(), 16)));
      }
   }

   public Optional<SphereWorldModel.SphereChunkAddress> address(ChunkPos pos) {
      return this.adapter.chunkAddress(pos.x, pos.z);
   }

   /** Surface preview independent of native chunk persistence, bounded by the configured apron. */
   @Override
   public Optional<List<CubeNetLodSurface.Layer>> sampleLodColumn(long minBlockX, long minBlockZ, int cellSize) {
      if (cellSize < 1 || Integer.bitCount(cellSize) != 1) throw new IllegalArgumentException("cellSize");
      if (cellSize > 1 && !this.remoteProjection.isUniformArea(minBlockX, minBlockZ, cellSize)) {
         return Optional.empty();
      }
      double x = minBlockX + (cellSize >> 1) + 0.5;
      double z = minBlockZ + (cellSize >> 1) + 0.5;
      var resolved = this.remoteProjection.resolve(x, z).orElse(null);
      if (resolved == null) return Optional.empty();
      var point = resolved.point();
      long blockX = (long)Math.floor(point.u());
      long blockZ = (long)Math.floor(point.v());
      var address = new SphereWorldModel.SphereChunkAddress(point.face().index(),
         Math.floorDiv(blockX, 16L), Math.floorDiv(blockZ, 16L));
      if (!this.singularityPolicy.admitsTerrainMaterialization(address)) return Optional.empty();
      // The fine path shares the exact cached columns used by native chunk
      // filling. Coarse LODs sample only their representative point.
      var data = cellSize == 1
         ? this.faces[address.face()].terrainGrid(address).column((int)(blockX & 15), (int)(blockZ & 15)).data()
         : this.faces[address.face()].terrainPoint(new SphereWorldModel.SphereBlockAddress(
            address.face(), blockX, blockZ), cellSize);
      // Missing elevation and unsupported vertical ranges must fail the
      // future, never turn into a successful all-air DH cache entry.
      var plan = this.materializer.plan(data, this.minY, this.maxY());
      return Optional.of(this.materializer.lodLayers(plan, this.minY, this.maxY()));
   }

   @Override
   public boolean isUniformArea(long minX, long minZ, long width) {
      if (!this.remoteProjection.isUniformArea(minX, minZ, width)) return false;
      // A chart can still contain one of the excluded cubed-sphere corner caps.
      // DH asks this method before allocating a coarse tile; if the cap is not
      // checked here, buildLod reaches an empty column and fails the whole tile.
      for (double dz : new double[]{0.5, width * 0.5, width - 0.5}) {
         for (double dx : new double[]{0.5, width * 0.5, width - 0.5}) {
            var resolved = this.remoteProjection.resolve(minX + dx, minZ + dz).orElse(null);
            if (resolved == null) return false;
            var point = resolved.point();
            long blockX = (long)Math.floor(point.u());
            long blockZ = (long)Math.floor(point.v());
            var address = new SphereWorldModel.SphereChunkAddress(point.face().index(),
               Math.floorDiv(blockX, 16L), Math.floorDiv(blockZ, 16L));
            if (!this.singularityPolicy.admitsTerrainMaterialization(address)) return false;
         }
      }
      return true;
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
      Optional<SphereWorldModel.SphereChunkAddress> materializable = this.address(chunk.getPos())
         .filter(this.singularityPolicy::admitsTerrainMaterialization);
      if (materializable.isPresent()) {
         return CompletableFuture.supplyAsync(() -> {
            this.fillFromCanonical(chunk, materializable.get());
            return chunk;
         }, executor);
      }
      // Do not overwrite excluded canonical chunks with a derived view.
      if (!this.nativeApronEnabled() || this.address(chunk.getPos()).isPresent()) {
         return CompletableFuture.completedFuture(chunk);
      }
      Optional<CubeNetApronResolver.ChunkProjection> projection = this.apronProjection(chunk.getPos());
      return projection
         .map(candidate -> CompletableFuture.supplyAsync(() -> {
            SphereWorldModel.SphereChunkAddress targetAddress = new SphereWorldModel.SphereChunkAddress(
               candidate.targetFace().index(), candidate.targetChunkX(), candidate.targetChunkZ());
            var canonical = this.faces[targetAddress.face()].terrainGrid(targetAddress);
            var columns = new ArrayList<SphereChunkSampler.Column>(256);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
               columns.add(canonical.column(candidate.targetBlockX(x, z) & 15, candidate.targetBlockZ(x, z) & 15));
            }
            this.materializer.fill(chunk, new SphereChunkSampler.TerrainGrid(targetAddress, columns));
            return chunk;
         }, executor))
         .orElseGet(() -> CompletableFuture.completedFuture(chunk));
   }

   public Optional<CubeNetApronResolver.ChunkProjection> apronProjection(ChunkPos pos) {
      if (!this.nativeApronEnabled() || this.address(pos).isPresent()) return Optional.empty();
      return this.apronResolver.resolveChunk(pos.x, pos.z)
         .filter(candidate -> this.singularityPolicy.admitsTerrainMaterialization(
            new SphereWorldModel.SphereChunkAddress(candidate.targetFace().index(),
               candidate.targetChunkX(), candidate.targetChunkZ())));
   }

   private void fillFromCanonical(ChunkAccess chunk, SphereWorldModel.SphereChunkAddress address) {
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
      if (address.isPresent() && this.singularityPolicy.admitsTerrainMaterialization(address.get())) try {
         CubeNetLayout.FacePoint point = this.adapter.layout().fromPlanar(
            new CubeNetLayout.PlanarPoint(x + 0.5, z + 0.5));
         SphereWorldModel.SphereChunkAddress resolved = address.get();
         if (point.face().index() != resolved.face()) return Optional.empty();
         return Optional.of(new BlockAddress(resolved, (int)Math.floor(point.u()), (int)Math.floor(point.v())));
      } catch (IllegalArgumentException ignored) {
         // Fall through to the derived seam apron below.
      }
      if (address.isPresent()) return Optional.empty();
      var apron = this.apronProjection(new ChunkPos(chunkX, chunkZ)).orElse(null);
      if (apron == null) return Optional.empty();
      int targetX = apron.targetBlockX(x & 15, z & 15);
      int targetZ = apron.targetBlockZ(x & 15, z & 15);
      SphereWorldModel.SphereChunkAddress resolved = new SphereWorldModel.SphereChunkAddress(
         apron.targetFace().index(), apron.targetChunkX(), apron.targetChunkZ());
      return Optional.of(new BlockAddress(resolved, targetX, targetZ));
   }

   private record BlockAddress(SphereWorldModel.SphereChunkAddress address, int x, int z) { }
}
