package com.yucareux.tellus.worldgen;

import com.google.common.base.Preconditions;
import com.mojang.serialization.Codec;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.world.data.canopy.TellusCanopyHeightSource;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import com.yucareux.tellus.worldgen.global.CubeNetAddressAdapter;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetSeamTransform;
import com.yucareux.tellus.worldgen.global.CubeNetRemoteProjection;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.CubeNetFeatureRasterizer;
import com.yucareux.tellus.worldgen.global.CubeNetRoadVerticalPolicy;
import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmStreetLightFeature;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadAreaFeature;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadMode;
import com.yucareux.tellus.world.data.osm.RoadSurfaceStyle;
import com.yucareux.tellus.world.data.osm.TellusOsmInfrastructureSource;
import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import com.yucareux.tellus.worldgen.building.BuildingBlueprint;
import com.yucareux.tellus.worldgen.building.TellusBuildingMaterials;
import com.yucareux.tellus.worldgen.building.TellusBuildingBlueprints;
import com.yucareux.tellus.worldgen.building.TellusBuildingProfiles;
import com.yucareux.tellus.worldgen.caves.CubeNetSphericalCaveSampler;
import com.yucareux.tellus.worldgen.caves.CubeNetSphericalCaveField;
import com.yucareux.tellus.worldgen.road.StreetLightModel;
import com.yucareux.tellus.worldgen.road.StreetLightPlanner;
import com.yucareux.tellus.worldgen.global.CubeNetFeatureProjector;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
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
   private static final Codec<BiomeSource> BIOME_SOURCE_CODEC = Codec.either(
      FixedBiomeSource.CODEC,
      CubeNetBiomeSource.CODEC.codec()
   ).xmap(
      either -> either.map(source -> (BiomeSource)source, source -> (BiomeSource)source),
      source -> source instanceof FixedBiomeSource fixed
         ? Either.left(fixed)
         : Either.right((CubeNetBiomeSource)source)
   );
   public static final Codec<CubeNetChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.intRange(GEOMETRY_VERSION, GEOMETRY_VERSION).fieldOf("geometry_version").forGetter(generator -> GEOMETRY_VERSION),
      Codec.STRING.fieldOf("mapping").forGetter(generator -> generator.model().mappingId()),
      Codec.DOUBLE.optionalFieldOf("excluded_radius_meters",
         SphereSingularityPolicy.DEFAULT_CONFIG.excludedRadiusMeters())
         .forGetter(generator -> generator.singularityConfig().excludedRadiusMeters()),
      Codec.DOUBLE.optionalFieldOf("transition_width_meters",
         SphereSingularityPolicy.DEFAULT_CONFIG.transitionWidthMeters())
         .forGetter(generator -> generator.singularityConfig().transitionWidthMeters()),
      BIOME_SOURCE_CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
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
      SphereSingularityPolicy.Config singularityConfig =
         new SphereSingularityPolicy.Config(excludedRadius, transitionWidth);
      if (biome instanceof CubeNetBiomeSource spherical
         && (Double.compare(spherical.worldScale(), scale) != 0
            || spherical.apronChunks() != apronChunks
            || !spherical.singularityConfig().equals(singularityConfig))) {
         throw new IllegalArgumentException("cube-net biome source does not match generator geometry");
      }
      FixedBiomeSource faceBiome = biome instanceof CubeNetBiomeSource spherical
         ? spherical.fallback()
         : (FixedBiomeSource)biome;
      return new CubeNetChunkGenerator(biome, faceBiome, scale, sea, min, height,
         singularityConfig, apronChunks);
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
   private volatile long worldSeed;
   private final CubeNetSphericalCaveSampler cubeNetSphericalCaveSampler = new CubeNetSphericalCaveSampler();
   private volatile long sphericalCaveSeed = Long.MIN_VALUE;
   private volatile CubeNetSphericalCaveField sphericalCaveField;
   private static final boolean CUBE_NET_CAVES = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.caves", "true")
   );
   private static final boolean CUBE_NET_ORE_VEINS = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.oreVeins", "true")
   );
   private static final boolean CUBE_NET_GEOLOGICAL_STONE = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.geologicalStonePatches", "true")
   );
   private static final boolean CUBE_NET_CAVES_REACH_SURFACE = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.cavesReachSurface", "false")
   );
   private static final int CUBE_NET_UNDERGROUND_DEPTH = Integer.getInteger(
      "tellus.cubeNet.undergroundDepth", 256
   );
   private static final boolean CUBE_NET_ROADS = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.roads", "true")
   );
   private static final boolean CUBE_NET_BUILDINGS = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.buildings", "true")
   );
   private final SphereSingularityPolicy.Config singularityConfig;
   private final SphereSingularityPolicy singularityPolicy;

   public CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height) {
      this((BiomeSource)biome, biome, scale, seaLevel, minY, height,
         SphereSingularityPolicy.DEFAULT_CONFIG, CubeNetViewCoverage.DEFAULT_APRON_CHUNKS);
   }

   CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height,
      SphereChunkSampler.PointSampler pointSampler) {
      this((BiomeSource)biome, biome, scale, seaLevel, minY, height,
         SphereSingularityPolicy.DEFAULT_CONFIG, pointSampler, CubeNetViewCoverage.DEFAULT_APRON_CHUNKS);
   }

   CubeNetChunkGenerator(FixedBiomeSource biome, double scale, int seaLevel, int minY, int height,
      SphereChunkSampler.PointSampler pointSampler, int apronChunks) {
      this((BiomeSource)biome, biome, scale, seaLevel, minY, height,
         SphereSingularityPolicy.DEFAULT_CONFIG, pointSampler, apronChunks);
   }

   private CubeNetChunkGenerator(BiomeSource biome, FixedBiomeSource faceBiome, double scale, int seaLevel, int minY, int height,
      SphereSingularityPolicy.Config singularityConfig, int apronChunks) {
      this(biome, faceBiome, scale, seaLevel, minY, height, singularityConfig, null, apronChunks);
   }

   private CubeNetChunkGenerator(BiomeSource biome, FixedBiomeSource faceBiome, double scale, int seaLevel, int minY, int height,
      SphereSingularityPolicy.Config singularityConfig, SphereChunkSampler.PointSampler pointSampler, int apronChunks) {
      super(biome, holder -> ((Biome)holder.value()).getGenerationSettings());
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
      SphereChunkSampler.PointSampler effectivePointSampler = pointSampler == null
         ? TellusWorldgenSources.cubeNetPointSampler(this.model, seaLevel) : pointSampler;
      this.faces = new SphereFaceChunkGenerator[CubeNetLayout.FACE_COUNT];
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         this.faces[face.index()] = new SphereFaceChunkGenerator(faceBiome, face.index(), scale, seaLevel, minY, height,
            effectivePointSampler, singularityConfig);
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

   @Override
   public ChunkGeneratorStructureState createState(
      HolderLookup<StructureSet> structureSets, RandomState randomState, long seed
   ) {
      this.worldSeed = seed;
      this.materializer.setWorldSeed(seed);
      for (SphereFaceChunkGenerator face : this.faces) face.setWorldSeed(seed);
      return ChunkGeneratorStructureState.createForNormal(randomState, seed, this.biomeSource, structureSets);
   }

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

   /**
    * Fetches OSM geometry in geodetic form for a canonical chunk. Callers that
    * rasterize the result must project every vertex with
    * {@code CubeNetFeatureProjector}; no global longitude/latitude plane is
    * valid near a cube-face seam.
    */
   public Optional<SphereChunkSampler.FeatureData> sampleFeatures(
      ChunkPos pos, double marginMeters, OsmQueryMode mode
   ) {
      Objects.requireNonNull(pos, "pos");
      Objects.requireNonNull(mode, "mode");
      SphereWorldModel.SphereChunkAddress address = this.address(pos)
         .filter(this.singularityPolicy::admitsTerrainMaterialization).orElse(null);
      if (address == null) return Optional.empty();
      return Optional.of(this.faces[address.face()].sampleFeatures(address, marginMeters, mode));
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
      var column = cellSize == 1
         ? this.faces[address.face()].terrainGrid(address).column((int)(blockX & 15), (int)(blockZ & 15))
         : this.faces[address.face()].terrainPoint(new SphereWorldModel.SphereBlockAddress(
            address.face(), blockX, blockZ), cellSize);
      // Missing elevation and unsupported vertical ranges must fail the
      // future, never turn into a successful all-air DH cache entry.
      var plan = this.materializer.plan(column.context(), this.minY, this.maxY());
      return Optional.of(this.materializer.lodLayers(plan, this.minY, this.maxY()));
   }

   @Override
   public boolean isUniformArea(long minX, long minZ, long width) {
      if (!this.remoteProjection.isUniformArea(minX, minZ, width)) {
         // DH's finest request is still one 16x16 block tile. It may straddle
         // two ordinary net charts, which is safe when every column has a
         // canonical/apron owner. Only split larger requests; otherwise a
         // legal chart boundary would remain WAIT forever.
         if (width > 16L) return false;
         for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            var resolved = this.remoteProjection.resolve(minX + x + 0.5, minZ + z + 0.5).orElse(null);
            if (resolved == null || !this.admitsTerrainSample(resolved)) return false;
         }
         return true;
      }
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

   private boolean admitsTerrainSample(CubeNetRemoteProjection.ResolvedPoint resolved) {
      var point = resolved.point();
      long blockX = (long)Math.floor(point.u());
      long blockZ = (long)Math.floor(point.v());
      var address = new SphereWorldModel.SphereChunkAddress(point.face().index(),
         Math.floorDiv(blockX, 16L), Math.floorDiv(blockZ, 16L));
      return this.singularityPolicy.admitsTerrainMaterialization(address);
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
      StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {
      if (step != GenerationStep.Carving.AIR) return;
      if (!CUBE_NET_CAVES && !CUBE_NET_ORE_VEINS && !CUBE_NET_GEOLOGICAL_STONE) return;
      SphereWorldModel.SphereChunkAddress address = this.address(chunk.getPos())
         .filter(this.singularityPolicy::admitsTerrainMaterialization).orElse(null);
      if (address == null) {
         this.applyCarversToApron(level, seed, random, structures, chunk);
         return;
      }
      this.applySphericalCarvers(chunk, address, seed, random, structures);
   }

   private void applySphericalCarvers(
      ChunkAccess chunk, SphereWorldModel.SphereChunkAddress address, long seed,
      RandomState random, StructureManager structures
   ) {
      UndergroundColumns columns = this.undergroundColumns(chunk.getPos(), address);
      this.cubeNetSphericalCaveSampler.apply(
         this.model,
         address,
         this.sphericalCaveField(seed),
         chunk,
         this.minY,
         this.seaLevel,
         CUBE_NET_CAVES,
         CUBE_NET_CAVES_REACH_SURFACE,
         CUBE_NET_ORE_VEINS,
         CUBE_NET_GEOLOGICAL_STONE,
         columns.surfaceY(),
         columns.surfaceSampler(),
         columns.floodGuardY(),
         columns.generationFloorY(),
         this.undergroundStructureExclusions(structures, chunk.getPos()),
         (written, pos, state, fluid) -> {
            MinecraftVersionCompat.setBlockState(written, pos, state);
            if (fluid && written instanceof ProtoChunk proto) {
               MinecraftVersionCompat.markPosForPostProcessing(proto, pos);
            }
         }
      );
   }

   private CubeNetSphericalCaveField sphericalCaveField(long seed) {
      CubeNetSphericalCaveField cached = this.sphericalCaveField;
      if (cached != null && this.sphericalCaveSeed == seed) return cached;
      synchronized (this) {
         cached = this.sphericalCaveField;
         if (cached == null || this.sphericalCaveSeed != seed) {
            cached = new CubeNetSphericalCaveField(seed);
            this.sphericalCaveField = cached;
            this.sphericalCaveSeed = seed;
         }
         return cached;
      }
   }

   private List<UndergroundStructureExclusion.Box> undergroundStructureExclusions(
      StructureManager structures, ChunkPos pos
   ) {
      if (structures == null) return List.of();
      List<UndergroundStructureExclusion.Box> exclusions = new ArrayList<>();
      Map<Long, Integer> surfaces = new HashMap<>();
      Map<Long, SphereChunkSampler.SurfaceData> columns = new HashMap<>();
      for (StructureStart start : WorldgenApi.structureStarts(structures, pos,
         structure -> UndergroundStructureExclusion.protectsProjectedCarving(structure.terrainAdaptation()))) {
         if (start == null || !start.isValid()) continue;
         BoundingBox bounds = start.getBoundingBox();
         int x = bounds.minX() + (bounds.maxX() - bounds.minX()) / 2;
         int z = bounds.minZ() + (bounds.maxZ() - bounds.minZ()) / 2;
         if (!UndergroundStructureExclusion.isUnderground(bounds.maxY(),
            this.surfaceYAtPlanar(x, z, surfaces, columns))) continue;
         var adjustment = start.getStructure().terrainAdaptation();
         int margin = UndergroundStructureProtection.usesBoundedCavernEnvelope(adjustment)
            ? UndergroundStructureProtection.CAVERN_PIECE_CARVER_MARGIN
            : UndergroundStructureExclusion.CARVER_MARGIN;
         for (StructurePiece piece : start.getPieces()) {
            BoundingBox b = piece.getBoundingBox();
            var box = new UndergroundStructureExclusion.Box(b.minX(), b.minY(), b.minZ(),
               b.maxX(), b.maxY(), b.maxZ(), margin,
               UndergroundStructureExclusion.protectsFeaturePlacement(adjustment));
            if (box.intersectsHorizontal(pos.getMinBlockX(), pos.getMinBlockZ(),
               pos.getMaxBlockX(), pos.getMaxBlockZ(), margin)) exclusions.add(box);
         }
      }
      return List.copyOf(exclusions);
   }

   /** Physical-face halo: guard policies do not depend on whether an apron view is enabled. */
   UndergroundColumns undergroundColumns(ChunkPos pos, SphereWorldModel.SphereChunkAddress address) {
      Map<Long, Integer> surfaceCache = new HashMap<>();
      Map<SphereWorldModel.SphereBlockAddress, SphereChunkSampler.SurfaceData> columnCache = new HashMap<>();
      var face = CubeNetLayout.Face.of(address.face());
      var placement = this.adapter.layout().placement(face);
      int chunkMinX = pos.getMinBlockX();
      int chunkMinZ = pos.getMinBlockZ();
      java.util.function.BiFunction<Integer, Integer, SphereChunkSampler.SurfaceData> sample = (x, z) -> {
         double dx = x - chunkMinX - 7.5;
         double dz = z - chunkMinZ - 7.5;
         return this.undergroundSurfaceData(face,
            address.blockX() + 8.0 + placement.uAxisX() * dx + placement.uAxisZ() * dz,
            address.blockZ() + 8.0 + placement.vAxisX() * dx + placement.vAxisZ() * dz, columnCache);
      };
      IntBinaryOperator surfaceSampler = (x, z) -> surfaceCache.computeIfAbsent(ChunkPos.asLong(x, z), key -> {
         var data = sample.apply(x, z);
         return data == null ? this.minY : this.materializer.plan(data, this.minY, this.maxY()).surfaceY();
      });
      int[] surfaceYByColumn = new int[256];
      int[] floodGuardYByColumn = new int[256];
      int[] generationFloorYByColumn = new int[256];
      Arrays.fill(floodGuardYByColumn, Integer.MAX_VALUE);
      for (int localZ = 0; localZ < 16; localZ++) {
         for (int localX = 0; localX < 16; localX++) {
            int index = (localZ << 4) | localX;
            int worldX = chunkMinX + localX;
            int worldZ = chunkMinZ + localZ;
            int surfaceY = surfaceSampler.applyAsInt(worldX, worldZ);
            surfaceYByColumn[index] = surfaceY;
            generationFloorYByColumn[index] = CubeNetUndergroundPolicy.generationFloorY(
               surfaceY, CUBE_NET_UNDERGROUND_DEPTH, this.minY
            );
            for (int dz = -CubeNetUndergroundPolicy.WATER_HALO; dz <= CubeNetUndergroundPolicy.WATER_HALO; dz++) {
               for (int dx = -CubeNetUndergroundPolicy.WATER_HALO; dx <= CubeNetUndergroundPolicy.WATER_HALO; dx++) {
                  SphereChunkSampler.SurfaceData water = sample.apply(worldX + dx, worldZ + dz);
                  if (water == null || !water.waterAvailable()) continue;
                  int waterY = this.materializer.height(water.waterSurfaceMeters());
                  int waterFloorY = this.materializer.height(water.elevationMeters());
                  int guardY = CubeNetUndergroundPolicy.floodGuardY(waterFloorY, waterY, water.oceanWater());
                  floodGuardYByColumn[index] = Math.min(floodGuardYByColumn[index], guardY);
               }
            }
         }
      }
      return new UndergroundColumns(surfaceYByColumn, floodGuardYByColumn, generationFloorYByColumn, surfaceSampler);
   }

   private SphereChunkSampler.SurfaceData undergroundSurfaceData(
      CubeNetLayout.Face face, double u, double v,
      Map<SphereWorldModel.SphereBlockAddress, SphereChunkSampler.SurfaceData> cache
   ) {
      double size = this.adapter.layout().faceSize();
      boolean outsideU = u < 0.0 || u >= size;
      boolean outsideV = v < 0.0 || v >= size;
      if (outsideU && outsideV) return null; // Existing vertex quarantine.
      if (outsideU || outsideV) {
         CubeNetLayout.Side side = outsideU
            ? u < 0.0 ? CubeNetLayout.Side.WEST : CubeNetLayout.Side.EAST
            : v < 0.0 ? CubeNetLayout.Side.NORTH : CubeNetLayout.Side.SOUTH;
         double penetration = outsideU ? u < 0.0 ? -u : u-size : v < 0.0 ? -v : v-size;
         var target = this.adapter.layout().mapAcross(face, side, outsideU ? v : u, penetration);
         face = target.face();
         u = target.u();
         v = target.v();
      }
      var block = new SphereWorldModel.SphereBlockAddress(face.index(), (long)Math.floor(u), (long)Math.floor(v));
      return cache.computeIfAbsent(block, key -> this.faces[key.face()].terrainPoint(key, 1).data());
   }

   record UndergroundColumns(int[] surfaceY, int[] floodGuardY, int[] generationFloorY, IntBinaryOperator surfaceSampler) { }

   /** Samples the canonical spherical owner, then mirrors every Y into the apron view. */
   private void applyCarversToApron(
      WorldGenRegion level, long seed, RandomState random, StructureManager structures, ChunkAccess apron
   ) {
      CubeNetApronResolver.ChunkProjection projection = this.apronProjection(apron.getPos()).orElse(null);
      if (projection == null) return;
      ChunkPos ownerPos = CubeNetApronChunkSync.canonicalChunkPosition(this.adapter.layout(), projection);
      this.applyCarversToApron(level.registryAccess().registryOrThrow(Registries.BIOME), seed, random,
         level.hasChunk(ownerPos.x, ownerPos.z) ? structures : null, apron, projection);
   }

   void applyCarversToApron(
      Registry<net.minecraft.world.level.biome.Biome> biomeRegistry, long seed, RandomState random,
      StructureManager structures, ChunkAccess apron, CubeNetApronResolver.ChunkProjection projection
   ) {
      SphereWorldModel.SphereChunkAddress ownerAddress = new SphereWorldModel.SphereChunkAddress(
         projection.targetFace().index(), projection.targetChunkX(), projection.targetChunkZ());
      ChunkPos ownerPos = CubeNetApronChunkSync.canonicalChunkPosition(this.adapter.layout(), projection);
      ProtoChunk owner = new ProtoChunk(ownerPos, UpgradeData.EMPTY,
         apron.getHeightAccessorForGeneration(), biomeRegistry, null);
      this.fillFromCanonical(owner, ownerAddress);
      this.applySphericalCarvers(owner, ownerAddress, seed, random, structures);

      CubeNetLayout.Seam seam = this.adapter.layout().seam(projection.sourceFace(), projection.sourceSide());
      net.minecraft.world.level.block.Rotation rotation = CubeNetApronChunkSync.rotation(
         new CubeNetSeamTransform(this.adapter.layout(), seam)
            .targetToSourceClockwiseQuarterTurns().orElseThrow());
      BlockPos.MutableBlockPos sourcePos = new BlockPos.MutableBlockPos();
      BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();
      for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
         int targetX = projection.targetBlockX(localX, localZ);
         int targetZ = projection.targetBlockZ(localX, localZ);
         CubeNetLayout.PlanarPoint ownerPoint = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
            projection.targetFace(), targetX + 0.5, targetZ + 0.5));
         int ownerX = (int)Math.floor(ownerPoint.x());
         int ownerZ = (int)Math.floor(ownerPoint.z());
         for (int y = this.minY; y < this.maxY(); y++) {
            sourcePos.set(ownerX, y, ownerZ);
            BlockState state = owner.getBlockState(sourcePos).rotate(rotation);
            int targetXWorld = apron.getPos().getMinBlockX() + localX;
            int targetZWorld = apron.getPos().getMinBlockZ() + localZ;
            targetPos.set(targetXWorld, y, targetZWorld);
            MinecraftVersionCompat.setBlockState(apron, targetPos, state);
            if (apron instanceof ProtoChunk proto && !state.getFluidState().isEmpty()) {
               MinecraftVersionCompat.markPosForPostProcessing(proto, targetPos);
            }
         }
      }
   }
   @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
      // Only canonical chunks own decoration. Aprons receive the complete
      // decorated owner view below, which keeps vanilla features, Tellus
      // features and structures on one spherical owner.
      if (this.address(chunk.getPos()).filter(this.singularityPolicy::admitsTerrainMaterialization).isPresent()) {
         super.applyBiomeDecoration(level, chunk, structures);
      }
      this.placeCubeNetFeatures(level, chunk);
      this.mirrorCanonicalStructureView(level, chunk);
   }
   @Override public void spawnOriginalMobs(WorldGenRegion level) {
      ChunkPos center = level.getCenter();
      WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
      random.setDecorationSeed(level.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
      WorldgenApi.spawnMobs(level, center, random);
   }
   @Override public void createStructures(RegistryAccess registry, ChunkGeneratorStructureState state,
      StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates) {
      // Structures have one canonical owner. Aprons receive the owner's block
      // projection later and never create a second start at the seam view.
      if (this.address(chunk.getPos()).filter(this.singularityPolicy::admitsTerrainMaterialization).isPresent()) {
         super.createStructures(registry, state, structures, chunk, templates);
      }
   }
   @Override public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
      if (this.address(chunk.getPos()).filter(this.singularityPolicy::admitsTerrainMaterialization).isPresent()) {
         super.createReferences(level, structures, chunk);
      }
   }

   private int surfaceYAtPlanar(
      int worldX,
      int worldZ,
      Map<Long, Integer> surfaceCache,
      Map<Long, SphereChunkSampler.SurfaceData> columnCache
   ) {
      long key = ChunkPos.asLong(worldX, worldZ);
      Integer cached = surfaceCache.get(key);
      if (cached != null) return cached;
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(worldX, worldZ, columnCache);
      int result = surface == null ? this.minY : this.materializer.plan(
         surface, this.minY, this.maxY()).surfaceY();
      surfaceCache.put(key, result);
      return result;
   }

   private SphereChunkSampler.SurfaceData surfaceDataAtPlanar(
      int worldX,
      int worldZ,
      Map<Long, SphereChunkSampler.SurfaceData> cache
   ) {
      long key = ChunkPos.asLong(worldX, worldZ);
      SphereChunkSampler.SurfaceData cached = cache.get(key);
      if (cached != null) return cached;
      SphereChunkSampler.SurfaceData result = this.resolveBlock(worldX, worldZ)
         .map(block -> {
            SphereWorldModel.SphereBlockAddress address = new SphereWorldModel.SphereBlockAddress(
               block.address().face(), block.x(), block.z());
            return this.faces[address.face()].terrainPoint(address, 1).data();
         })
         .orElse(null);
      if (result != null) cache.put(key, result);
      return result;
   }

   /**
    * Reuses OSM road/building data only after projecting every vertex through
    * the canonical face. Cache misses return no geometry, matching Tellus's
    * no-data contract; a later generation or reload can observe the cache.
    */
   private void placeCubeNetFeatures(WorldGenLevel level, ChunkAccess chunk) {
      if (!CUBE_NET_ROADS && !CUBE_NET_BUILDINGS) return;
      SphereWorldModel.SphereChunkAddress address = this.address(chunk.getPos())
         .filter(this.singularityPolicy::admitsTerrainMaterialization).orElse(null);
      CubeNetApronResolver.ChunkProjection apron = address == null ? this.apronProjection(chunk.getPos()).orElse(null) : null;
      if (address == null && apron == null) return;
      ChunkPos queryPos = address == null
         ? CubeNetApronChunkSync.canonicalChunkPosition(this.adapter.layout(), apron)
         : chunk.getPos();
      SphereWorldModel.SphereChunkAddress queryAddress = address == null
         ? new SphereWorldModel.SphereChunkAddress(apron.targetFace().index(), apron.targetChunkX(), apron.targetChunkZ())
         : address;
      SphereChunkSampler.FeatureData data = this.sampleFeatures(
         queryPos, 96.0, OsmQueryMode.NON_BLOCKING).orElse(null);
      if (data == null) return;
      CubeNetLayout.Face face = CubeNetLayout.Face.of(queryAddress.face());
      int minX = Math.toIntExact(queryAddress.blockX());
      int minZ = Math.toIntExact(queryAddress.blockZ());
      int maxX = minX + 15;
      int maxZ = minZ + 15;
      Map<Long, Integer> featureSurfaceCache = new HashMap<>();
      Map<Long, SphereChunkSampler.SurfaceData> featureColumnCache = new HashMap<>();
      if (CUBE_NET_ROADS) {
         for (RoadFeature road : data.roads().features()) {
            for (CubeNetFeatureRasterizer.FaceCell cell : CubeNetFeatureRasterizer.roadCells(
               this.model, this.adapter.layout(), road, face, minX, minZ, maxX, maxZ, this.worldScale())) {
               if (apron == null) this.placeCubeNetRoad(level, chunk, road, cell,
                  featureSurfaceCache, featureColumnCache);
               else this.placeCubeNetApronRoad(level, chunk, apron, road, cell,
                  featureSurfaceCache, featureColumnCache);
            }
         }
         for (RoadAreaFeature area : data.roadAreas().features()) {
            for (CubeNetFeatureRasterizer.FaceCell cell : CubeNetFeatureRasterizer.roadAreaCells(
               this.model, this.adapter.layout(), area, face, minX, minZ, maxX, maxZ)) {
               if (apron == null) this.placeCubeNetRoadArea(level, chunk, area, cell,
                  featureSurfaceCache, featureColumnCache);
               else this.placeCubeNetApronRoadArea(level, chunk, apron, area, cell,
                  featureSurfaceCache, featureColumnCache);
            }
         }
      }
      if (CUBE_NET_BUILDINGS) {
         for (OsmBuildingFeature building : data.buildings().features()) {
            if (apron == null) this.placeCubeNetBuilding(level, chunk, building, face, minX, minZ, maxX, maxZ);
            else this.placeCubeNetApronBuilding(level, chunk, apron, building, face, minX, minZ, maxX, maxZ);
         }
      }
      // Street lamps are generated by the same road-aware planner as the
      // planar generator.  Canonical face chunks own the fixture; apron chunks
      // receive it through the canonical view mirror above.
      if (apron == null && this.worldScale() <= StreetLightPlanner.MAX_SCALE && !data.roads().features().isEmpty()) {
         this.placeCubeNetStreetLights(level, chunk, queryAddress, data.roads().features());
      }
   }

   private void placeCubeNetRoadArea(WorldGenLevel level, ChunkAccess chunk,
      RoadAreaFeature area,
      CubeNetFeatureRasterizer.FaceCell cell, Map<Long, Integer> surfaceCache,
      Map<Long, SphereChunkSampler.SurfaceData> columnCache) {
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int worldX = (int)Math.floor(point.x());
      int worldZ = (int)Math.floor(point.z());
      int surfaceY = this.surfaceYAtPlanar(worldX, worldZ, surfaceCache, columnCache);
      this.placeFeatureBlock(level, chunk, cell, this.cubeNetRoadAreaState(area, cell.x(), cell.z()), surfaceY);
   }

   private void placeCubeNetApronRoadArea(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron,
      RoadAreaFeature area,
      CubeNetFeatureRasterizer.FaceCell cell, Map<Long, Integer> surfaceCache,
      Map<Long, SphereChunkSampler.SurfaceData> columnCache) {
      int[] local = this.sourceLocalForTarget(apron, cell.x(), cell.z());
      if (local == null) return;
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int surfaceY = this.surfaceYAtPlanar((int)Math.floor(point.x()), (int)Math.floor(point.z()),
         surfaceCache, columnCache);
      this.placeApronFeatureBlock(level, chunk, apron, cell,
         this.cubeNetRoadAreaState(area, cell.x(), cell.z()), surfaceY, local);
   }

   private void placeCubeNetStreetLights(
      WorldGenLevel level, ChunkAccess chunk, SphereWorldModel.SphereChunkAddress queryAddress,
      List<RoadFeature> sourceRoads
   ) {
      SphereWorldModel.GeoPoint center = this.model.exactSurfacePoint(queryAddress);
      GeodeticBounds bounds = GeodeticBounds.around(center, 160.0);
      TellusOsmInfrastructureSource.StreetLightQueryResult pointQuery =
         TellusWorldgenSources.osmInfrastructure().roadPointsForGeoAreaWithStatus(bounds, OsmQueryMode.NON_BLOCKING);
      if (pointQuery.features().isEmpty() && sourceRoads.stream().noneMatch(this::roadCanBeLit)) return;

      // Planner coordinates are a local Earth projection.  Normalize every
      // feature around the chunk longitude so a date-line road remains short.
      List<RoadFeature> roads = new ArrayList<>(sourceRoads.size());
      for (RoadFeature road : sourceRoads) roads.add(this.normalizeRoadForLongitude(road, center.longitude()));
      List<OsmStreetLightFeature> points = new ArrayList<>(pointQuery.features().size());
      for (OsmStreetLightFeature point : pointQuery.features()) {
         points.add(new OsmStreetLightFeature(point.featureId(),
            this.normalizeLongitude(point.longitude(), center.longitude()), point.latitude(), point.kind()));
      }
      this.placeCubeNetInfrastructurePoints(level, chunk, points);
      double centerX = EarthProjection.longitudeToBlockX(center.longitude(), this.worldScale());
      double centerZ = EarthProjection.latToBlockZ(center.latitude(), this.worldScale());
      if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)) return;
      int minX = safeIntFloor(centerX - 192.0);
      int minZ = safeIntFloor(centerZ - 192.0);
      int maxX = safeIntFloor(centerX + 192.0);
      int maxZ = safeIntFloor(centerZ + 192.0);
      int factor = this.worldScale() <= 1.0 ? 2 : 1;
      List<StreetLightPlanner.Lamp> lamps = StreetLightPlanner.plan(
         roads, points, this.worldScale(), 8 * factor, 5 * factor, 3 * factor,
         minX, minZ, maxX, maxZ);
      for (StreetLightPlanner.Lamp lamp : lamps) {
         this.placeProjectedLamp(level, chunk, lamp, center.longitude());
      }
   }

   private void placeCubeNetInfrastructurePoints(WorldGenLevel level, ChunkAccess chunk,
      List<OsmStreetLightFeature> points) {
      for (OsmStreetLightFeature point : points) {
         if (point.kind() == null || point.kind() == com.yucareux.tellus.world.data.osm.RoadPointKind.STREET_LIGHT) continue;
         CubeNetFeatureProjector.Projection projection = CubeNetFeatureProjector.project(
            this.model, this.adapter.layout(), point.latitude(), point.longitude()).orElse(null);
         if (projection == null || !this.chunkOwnsPlanarPoint(chunk, projection.planarX(), projection.planarZ())) continue;
         int worldX = (int)Math.floor(projection.planarX());
         int worldZ = (int)Math.floor(projection.planarZ());
         int surfaceY = this.surfaceYAtPlanar(worldX, worldZ, new HashMap<>(), new HashMap<>());
         BlockState state = switch (point.kind()) {
            case TRAFFIC_SIGNAL -> Blocks.REDSTONE_TORCH.defaultBlockState();
            case BUS_STOP -> Blocks.OAK_SIGN.defaultBlockState();
            case CROSSING -> Blocks.WHITE_CARPET.defaultBlockState();
            case BENCH -> Blocks.OAK_STAIRS.defaultBlockState();
            case WASTE_BASKET -> Blocks.CAULDRON.defaultBlockState();
            case RECYCLING -> Blocks.COMPOSTER.defaultBlockState();
            case BICYCLE_PARKING -> Blocks.IRON_BARS.defaultBlockState();
            case FOUNTAIN -> Blocks.WATER.defaultBlockState();
            case BOLLARD -> Blocks.COBBLESTONE_WALL.defaultBlockState();
            default -> null;
         };
         if (state == null) continue;
         BlockPos target = new BlockPos(worldX, surfaceY + 1, worldZ);
         if (target.getY() >= this.minY && target.getY() < this.maxY()
            && level.getBlockState(target).isAir()) {
            level.setBlock(target, state, 2);
         }
      }
   }

   private boolean roadCanBeLit(RoadFeature road) {
      return road.mode() == RoadMode.NORMAL && road.roadClass() != RoadClass.DIRT;
   }

   private void placeProjectedLamp(WorldGenLevel level, ChunkAccess chunk,
      StreetLightPlanner.Lamp lamp, double centerLongitude) {
      double longitude = this.normalizeLongitude(
         EarthProjection.blockXToLongitude(lamp.worldX(), this.worldScale()), centerLongitude);
      double latitude = EarthProjection.blockZToLat(lamp.worldZ(), this.worldScale());
      CubeNetFeatureProjector.Projection projection = CubeNetFeatureProjector.project(
         this.model, this.adapter.layout(), latitude, longitude).orElse(null);
      if (projection == null || !this.chunkOwnsPlanarPoint(chunk, projection.planarX(), projection.planarZ())) return;
      int worldX = (int)Math.floor(projection.planarX());
      int worldZ = (int)Math.floor(projection.planarZ());
      int baseY = this.surfaceYAtPlanar(worldX, worldZ, new HashMap<>(), new HashMap<>());
      for (StreetLightModel.Part part : StreetLightModel.parts(lamp, baseY)) {
         double partLon = this.normalizeLongitude(
            EarthProjection.blockXToLongitude(part.position().getX(), this.worldScale()), centerLongitude);
         double partLat = EarthProjection.blockZToLat(part.position().getZ(), this.worldScale());
         CubeNetFeatureProjector.Projection partProjection = CubeNetFeatureProjector.project(
            this.model, this.adapter.layout(), partLat, partLon).orElse(null);
         if (partProjection == null || !this.chunkOwnsPlanarPoint(chunk, partProjection.planarX(), partProjection.planarZ())) continue;
         BlockPos target = new BlockPos((int)Math.floor(partProjection.planarX()), part.position().getY(),
            (int)Math.floor(partProjection.planarZ()));
         if (target.getY() >= this.minY && target.getY() < this.maxY()
            && level.getBlockState(target).isAir()) {
            level.setBlock(target, part.state(), 2);
         }
      }
   }

   private boolean chunkOwnsPlanarPoint(ChunkAccess chunk, double x, double z) {
      return Math.floorDiv((int)Math.floor(x), 16) == chunk.getPos().x
         && Math.floorDiv((int)Math.floor(z), 16) == chunk.getPos().z;
   }

   private RoadFeature normalizeRoadForLongitude(RoadFeature road, double centerLongitude) {
      double[] longitudes = road.longitudes();
      for (int i = 0; i < longitudes.length; i++) longitudes[i] = this.normalizeLongitude(longitudes[i], centerLongitude);
      return new RoadFeature(road.wayId(), road.roadClass(), road.mode(), road.bridgeLevel(), road.highwayTag(),
         road.roadSurface(), road.subclass(), road.widthMeters(), road.lanes(), road.laneMarkings(),
         longitudes, road.latitudes());
   }

   private double normalizeLongitude(double longitude, double centerLongitude) {
      if (!Double.isFinite(longitude) || !Double.isFinite(centerLongitude)) return longitude;
      double normalized = longitude;
      while (normalized - centerLongitude > 180.0) normalized -= 360.0;
      while (normalized - centerLongitude < -180.0) normalized += 360.0;
      return normalized;
   }

   private static int safeIntFloor(double value) {
      if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
      if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
      return (int)Math.floor(value);
   }

   private void placeCubeNetRoad(WorldGenLevel level, ChunkAccess chunk, RoadFeature road,
      CubeNetFeatureRasterizer.FaceCell cell, Map<Long, Integer> surfaceCache,
      Map<Long, SphereChunkSampler.SurfaceData> columnCache) {
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int worldX = (int)Math.floor(point.x());
      int worldZ = (int)Math.floor(point.z());
      int terrainSurface = this.surfaceYAtPlanar(worldX, worldZ, surfaceCache, columnCache);
      BlockState roadState = this.cubeNetRoadState(road, cell.x(), cell.z());
      switch (road.mode()) {
         case NORMAL -> this.placeFeatureBlock(level, chunk, cell, roadState);
         case BRIDGE -> {
            int deckY = CubeNetRoadVerticalPolicy.bridgeDeckY(terrainSurface, road.bridgeLevel());
            this.placeFeatureBlock(level, chunk, cell, roadState, deckY);
            if ((Math.floorMod(worldX, 8) == 0 && Math.floorMod(worldZ, 8) == 0)
               && deckY > terrainSurface + 1) {
               for (int y = terrainSurface + 1; y < deckY; y++) {
                  this.placeFeatureBlock(level, chunk, cell, Blocks.QUARTZ_PILLAR.defaultBlockState(), y);
               }
            }
         }
         case TUNNEL -> {
            int deckY = CubeNetRoadVerticalPolicy.tunnelDeckY(terrainSurface);
            for (int y = deckY; y < terrainSurface; y++) {
               this.placeFeatureBlock(level, chunk, cell, Blocks.AIR.defaultBlockState(), y);
            }
            this.placeFeatureBlock(level, chunk, cell, roadState, deckY);
         }
      }
   }

   private void placeCubeNetApronRoad(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, RoadFeature road,
      CubeNetFeatureRasterizer.FaceCell cell, Map<Long, Integer> surfaceCache,
      Map<Long, SphereChunkSampler.SurfaceData> columnCache) {
      int[] local = this.sourceLocalForTarget(apron, cell.x(), cell.z());
      if (local == null) return;
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int terrainSurface = this.surfaceYAtPlanar((int)Math.floor(point.x()), (int)Math.floor(point.z()),
         surfaceCache, columnCache);
      BlockState roadState = this.cubeNetRoadState(road, cell.x(), cell.z());
      switch (road.mode()) {
         case NORMAL -> this.placeApronFeatureBlock(level, chunk, apron, cell, roadState);
         case BRIDGE -> {
            int deckY = CubeNetRoadVerticalPolicy.bridgeDeckY(terrainSurface, road.bridgeLevel());
            this.placeApronFeatureBlock(level, chunk, apron, cell, roadState, deckY, local);
            int worldX = (int)Math.floor(point.x());
            int worldZ = (int)Math.floor(point.z());
            if (Math.floorMod(worldX, 8) == 0 && Math.floorMod(worldZ, 8) == 0
               && deckY > terrainSurface + 1) {
               for (int y = terrainSurface + 1; y < deckY; y++) {
                  this.placeApronFeatureBlock(level, chunk, apron, cell,
                     Blocks.QUARTZ_PILLAR.defaultBlockState(), y, local);
               }
            }
         }
         case TUNNEL -> {
            int deckY = CubeNetRoadVerticalPolicy.tunnelDeckY(terrainSurface);
            for (int y = deckY; y < terrainSurface; y++) {
               this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.AIR.defaultBlockState(), y, local);
            }
            this.placeApronFeatureBlock(level, chunk, apron, cell, roadState, deckY, local);
         }
      }
   }

   /** Mirrors the already-generated owner when the worldgen region contains it. */
   private void mirrorCanonicalStructureView(WorldGenLevel level, ChunkAccess chunk) {
      if (!(level instanceof WorldGenRegion region) || this.address(chunk.getPos()).isPresent()) return;
      CubeNetApronResolver.ChunkProjection projection = this.apronProjection(chunk.getPos()).orElse(null);
      if (projection == null) return;
      ChunkPos ownerPos = CubeNetApronChunkSync.canonicalChunkPosition(this.adapter.layout(), projection);
      if (!region.hasChunk(ownerPos.x, ownerPos.z)) return;
      ChunkAccess owner = region.getChunk(ownerPos.x, ownerPos.z);
      CubeNetLayout.Seam seam = this.adapter.layout().seam(projection.sourceFace(), projection.sourceSide());
      net.minecraft.world.level.block.Rotation rotation = CubeNetApronChunkSync.rotation(
         new CubeNetSeamTransform(this.adapter.layout(), seam)
            .targetToSourceClockwiseQuarterTurns().orElseThrow());
      BlockPos.MutableBlockPos sourcePos = new BlockPos.MutableBlockPos();
      BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();
      for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
         int targetX = projection.targetBlockX(localX, localZ);
         int targetZ = projection.targetBlockZ(localX, localZ);
         CubeNetLayout.PlanarPoint ownerPoint = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
            projection.targetFace(), targetX + 0.5, targetZ + 0.5));
         int ownerX = (int)Math.floor(ownerPoint.x());
         int ownerZ = (int)Math.floor(ownerPoint.z());
         for (int y = this.minY; y < this.maxY(); y++) {
            sourcePos.set(ownerX, y, ownerZ);
            targetPos.set(chunk.getPos().getMinBlockX() + localX, y,
               chunk.getPos().getMinBlockZ() + localZ);
            BlockState state = owner.getBlockState(sourcePos).rotate(rotation);
            MinecraftVersionCompat.setBlockState(chunk, targetPos, state);
            if (chunk instanceof ProtoChunk proto && !state.getFluidState().isEmpty()) {
               MinecraftVersionCompat.markPosForPostProcessing(proto, targetPos);
            }
         }
      }
   }

   private void placeCubeNetApronBuilding(
      WorldGenLevel level, ChunkAccess chunk, CubeNetApronResolver.ChunkProjection apron,
      OsmBuildingFeature building, CubeNetLayout.Face face, int minX, int minZ, int maxX, int maxZ
   ) {
      List<CubeNetFeatureRasterizer.FaceCell> cells = CubeNetFeatureRasterizer.buildingCells(
         this.model, this.adapter.layout(), building, face, minX - 1, minZ - 1, maxX + 1, maxZ + 1);
      if (cells.isEmpty()) return;
      var centroid = com.yucareux.tellus.worldgen.global.CubeNetFeatureProjector.project(
         this.model, this.adapter.layout(), building.centroidLat(), building.centroidLon()).orElse(null);
      if (centroid == null || centroid.face() != face.index()) return;
      int groundY;
      BuildingProfile profile;
      BuildingBlueprint blueprint;
      TellusBuildingMaterials.BuildingMaterialPalette palette;
      int height;
      try {
         groundY = this.surfaceYAtPlanar((int)Math.floor(centroid.planarX()), (int)Math.floor(centroid.planarZ()),
            new HashMap<>(), new HashMap<>());
         profile = TellusBuildingProfiles.resolveProfile(building, this.worldScale(), null, false);
         blueprint = TellusBuildingBlueprints.createExterior(
            "cube-net:" + building.featureId(), building, profile, this.worldSeed,
            groundY, groundY, groundY + Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale())),
            groundY + Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale())), this.worldScale());
         palette = TellusBuildingMaterials.resolvePalette(blueprint);
         height = Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale()));
      } catch (RuntimeException unavailableTerrain) {
         return;
      }
      java.util.Set<Long> occupied = new java.util.HashSet<>();
      for (var cell : cells) occupied.add(packFaceCell(cell.x(), cell.z()));
      for (var cell : cells) {
         if (cell.x() < minX || cell.x() > maxX || cell.z() < minZ || cell.z() > maxZ) continue;
         boolean boundary = !occupied.contains(packFaceCell(cell.x() - 1, cell.z()))
            || !occupied.contains(packFaceCell(cell.x() + 1, cell.z()))
            || !occupied.contains(packFaceCell(cell.x(), cell.z() - 1))
            || !occupied.contains(packFaceCell(cell.x(), cell.z() + 1));
         int[] local = this.sourceLocalForTarget(apron, cell.x(), cell.z());
         if (local == null) continue;
         for (int y = groundY; y <= groundY + height; y++) {
            int[] earth = this.earthCoordinates(cell);
            int boundaryDistance = boundary ? 0 : 2;
            int floorIndex = blueprint.floorIndexAtY(y);
            BlockState state = y == groundY ? palette.floor()
               : y >= blueprint.roofBaseY()
                  ? TellusBuildingMaterials.resolveLodRoofBlock(blueprint, palette, boundaryDistance, earth[0], earth[1])
                  : boundary
                     ? TellusBuildingMaterials.resolveLodFacadeBlock(blueprint, palette, boundaryDistance,
                        earth[0], earth[1], floorIndex)
                     : Blocks.AIR.defaultBlockState();
            this.placeApronFeatureBlock(level, chunk, apron, cell, state, y, local);
         }
      }
   }

   private void placeCubeNetBuilding(
      WorldGenLevel level, ChunkAccess chunk, OsmBuildingFeature building, CubeNetLayout.Face face,
      int minX, int minZ, int maxX, int maxZ
   ) {
      List<CubeNetFeatureRasterizer.FaceCell> cells = CubeNetFeatureRasterizer.buildingCells(
         this.model, this.adapter.layout(), building, face, minX - 1, minZ - 1, maxX + 1, maxZ + 1);
      if (cells.isEmpty()) return;
      SphereWorldModel.GeoPoint centroid = new SphereWorldModel.GeoPoint(
         building.centroidLat(), building.centroidLon(), 0.0);
      var centroidProjection = com.yucareux.tellus.worldgen.global.CubeNetFeatureProjector.project(
         this.model, this.adapter.layout(), centroid.latitude(), centroid.longitude()).orElse(null);
      if (centroidProjection == null || centroidProjection.face() != face.index()) return;
      int groundY;
      BuildingProfile profile;
      BuildingBlueprint blueprint;
      TellusBuildingMaterials.BuildingMaterialPalette palette;
      int height;
      try {
         groundY = this.surfaceYAtPlanar(
            (int)Math.floor(centroidProjection.planarX()), (int)Math.floor(centroidProjection.planarZ()),
            new HashMap<>(), new HashMap<>());
         profile = TellusBuildingProfiles.resolveProfile(building, this.worldScale(), null, false);
         blueprint = TellusBuildingBlueprints.createExterior(
            "cube-net:" + building.featureId(), building, profile, this.worldSeed,
            groundY, groundY, groundY + Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale())),
            groundY + Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale())), this.worldScale());
         palette = TellusBuildingMaterials.resolvePalette(blueprint);
         height = Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale()));
      } catch (RuntimeException unavailableTerrain) {
         return;
      }
      java.util.Set<Long> occupied = new java.util.HashSet<>();
      for (CubeNetFeatureRasterizer.FaceCell cell : cells) occupied.add(packFaceCell(cell.x(), cell.z()));
      for (CubeNetFeatureRasterizer.FaceCell cell : cells) {
         if (cell.x() < minX || cell.x() > maxX || cell.z() < minZ || cell.z() > maxZ) continue;
         boolean boundary = !occupied.contains(packFaceCell(cell.x() - 1, cell.z()))
            || !occupied.contains(packFaceCell(cell.x() + 1, cell.z()))
            || !occupied.contains(packFaceCell(cell.x(), cell.z() - 1))
            || !occupied.contains(packFaceCell(cell.x(), cell.z() + 1));
         for (int y = groundY; y <= groundY + height; y++) {
            int[] earth = this.earthCoordinates(cell);
            int boundaryDistance = boundary ? 0 : 2;
            int floorIndex = blueprint.floorIndexAtY(y);
            BlockState state = y == groundY ? palette.floor()
               : y >= blueprint.roofBaseY()
                  ? TellusBuildingMaterials.resolveLodRoofBlock(blueprint, palette, boundaryDistance, earth[0], earth[1])
                  : boundary
                     ? TellusBuildingMaterials.resolveLodFacadeBlock(blueprint, palette, boundaryDistance,
                        earth[0], earth[1], floorIndex)
                     : Blocks.AIR.defaultBlockState();
            this.placeFeatureBlock(level, chunk, cell, state, y);
         }
      }
   }

   private void placeFeatureBlock(WorldGenLevel level, ChunkAccess chunk,
      CubeNetFeatureRasterizer.FaceCell cell, BlockState state) {
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int worldX = (int)Math.floor(point.x());
      int worldZ = (int)Math.floor(point.z());
      int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
      this.placeFeatureBlock(level, chunk, cell, state, y + 1);
   }

   private void placeFeatureBlock(WorldGenLevel level, ChunkAccess chunk,
      CubeNetFeatureRasterizer.FaceCell cell, BlockState state, int y) {
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int worldX = (int)Math.floor(point.x());
      int worldZ = (int)Math.floor(point.z());
      if (Math.floorDiv(worldX, 16) != chunk.getPos().x || Math.floorDiv(worldZ, 16) != chunk.getPos().z
         || y < this.minY || y >= this.maxY()) return;
      BlockPos pos = new BlockPos(worldX, y, worldZ);
      level.setBlock(pos, state, 2);
   }

   private void placeApronFeatureBlock(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, CubeNetFeatureRasterizer.FaceCell cell, BlockState state) {
      int[] local = this.sourceLocalForTarget(apron, cell.x(), cell.z());
      if (local == null) return;
      int worldX = chunk.getPos().getMinBlockX() + local[0];
      int worldZ = chunk.getPos().getMinBlockZ() + local[1];
      int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
      this.placeApronFeatureBlock(level, chunk, apron, cell, state, y + 1, local);
   }

   private void placeApronFeatureBlock(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, CubeNetFeatureRasterizer.FaceCell cell,
      BlockState state, int y, int[] local) {
      if (y < this.minY || y >= this.maxY()) return;
      BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + local[0], y,
         chunk.getPos().getMinBlockZ() + local[1]);
      level.setBlock(pos, state, 2);
   }

   private int[] sourceLocalForTarget(CubeNetApronResolver.ChunkProjection apron, int targetX, int targetZ) {
      for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
         if (apron.targetBlockX(localX, localZ) == targetX && apron.targetBlockZ(localX, localZ) == targetZ) {
            return new int[]{localX, localZ};
         }
      }
      return null;
   }

   private BlockState cubeNetRoadState(RoadFeature road, int x, int z) {
      return switch (RoadSurfaceStyle.surfaceStyleId(road, x, z)) {
         case RoadSurfaceStyle.STYLE_PAVED_LIGHT, RoadSurfaceStyle.STYLE_CONCRETE -> TellusBlockReferences.concreteState("LIGHT_GRAY");
         case RoadSurfaceStyle.STYLE_PAVED_SMOOTH, RoadSurfaceStyle.STYLE_PEDESTRIAN -> Blocks.SMOOTH_STONE.defaultBlockState();
         case RoadSurfaceStyle.STYLE_GRAVEL -> Blocks.GRAVEL.defaultBlockState();
         case RoadSurfaceStyle.STYLE_DIRT -> Blocks.DIRT_PATH.defaultBlockState();
         case RoadSurfaceStyle.STYLE_COBBLESTONE -> Blocks.COBBLESTONE.defaultBlockState();
         case RoadSurfaceStyle.STYLE_STONE_PAVERS -> Blocks.STONE_BRICKS.defaultBlockState();
         case RoadSurfaceStyle.STYLE_BRICK -> Blocks.BRICKS.defaultBlockState();
         case RoadSurfaceStyle.STYLE_SAND -> Blocks.SANDSTONE.defaultBlockState();
         case RoadSurfaceStyle.STYLE_WOOD -> Blocks.OAK_PLANKS.defaultBlockState();
         default -> switch (road.roadClass()) {
            case MAIN -> TellusBlockReferences.concreteState("GRAY");
            case NORMAL -> Blocks.SMOOTH_STONE.defaultBlockState();
            case DIRT -> Blocks.DIRT_PATH.defaultBlockState();
         };
      };
   }

   private BlockState cubeNetRoadAreaState(RoadAreaFeature area, int x, int z) {
      return switch (RoadSurfaceStyle.surfaceStyleId(
         area.roadClass(), area.highwayTag(), area.roadSurface(), area.subclass(), x, z)) {
         case RoadSurfaceStyle.STYLE_PAVED_LIGHT, RoadSurfaceStyle.STYLE_CONCRETE ->
            TellusBlockReferences.concreteState("LIGHT_GRAY");
         case RoadSurfaceStyle.STYLE_PAVED_SMOOTH, RoadSurfaceStyle.STYLE_PEDESTRIAN ->
            Blocks.SMOOTH_STONE.defaultBlockState();
         case RoadSurfaceStyle.STYLE_GRAVEL -> Blocks.GRAVEL.defaultBlockState();
         case RoadSurfaceStyle.STYLE_DIRT -> Blocks.DIRT_PATH.defaultBlockState();
         case RoadSurfaceStyle.STYLE_COBBLESTONE -> Blocks.COBBLESTONE.defaultBlockState();
         case RoadSurfaceStyle.STYLE_STONE_PAVERS -> Blocks.STONE_BRICKS.defaultBlockState();
         case RoadSurfaceStyle.STYLE_BRICK -> Blocks.BRICKS.defaultBlockState();
         case RoadSurfaceStyle.STYLE_SAND -> Blocks.SANDSTONE.defaultBlockState();
         case RoadSurfaceStyle.STYLE_WOOD -> Blocks.OAK_PLANKS.defaultBlockState();
         default -> switch (area.roadClass()) {
            case MAIN -> TellusBlockReferences.concreteState("GRAY");
            case NORMAL -> Blocks.SMOOTH_STONE.defaultBlockState();
            case DIRT -> Blocks.DIRT_PATH.defaultBlockState();
         };
      };
   }

   private static long packFaceCell(int x, int z) {
      return (long)x << 32 ^ (z & 0xffffffffL);
   }

   private int[] earthCoordinates(CubeNetFeatureRasterizer.FaceCell cell) {
      SphereWorldModel.GeoPoint point = this.model.exactSurfacePoint(
         cell.face(), cell.x() + 0.5, cell.z() + 0.5);
      return new int[]{
         coordinateForEarth(EarthProjection.longitudeToBlockX(point.longitude(), this.worldScale())),
         coordinateForEarth(EarthProjection.latToBlockZ(point.latitude(), this.worldScale()))
      };
   }

   private static int coordinateForEarth(double coordinate) {
      if (!Double.isFinite(coordinate)) return 0;
      if (coordinate <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
      if (coordinate >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
      return (int)Math.floor(coordinate);
   }

   /**
    * Reuses Tellus's canopy-height and procedural-tree rules at canonical face
    * anchors. Apron chunks are derived views and never own decoration, so a
    * tree cannot be written twice at a cut edge.
    */
   private void placeCubeNetTrees(WorldGenLevel level, ChunkAccess chunk) {
      if (!(this.biomeSource instanceof CubeNetBiomeSource)) return;
      SphereWorldModel.SphereChunkAddress address = this.address(chunk.getPos())
         .filter(this.singularityPolicy::admitsTerrainMaterialization).orElse(null);
      if (address == null) return;
      int face = address.face();
      int cellSize = TellusProceduralTreeGenerator.PLACEMENT_CELL_SIZE;
      int minFaceX = Math.toIntExact(address.blockX());
      int minFaceZ = Math.toIntExact(address.blockZ());
      int maxFaceX = minFaceX + 15;
      int maxFaceZ = minFaceZ + 15;
      int minCellX = Math.floorDiv(minFaceX, cellSize) - 1;
      int maxCellX = Math.floorDiv(maxFaceX, cellSize) + 1;
      int minCellZ = Math.floorDiv(minFaceZ, cellSize) - 1;
      int maxCellZ = Math.floorDiv(maxFaceZ, cellSize) + 1;
      SphereChunkSampler.TerrainGrid grid = this.faces[face].terrainGrid(address);
      TellusCanopyHeightSource canopy = TellusWorldgenSources.canopyHeight();
      for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
         for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            TellusProceduralTreeGenerator.TreeAnchor anchor = TellusProceduralTreeGenerator.anchorForCell(
               cellX, cellZ, this.worldSeed ^ (long)face * 0x9E3779B97F4A7C15L
            );
            int faceX = anchor.worldX();
            int faceZ = anchor.worldZ();
            if (faceX < minFaceX || faceX > maxFaceX || faceZ < minFaceZ || faceZ > maxFaceZ) continue;
            CubeNetLayout.PlanarPoint planar = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
               CubeNetLayout.Face.of(face), faceX + 0.5, faceZ + 0.5));
            int worldX = (int)Math.floor(planar.x());
            int worldZ = (int)Math.floor(planar.z());
            if (Math.floorDiv(worldX, 16) != chunk.getPos().x || Math.floorDiv(worldZ, 16) != chunk.getPos().z) continue;
            SphereChunkSampler.Column column = grid.column(Math.floorMod(faceX, 16), Math.floorMod(faceZ, 16));
            SphereChunkSampler.SurfaceData surface = column.data();
            if (surface.coverClass() != MountainSurfaceRules.ESA_TREE_COVER
               || surface.waterAvailable() || !surface.elevationAvailable()) continue;
            SphereTerrainMaterializer.ColumnPlan plan = this.materializer.plan(
               column.context(), this.minY, this.maxY()
            );
            if (plan.surfaceY() < this.seaLevel) continue;
            BlockPos ground = new BlockPos(worldX, plan.surfaceY(), worldZ);
            if (!level.getBlockState(ground).is(Blocks.GRASS_BLOCK)
               && !level.getBlockState(ground).is(Blocks.DIRT)
               && !level.getBlockState(ground).is(Blocks.MUD)) continue;
            TellusCanopyHeightSource.CanopySample sample = canopy.sampleCanopy(
               column.point(), this.worldScale(), this.worldScale()
            );
            TellusProceduralTreeGenerator.place(
               level, ground, level.getBiome(ground.above()), sample, anchor.seed()
            );
         }
      }
   }

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
