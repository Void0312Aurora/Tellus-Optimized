package com.yucareux.tellus.worldgen;

import com.google.common.base.Preconditions;
import com.mojang.datafixers.util.Pair;
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
import com.yucareux.tellus.worldgen.global.SphereColumnContext;
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
import com.yucareux.tellus.world.data.osm.RoadPointKind;
import com.yucareux.tellus.world.data.osm.RoadSurfaceStyle;
import com.yucareux.tellus.world.data.osm.TellusOsmInfrastructureSource;
import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.worldgen.building.BuildingProfile;
import com.yucareux.tellus.worldgen.building.BuildingBlueprint;
import com.yucareux.tellus.worldgen.building.BuildingFloorPlan;
import com.yucareux.tellus.worldgen.building.TellusBuildingMaterials;
import com.yucareux.tellus.worldgen.building.TellusBuildingBlueprints;
import com.yucareux.tellus.worldgen.building.TellusBuildingInteriors;
import com.yucareux.tellus.worldgen.building.TellusBuildingProfiles;
import com.yucareux.tellus.worldgen.building.TellusBuildingFacade;
import com.yucareux.tellus.worldgen.arnis.ArnisBuildingRules;
import com.yucareux.tellus.worldgen.caves.CubeNetSphericalCaveSampler;
import com.yucareux.tellus.worldgen.caves.CubeNetSphericalCaveField;
import com.yucareux.tellus.worldgen.road.StreetLightModel;
import com.yucareux.tellus.worldgen.road.StreetLightPlanner;
import com.yucareux.tellus.worldgen.global.CubeNetFeatureProjector;
import com.yucareux.tellus.worldgen.landmark.LandmarkPlacement;
import com.yucareux.tellus.worldgen.landmark.TellusLandmarks;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
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
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.util.Mth;
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
public final class CubeNetChunkGenerator extends ChunkGenerator implements CubeNetLodSurface, WaterSourceConversionGuard, TellusGeodeticPosition {
   public static final int GEOMETRY_VERSION = 3;
   private static final float AXOLOTL_CHUNK_CHANCE = 0.55F;
   private static final float AXOLOTL_POND_CHANCE = 0.68F;
   private static final int MAX_AXOLOTLS_PER_CHUNK = 2;
   private static final int CUBE_NET_SHORELINE_MIN_CLIFF = 3;
   private static final BlockState CUBE_NET_ROAD_POINT_BASE = Blocks.STONE_BRICK_WALL.defaultBlockState();
   private static final BlockState CUBE_NET_ROAD_SIGNAL_POLE = Blocks.IRON_BARS.defaultBlockState();
   private static final BlockState CUBE_NET_ROAD_SIGNAL_HEAD = TellusBlockReferences.woolState("BLACK");
   private static final BlockState CUBE_NET_ROAD_SIGNAL_RED = TellusBlockReferences.concreteState("RED");
   private static final BlockState CUBE_NET_ROAD_SIGNAL_YELLOW = TellusBlockReferences.concreteState("YELLOW");
   private static final BlockState CUBE_NET_ROAD_SIGNAL_GREEN = TellusBlockReferences.concreteState("LIME");
   private static final BlockState CUBE_NET_ROAD_BUS_SIGN = TellusBlockReferences.woolState("BLUE");
   private static final BlockState CUBE_NET_ROAD_BUS_PANEL = TellusBlockReferences.woolState("WHITE");
   private static final BlockState CUBE_NET_ROAD_WASTE_BIN = Blocks.CAULDRON.defaultBlockState();
   private static final BlockState CUBE_NET_ROAD_RECYCLING_BIN = TellusBlockReferences.concreteState("GREEN");
   private static final BlockState CUBE_NET_ROAD_BICYCLE_RACK = Blocks.IRON_BARS.defaultBlockState();
   private static final BlockState CUBE_NET_ROAD_FOUNTAIN = Blocks.WATER_CAULDRON.defaultBlockState();
   private static final BlockState CUBE_NET_ROAD_BOLLARD = Blocks.COBBLESTONE_WALL.defaultBlockState();
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
         .forGetter(generator -> generator.apronWidthChunks),
      Codec.intRange(0, 256).optionalFieldOf("river_lake_shoreline_blend",
         EarthGeneratorSettings.DEFAULT.riverLakeShorelineBlend())
         .forGetter(generator -> generator.inlandShorelineRadius),
      Codec.intRange(0, 256).optionalFieldOf("ocean_shoreline_blend",
         EarthGeneratorSettings.DEFAULT.oceanShorelineBlend())
         .forGetter(generator -> generator.oceanShorelineRadius),
      Codec.BOOL.optionalFieldOf("shoreline_blend_cliff_limit",
         EarthGeneratorSettings.DEFAULT.shorelineBlendCliffLimit())
         .forGetter(generator -> generator.shorelineBlendCliffLimit)
    ).apply(instance, (version, mapping, excludedRadius, transitionWidth, biome, scale, sea, min, height,
       apronChunks, inlandShorelineRadius, oceanShorelineRadius, shorelineBlendCliffLimit) -> {
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
         singularityConfig, null, apronChunks, inlandShorelineRadius,
          oceanShorelineRadius, shorelineBlendCliffLimit);

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
   private final CubeNetInlandWaterResolver inlandWaterResolver;
   private final TellusLandmarks landmarks;
   private volatile long worldSeed;
   private final CubeNetSphericalCaveSampler cubeNetSphericalCaveSampler = new CubeNetSphericalCaveSampler();
   private volatile long sphericalCaveSeed = Long.MIN_VALUE;
   private volatile CubeNetSphericalCaveField sphericalCaveField;
   private final TellusStructurePolicy structurePolicy;
   private final boolean cubeNetCaves;
   private final boolean cubeNetOreVeins;
   private final boolean cubeNetGeologicalStonePatches;
   private final boolean cubeNetCavesReachSurface;
   private final int cubeNetUndergroundDepth;
   private final boolean cubeNetRoads;
   private final boolean cubeNetBuildings;
   private final boolean cubeNetLakeFreezing;
   private final boolean cubeNetEnableWater;
   private final boolean cubeNetTeleportProgress;
   private final int cubeNetHeightOffset;
   private final double cubeNetTerrestrialHeightScale;
   private final double cubeNetOceanicHeightScale;
   private static final boolean CUBE_NET_CAVES_DEFAULT = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.caves", "true")
   );
   private static final boolean CUBE_NET_ORE_VEINS_DEFAULT = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.oreVeins", "true")
   );
   private static final boolean CUBE_NET_GEOLOGICAL_STONE_DEFAULT = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.geologicalStonePatches", "true")
   );
   private static final boolean CUBE_NET_CAVES_REACH_SURFACE_DEFAULT = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.cavesReachSurface", "false")
   );
   private static final int CUBE_NET_UNDERGROUND_DEPTH_DEFAULT = Integer.getInteger(
      "tellus.cubeNet.undergroundDepth", 256
   );
   private static final boolean CUBE_NET_ROADS_DEFAULT = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.roads", "true")
   );
   private static final boolean CUBE_NET_BUILDINGS_DEFAULT = Boolean.parseBoolean(
      System.getProperty("tellus.cubeNet.buildings", "true")
   );
   private static boolean resolveCaveGeneration(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().caveGeneration() : CUBE_NET_CAVES_DEFAULT;
   }

   private static boolean resolveOreVeins(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().oreVeins() : CUBE_NET_ORE_VEINS_DEFAULT;
   }

   private static boolean resolveGeologicalStonePatches(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().geologicalStonePatches() : CUBE_NET_GEOLOGICAL_STONE_DEFAULT;
   }

   private static boolean resolveCavesReachSurface(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().cavesReachSurface() : CUBE_NET_CAVES_REACH_SURFACE_DEFAULT;
   }

   private static int resolveUndergroundDepth(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().undergroundDepth() : CUBE_NET_UNDERGROUND_DEPTH_DEFAULT;
   }

   private static boolean resolveRoads(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().roads() : CUBE_NET_ROADS_DEFAULT;
   }

   private static boolean resolveBuildings(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().buildings() : CUBE_NET_BUILDINGS_DEFAULT;
   }

   private static boolean resolveLakeFreezing(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().lakeFreezing() : EarthGeneratorSettings.DEFAULT.lakeFreezing();
   }

   private static boolean resolveLavaPools(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().lavaPools() : EarthGeneratorSettings.DEFAULT.lavaPools();
   }

   private static boolean resolveGeodes(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().geodes() : EarthGeneratorSettings.DEFAULT.geodes();
   }

   private static boolean resolveEnableWater(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().enableWater() : EarthGeneratorSettings.DEFAULT.enableWater();
   }

   private static boolean resolveTeleportProgress(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().teleportProgress() : EarthGeneratorSettings.DEFAULT.teleportProgress();
   }

   private static int resolveHeightOffset(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().heightOffset() : EarthGeneratorSettings.DEFAULT.heightOffset();
   }

   private static double resolveTerrestrialHeightScale(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().terrestrialHeightScale() : EarthGeneratorSettings.DEFAULT.terrestrialHeightScale();
   }

   private static double resolveOceanicHeightScale(BiomeSource biomeSource) {
      return biomeSource instanceof CubeNetBiomeSource cubeNet
         ? cubeNet.settings().oceanicHeightScale() : EarthGeneratorSettings.DEFAULT.oceanicHeightScale();
   }

   private static TellusBiomeGenerationSettings.Settings cubeNetGenerationSettings(BiomeSource biomeSource) {
      boolean deepDark = biomeSource instanceof CubeNetBiomeSource cubeNetBiomeSource
         && cubeNetBiomeSource.settings().deepDark();
      // Vanilla ores and stone patches stay enabled: Cube-Net's own sampler only
      // adds copper/iron veins and granite/tuff, so filtering them would remove
      // most ore and rock types. The custom switches control only that sampler.
      return new TellusBiomeGenerationSettings.Settings(
         resolveCaveGeneration(biomeSource),
         true,
         true,
         resolveLavaPools(biomeSource),
         resolveGeodes(biomeSource),
         deepDark,
         false
      );
   }

   private final SphereSingularityPolicy.Config singularityConfig;
   private final SphereSingularityPolicy singularityPolicy;
   private final int inlandShorelineRadius;
   private final int oceanShorelineRadius;
   private final boolean shorelineBlendCliffLimit;

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
      this(biome, faceBiome, scale, seaLevel, minY, height, singularityConfig, null, apronChunks,
         EarthGeneratorSettings.DEFAULT.riverLakeShorelineBlend(),
         EarthGeneratorSettings.DEFAULT.oceanShorelineBlend(),
         EarthGeneratorSettings.DEFAULT.shorelineBlendCliffLimit());
   }

   private CubeNetChunkGenerator(BiomeSource biome, FixedBiomeSource faceBiome, double scale, int seaLevel, int minY, int height,
      SphereSingularityPolicy.Config singularityConfig, SphereChunkSampler.PointSampler pointSampler, int apronChunks) {
      this(biome, faceBiome, scale, seaLevel, minY, height, singularityConfig, pointSampler, apronChunks,
         EarthGeneratorSettings.DEFAULT.riverLakeShorelineBlend(),
         EarthGeneratorSettings.DEFAULT.oceanShorelineBlend(),
         EarthGeneratorSettings.DEFAULT.shorelineBlendCliffLimit());
   }

   private CubeNetChunkGenerator(BiomeSource biome, FixedBiomeSource faceBiome, double scale, int seaLevel, int minY, int height,
      SphereSingularityPolicy.Config singularityConfig, SphereChunkSampler.PointSampler pointSampler, int apronChunks,
      int inlandShorelineRadius, int oceanShorelineRadius, boolean shorelineBlendCliffLimit) {
      super(biome, holder -> TellusBiomeGenerationSettings.forBiome(holder, cubeNetGenerationSettings(biome)));
      this.structurePolicy = biome instanceof CubeNetBiomeSource cubeNet
          ? new TellusStructurePolicy(cubeNet.settings().structureToggles())
          : new TellusStructurePolicy(EarthGeneratorSettings.DEFAULT);
       this.cubeNetCaves = resolveCaveGeneration(biome);
      this.cubeNetOreVeins = resolveOreVeins(biome);
      this.cubeNetGeologicalStonePatches = resolveGeologicalStonePatches(biome);
      this.cubeNetCavesReachSurface = resolveCavesReachSurface(biome);
      this.cubeNetUndergroundDepth = resolveUndergroundDepth(biome);
      this.cubeNetRoads = resolveRoads(biome);
      this.cubeNetBuildings = resolveBuildings(biome);
      this.cubeNetLakeFreezing = resolveLakeFreezing(biome);
      this.cubeNetEnableWater = resolveEnableWater(biome);
      this.cubeNetTeleportProgress = resolveTeleportProgress(biome);
      this.cubeNetHeightOffset = resolveHeightOffset(biome);
      this.cubeNetTerrestrialHeightScale = resolveTerrestrialHeightScale(biome);
      this.cubeNetOceanicHeightScale = resolveOceanicHeightScale(biome);

      Preconditions.checkArgument(minY % 16 == 0 && height % 16 == 0 && height >= 16
         && minY >= -2032 && (long)minY + height <= 2032,
         "vertical range must be section-aligned within [-2032, 2032)");
      this.seaLevel = seaLevel + this.cubeNetHeightOffset - EarthGeneratorSettings.DEFAULT.heightOffset();
      this.minY = minY;
      this.height = height;
      this.model = SphereWorldModel.forWorldScale(scale);
      this.adapter = new CubeNetAddressAdapter(this.model);
      Preconditions.checkArgument(apronChunks >= 0 && apronChunks <= 35
         && apronChunks * 16 <= this.model.config().faceBlocks(), "apron width exceeds supported face size");
      this.apronWidthChunks = apronChunks;
      this.inlandShorelineRadius = inlandShorelineRadius;
      this.oceanShorelineRadius = oceanShorelineRadius;
      this.shorelineBlendCliffLimit = shorelineBlendCliffLimit;
      this.remoteProjection = new CubeNetRemoteProjection(this.adapter.layout(), apronChunks * 16.0);
      this.apronResolver = new CubeNetApronResolver(this.adapter.layout(), Math.max(16, apronChunks * 16));
      this.materializer = new SphereTerrainMaterializer(scale, this.seaLevel, this.cubeNetEnableWater,
         this.cubeNetTerrestrialHeightScale, this.cubeNetOceanicHeightScale, 0L);
      this.landmarks = TellusLandmarks.create(
         scale, this.cubeNetBuildings, this::surfaceYAtEarthCoordinate,
         (elevation, blockZ) -> this.materializer.height(elevation));
      this.singularityConfig = singularityConfig;
      this.singularityPolicy = new SphereSingularityPolicy(this.model, singularityConfig);
      TellusWorldgenSources.CubeNetSurfaceSampler surfaceSampler = pointSampler == null
         ? TellusWorldgenSources.cubeNetSurfaceSampler(this.model, this.seaLevel) : null;
      SphereChunkSampler.PointSampler effectivePointSampler = surfaceSampler == null
         ? pointSampler : surfaceSampler.pointSampler();
      this.inlandWaterResolver = surfaceSampler == null ? null : surfaceSampler.inlandWater();
      this.faces = new SphereFaceChunkGenerator[CubeNetLayout.FACE_COUNT];
      for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
         this.faces[face.index()] = new SphereFaceChunkGenerator(faceBiome, face.index(), scale, this.seaLevel, minY, height,
            effectivePointSampler, singularityConfig);
      }
   }

   /** Explicit native base stage: POST_GROUND_ADJUSTMENT_PRE_INLAND_FLOW_BADLANDS. */
   public com.yucareux.tellus.worldgen.sample.TellusSphericalSampleProvider semanticBaseSampleProvider() {
      return com.yucareux.tellus.worldgen.sample.TellusSphericalSampleProvider.forCubeNetNativeSnapshots(request -> {
         if (Double.compare(request.geometry().worldScale(), this.worldScale()) != 0
            || request.cacheKey().worldSeed() != this.worldSeed
            || this.worldSeed != this.materializer.worldSeed()) {
            throw new IllegalArgumentException("native Cube-Net base sample differs from generator scale/seed");
         }
         var position = this.semanticBasePosition(request.coordinate());
         var owner = (com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView)request.coordinate().owner();
         var column = this.faces[owner.block().face()].terrainGrid(owner.chunk()).column(
            (int)Math.floorMod(owner.block().blockX(), 16L), (int)Math.floorMod(owner.block().blockZ(), 16L));
         column = this.applyCubeNetLandmarkGround(List.of(column)).get(0);
         if (this.inlandWaterResolver != null) {
            column = this.applyCubeNetShorelineBankRampColumn(position.x(), position.z(), column, new HashMap<>());
         }
         var surface = this.inlandWaterResolver == null
            ? CubeNetSurfacePolicy.resolve(column.point(), column.data(), this.worldScale(), this.worldSeed)
            : this.resolveCubeNetSurfaceDecision(column.context(), new HashMap<>());
         return com.yucareux.tellus.worldgen.sample.TellusSphericalSurfaceSnapshot.resolvedNativeColumn(column, surface);
      });
   }

   /** Explicit base-column decision; later flowing-water properties and badlands bands remain external. */
   public com.yucareux.tellus.worldgen.decision.TellusSphericalDecisionKernel semanticBaseColumnKernel() {
      var settings = new com.yucareux.tellus.worldgen.decision.TellusSphericalDecisionKernel.Settings(
         this.cubeNetEnableWater, this.cubeNetTerrestrialHeightScale, this.cubeNetOceanicHeightScale);
      return new com.yucareux.tellus.worldgen.decision.TellusSphericalDecisionKernel(settings,
         (generation, coordinate, sample, terrainY) -> {
            if (Double.compare(generation.worldScale(), this.worldScale()) != 0
               || generation.seaLevel() != this.seaLevel || generation.minY() != this.minY
               || generation.maxYExclusive() != this.maxY() || generation.worldSeed() != this.worldSeed
               || this.worldSeed != this.materializer.worldSeed()) {
               throw new IllegalArgumentException("native Cube-Net base kernel differs from generator profile/seed");
            }
            var position = this.semanticBasePosition(coordinate);
            if (this.biomeSource instanceof CubeNetBiomeSource cubeNet) {
               var owner = (com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView)coordinate.owner();
               var center = com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory.fromCubeNetCanonical(
                  this.model, this.adapter.layout(), this.adapter.facePoint(owner.block()));
               return cubeNet.semanticColumnBiome(generation, center, terrainY);
            }
            var biome = this.biomeSource.getNoiseBiome(QuartPos.fromBlock(position.x()), QuartPos.fromBlock(terrainY),
               QuartPos.fromBlock(position.z()), Climate.empty());
            return biome.unwrapKey().map(key -> new com.yucareux.tellus.worldgen.decision.BiomeDecision(
               key.location().toString(), true)).orElseGet(com.yucareux.tellus.worldgen.decision.BiomeDecision::unknown);
         });
   }

   private com.yucareux.tellus.worldgen.write.CanonicalColumnPosition semanticBasePosition(
      com.yucareux.tellus.worldgen.global.TellusCoordinateContext coordinate
   ) {
      Objects.requireNonNull(coordinate, "coordinate");
      if (!(coordinate.owner() instanceof com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView owner)) {
         throw new IllegalArgumentException("native Cube-Net base requires canonical Cube-Net ownership");
      }
      this.model.chartForBlock(owner.block().face(), owner.block().blockX(), owner.block().blockZ());
      if (!this.model.blockForGeo(coordinate.geo()).equals(owner.block())) {
         throw new IllegalArgumentException("native Cube-Net base owner does not match geographic point");
      }
      var generation = new com.yucareux.tellus.worldgen.decision.TellusGenerationContext(
         this.worldSeed, this.worldScale(), this.seaLevel, this.minY, this.maxY());
      var position = com.yucareux.tellus.worldgen.write.CanonicalColumnPosition.resolve(
         com.yucareux.tellus.worldgen.write.WriteContext.canonical(coordinate, generation, "cube-native-base"), this.model);
      if (!this.address(new ChunkPos(Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16)))
         .filter(owner.chunk()::equals).isPresent()) {
         throw new IllegalArgumentException("native Cube-Net base planar chunk does not match canonical owner");
      }
      BlockAddress block = this.resolveBlock(position.x(), position.z()).orElseThrow(
         () -> new IllegalArgumentException("native Cube-Net base planar block is not materializable"));
      if (block.address().face() != owner.block().face() || block.x() != owner.block().blockX()
         || block.z() != owner.block().blockZ()) {
         throw new IllegalArgumentException("native Cube-Net base planar block does not match canonical owner");
      }
      this.singularityPolicy.requireTerrainMaterialization(owner.chunk());
      return position;
   }

   /** Explicit raw-source badlands overlay; flowing-water state properties remain external. */
   public com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner semanticBadlandsFeaturePlanner() {
      return (generation, coordinate, column, sample) -> {
         Objects.requireNonNull(generation, "generation");
         Objects.requireNonNull(coordinate, "coordinate");
         Objects.requireNonNull(column, "column");
         Objects.requireNonNull(sample, "sample");
         if (!coordinate.equals(column.coordinate()) || !coordinate.equals(sample.request().coordinate())
            || !generation.geometry().equals(sample.request().geometry())
            || generation.worldSeed() != sample.request().cacheKey().worldSeed()) {
            throw new IllegalArgumentException("native Cube-Net badlands inputs do not share an identity");
         }
         if (sample.request().nonBlocking()
            || sample.status() == com.yucareux.tellus.worldgen.sample.SampleStatus.QUEUED
            || column.status() == com.yucareux.tellus.worldgen.decision.DecisionStatus.QUEUED) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.queued("native Cube-Net badlands input queued");
         }
         if (!sample.complete() || column.status() != com.yucareux.tellus.worldgen.decision.DecisionStatus.AVAILABLE) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.unknown("complete native Cube-Net badlands inputs required");
         }
         if (Double.compare(generation.worldScale(), this.worldScale()) != 0
            || generation.seaLevel() != this.seaLevel || generation.minY() != this.minY
            || generation.maxYExclusive() != this.maxY() || generation.worldSeed() != this.worldSeed
            || this.worldSeed != this.materializer.worldSeed()) {
            throw new IllegalArgumentException("native Cube-Net badlands planner differs from generator profile/seed");
         }
         var position = this.semanticBasePosition(coordinate);
         if (!(this.biomeSource instanceof CubeNetBiomeSource)) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.empty("native fixed-source badlands stage is empty");
         }
         var surface = this.surfaceDataAtPlanar(position.x(), position.z(), new HashMap<>());
         if (surface == null || !surface.elevationAvailable()) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.unknown("native badlands raw elevation is unavailable");
         }
         if (surface.waterAvailable()) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.empty("native badlands excludes raw water columns");
         }
         int surfaceY = this.materializer.height(surface.elevationMeters());
         var biome = this.biomeSource.getNoiseBiome(QuartPos.fromBlock(position.x()), QuartPos.fromBlock(surfaceY),
            QuartPos.fromBlock(position.z()), Climate.empty());
         if (!biome.is(BiomeTags.IS_BADLANDS)) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.empty("native raw column is not a badlands biome");
         }
         var owner = (com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView)coordinate.owner();
         var point = this.model.exactSurfacePoint(owner.block().face(), owner.block().blockX() + 0.5,
            owner.block().blockZ() + 0.5);
         int materialX = coordinateForEarth(EarthProjection.longitudeToBlockX(point.longitude(), this.worldScale()));
         int materialZ = coordinateForEarth(EarthProjection.latToBlockZ(point.latitude(), this.worldScale()));
         var cells = com.yucareux.tellus.worldgen.decision.TellusCubeBadlandsDecisionKernel.resolve(this.minY, surfaceY,
            this.worldScale(), surface.neighbourhood().relief(), surface.neighbourhood().gradient().slopeDegrees(),
            materialX, materialZ);
         List<com.yucareux.tellus.worldgen.feature.FeaturePlacement> placements = new ArrayList<>(cells.size());
         for (var cell : cells) {
            if (cell.y() < generation.minY() || cell.y() >= generation.maxYExclusive()) {
               return com.yucareux.tellus.worldgen.feature.FeaturePlan.unknown("native badlands overlay exceeds generation bounds");
            }
            placements.add(com.yucareux.tellus.worldgen.feature.FeaturePlacement.canonical(
               com.yucareux.tellus.worldgen.feature.FeatureKind.CUSTOM, "tellus:cube-badlands:" + cell.y(), coordinate,
               com.yucareux.tellus.worldgen.feature.PlacementOperation.SET,
               com.yucareux.tellus.worldgen.feature.PlacementConflictPolicy.OWNER_ONLY, cell.material(),
               0, cell.y(), 0, 1, Map.of("stage", "POST_INLAND_FLOW_BADLANDS")));
         }
         return placements.isEmpty()
            ? com.yucareux.tellus.worldgen.feature.FeaturePlan.empty("native badlands overlay is empty")
            : new com.yucareux.tellus.worldgen.feature.FeaturePlan(
               com.yucareux.tellus.worldgen.feature.FeaturePlanStatus.AVAILABLE, placements, List.of());
      };
   }

   /** Explicit raw-native inland flow, including exact WATER level and required post-processing. */
   public com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner semanticInlandFlowFeaturePlanner() {
      return (generation, coordinate, column, sample) -> {
         Objects.requireNonNull(generation, "generation");
         Objects.requireNonNull(coordinate, "coordinate");
         Objects.requireNonNull(column, "column");
         Objects.requireNonNull(sample, "sample");
         if (!coordinate.equals(column.coordinate()) || !coordinate.equals(sample.request().coordinate())
            || !generation.geometry().equals(sample.request().geometry())
            || generation.worldSeed() != sample.request().cacheKey().worldSeed()) {
            throw new IllegalArgumentException("native Cube-Net inland-flow inputs do not share an identity");
         }
         if (sample.request().nonBlocking()
            || sample.status() == com.yucareux.tellus.worldgen.sample.SampleStatus.QUEUED
            || column.status() == com.yucareux.tellus.worldgen.decision.DecisionStatus.QUEUED) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.queued("native Cube-Net inland-flow input queued");
         }
         if (!sample.complete() || column.status() != com.yucareux.tellus.worldgen.decision.DecisionStatus.AVAILABLE) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.unknown("complete native Cube-Net inland-flow inputs required");
         }
         if (Double.compare(generation.worldScale(), this.worldScale()) != 0
            || generation.seaLevel() != this.seaLevel || generation.minY() != this.minY
            || generation.maxYExclusive() != this.maxY() || generation.worldSeed() != this.worldSeed
            || this.worldSeed != this.materializer.worldSeed()) {
            throw new IllegalArgumentException("native Cube-Net inland-flow planner differs from generator profile/seed");
         }
         this.semanticBasePosition(coordinate);
         if (this.inlandWaterResolver == null) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.empty("native inland-flow resolver is absent");
         }
         var owner = (com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView)coordinate.owner();
         var point = this.model.exactSurfacePoint(owner.block().face(), owner.block().blockX() + 0.5,
            owner.block().blockZ() + 0.5);
         var surface = this.faces[owner.block().face()].terrainPoint(owner.block(), 1).data();
         if (surface == null || !surface.elevationAvailable()) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.unknown("native inland-flow raw elevation is unavailable");
         }
         if (!surface.inlandWater()) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.empty("native raw column has no inland water");
         }
         var flow = this.inlandWaterResolver.sample(point, surface);
         if (!flow.hasWater() || flow.flowLevel() <= 0) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.empty("native inland-flow column has no positive flow level");
         }
         int waterY = this.materializer.height(surface.waterSurfaceMeters());
         int terrainY = flow.falling() ? this.materializer.height(flow.terrainSurfaceMeters()) : 0;
         var resolved = com.yucareux.tellus.worldgen.decision.TellusCubeInlandFlowDecisionKernel.resolve(
            this.minY, this.maxY(), flow.hasWater(), flow.flowLevel(), flow.falling(), waterY, terrainY);
         if (resolved.isEmpty()) {
            return com.yucareux.tellus.worldgen.feature.FeaturePlan.empty("native inland-flow band is outside build bounds");
         }
         var band = resolved.orElseThrow();
         int height = Math.addExact(Math.subtractExact(band.topY(), band.bottomY()), 1);
         return com.yucareux.tellus.worldgen.feature.FeaturePlan.available(
            com.yucareux.tellus.worldgen.feature.FeaturePlacement.canonicalFlow(
               coordinate, "tellus:cube-inland-flow", band.bottomY(), height, band.level()));
      };
   }

   public SphereWorldModel model() { return this.model; }
   public CubeNetAddressAdapter adapter() { return this.adapter; }
   public CubeNetApronResolver apronResolver() { return this.apronResolver; }
   public boolean nativeApronEnabled() { return this.apronWidthChunks > 0; }
   public int apronWidthChunks() { return this.apronWidthChunks; }
   public double worldScale() {
      return this.model.config().worldScale();
   }

   @Override
   public double latitudeFromBlock(double blockZ) {
       return this.latitudeFromBlock(0.0, blockZ);
    }

   @Override
   public double longitudeFromBlock(double blockX) {
       return this.longitudeFromBlock(blockX, 0.0);
    }

   @Override
   public double latitudeFromBlock(double blockX, double blockZ) {
       return this.geodeticPointFromBlock(blockX, blockZ).latitude();
    }

   @Override
   public double longitudeFromBlock(double blockX, double blockZ) {
       return this.geodeticPointFromBlock(blockX, blockZ).longitude();
    }

    @Override
    public int heightOffset() {
       return this.cubeNetHeightOffset;
    }

    @Override
    public boolean lakeFreezing() {
       return this.cubeNetLakeFreezing;
    }

    @Override
    public boolean enableWater() {
       return this.cubeNetEnableWater;
    }

    @Override
    public boolean teleportProgress() {
       return this.cubeNetTeleportProgress;
    }

    @Override
    public boolean lavaPools() {
       return resolveLavaPools(this.biomeSource);
    }

    @Override
    public boolean geodes() {
       return resolveGeodes(this.biomeSource);
    }

    @Override
    public int seaLevel() {
       return this.seaLevel;
    }

    @Override
    public Optional<BlockPos> surfacePosition(LevelHeightAccessor heightAccessor, double latitude, double longitude) {
       if (heightAccessor == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)) {
          return Optional.empty();
       }
       CubeNetFeatureProjector.Projection projection = CubeNetFeatureProjector.project(
          this.model, this.adapter.layout(), latitude, longitude).orElse(null);
       if (projection == null) return Optional.empty();
       int planarX = (int)Math.floor(projection.planarX());
       int planarZ = (int)Math.floor(projection.planarZ());
       BlockAddress block = this.resolveBlock(planarX, planarZ).orElse(null);
       if (block == null || block.address().face() != projection.face()) return Optional.empty();
       int faceBlocks = this.model.config().faceBlocks();
       if (block.x() < 0 || block.z() < 0 || block.x() >= faceBlocks || block.z() >= faceBlocks) {
          return Optional.empty();
       }
       SphereChunkSampler.SurfaceData surface = this.faces[block.address().face()].terrainPoint(
          new SphereWorldModel.SphereBlockAddress(block.address().face(), block.x(), block.z()), 1).data();
       if (surface == null || !surface.elevationAvailable()) return Optional.empty();
       SphereTerrainMaterializer.ColumnPlan plan;
       try {
          plan = this.materializer.plan(surface, this.minY, this.maxY());
       } catch (RuntimeException error) {
          return Optional.empty();
       }
       int minY = MinecraftVersionCompat.minY(heightAccessor);
       int maxY = MinecraftVersionCompat.maxY(heightAccessor) - 1;
       if (minY > maxY) return Optional.empty();
       int targetY = Math.max(minY, Math.min(maxY, plan.topY() + 1));
       return Optional.of(new BlockPos(planarX, targetY, planarZ));
    }

    public BlockPos getInitialSpawnPosition(LevelHeightAccessor heightAccessor) {
       CubeNetBiomeSource cubeNet = this.biomeSource instanceof CubeNetBiomeSource source ? source : null;
       double latitude = cubeNet == null ? EarthGeneratorSettings.DEFAULT.spawnLatitude() : cubeNet.settings().spawnLatitude();
       double longitude = cubeNet == null ? EarthGeneratorSettings.DEFAULT.spawnLongitude() : cubeNet.settings().spawnLongitude();
       return this.surfacePosition(heightAccessor, latitude, longitude)
          .orElseThrow(() -> new IllegalStateException("Cube-Net initial spawn surface is unavailable at lat="
             + latitude + ", lon=" + longitude));
    }

    private SphereWorldModel.GeoPoint geodeticPointFromBlock(double blockX, double blockZ) {
       if (!Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
          throw new IllegalArgumentException("block coordinates must be finite");
       }
       int x = (int)Math.floor(blockX);
       int z = (int)Math.floor(blockZ);
       BlockAddress block = this.resolveBlock(x, z).orElse(null);
       if (block != null) {
          return this.model.exactSurfacePoint(block.address().face(), block.x() + 0.5, block.z() + 0.5);
       }
       CubeNetLayout.FacePoint point = this.adapter.layout().fromPlanar(
          new CubeNetLayout.PlanarPoint(blockX + 0.5, blockZ + 0.5));
       return this.model.exactSurfacePoint(point.face().index(), point.u(), point.v());
    }
   @Override
   public boolean isLakeWater(double blockX, double blockZ) {
      if (this.inlandWaterResolver == null || !Double.isFinite(blockX) || !Double.isFinite(blockZ)) return false;
      int worldX = (int)Math.floor(blockX);
      int worldZ = (int)Math.floor(blockZ);
      ChunkPos chunkPos = new ChunkPos(Math.floorDiv(worldX, 16), Math.floorDiv(worldZ, 16));
      SphereWorldModel.SphereChunkAddress owner = this.address(chunkPos)
         .filter(this.singularityPolicy::admitsTerrainMaterialization).orElse(null);
      if (owner == null) return false;
      BlockAddress block = this.resolveBlock(worldX, worldZ).orElse(null);
      if (block == null || block.address().face() != owner.face()) return false;
      SphereChunkSampler.SurfaceData surface = this.faces[owner.face()].terrainPoint(
         new SphereWorldModel.SphereBlockAddress(owner.face(), block.x(), block.z()), 1).data();
      if (surface == null || !surface.elevationAvailable() || surface.oceanWater()) return false;
      SphereWorldModel.GeoPoint point = this.model.exactSurfacePoint(
         owner.face(), block.x() + 0.5, block.z() + 0.5);
      CubeNetInlandWaterResolver.Sample sample = this.inlandWaterResolver.sample(point, surface);
      // Flowing reaches and falling curtains are rivers/waterfalls, not frozen lakes.
      return sample.hasWater() && !sample.ocean() && sample.flowLevel() <= 0 && !sample.falling();
   }

   @Override public int minY() { return this.minY; }
   @Override public int maxY() { return this.minY + this.height; }
   SphereSingularityPolicy.Config singularityConfig() { return this.singularityConfig; }

   @Override
   public boolean shouldSuppressWaterSourceConversion(int blockX, int blockY, int blockZ) {
      if (this.inlandWaterResolver == null) return false;
      BlockAddress block = this.resolveBlock(blockX, blockZ).orElse(null);
      if (block == null) return false;
      SphereWorldModel.GeoPoint point = this.model.exactSurfacePoint(
         block.address().face(), block.x() + 0.5, block.z() + 0.5);
      return this.inlandWaterResolver.shouldSuppressWaterSourceConversion(point, blockY);
   }

   @Override
   public ChunkGeneratorStructureState createState(
      HolderLookup<StructureSet> structureSets, RandomState randomState, long seed
   ) {
      this.worldSeed = seed;
      this.materializer.setWorldSeed(seed);
      for (SphereFaceChunkGenerator face : this.faces) face.setWorldSeed(seed);
       HolderLookup<StructureSet> filtered = new FilteredStructureLookup(structureSets, this.structurePolicy::enabledSet);
       return ChunkGeneratorStructureState.createForNormal(randomState, seed, this.biomeSource, filtered);
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
            List<SphereChunkSampler.Column> columns = new ArrayList<>(256);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
               columns.add(canonical.column(candidate.targetBlockX(x, z) & 15, candidate.targetBlockZ(x, z) & 15));
            }
            columns = this.applyCubeNetLandmarkGround(columns);
            this.materializer.fill(chunk, new SphereChunkSampler.TerrainGrid(targetAddress,
               this.inlandWaterResolver == null ? columns : this.applyCubeNetShorelineBankRamp(chunk, columns)),
               this.inlandWaterResolver == null ? null : this.cubeNetSurfaceDecisionResolver(chunk));
            this.applyCubeNetInlandFlow(chunk);
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
         List<SphereChunkSampler.Column> columns = new ArrayList<>(256);
         var face = CubeNetLayout.Face.of(address.face());
         for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            // Chunk origins and all face offsets are section aligned. The
            // inverse basis therefore also determines the local cell order;
            // floorMod handles reflected axes (cell 0 maps to cell 15).
            double[] local = this.adapter.layout().localVector(face, x + 0.5, z + 0.5);
            columns.add(canonical.column(Math.floorMod((int)Math.floor(local[0]), 16),
               Math.floorMod((int)Math.floor(local[1]), 16)));
         }
         columns = this.applyCubeNetLandmarkGround(columns);
         this.materializer.fill(chunk, new SphereChunkSampler.TerrainGrid(address,
            this.inlandWaterResolver == null ? columns : this.applyCubeNetShorelineBankRamp(chunk, columns)),
            this.inlandWaterResolver == null ? null : this.cubeNetSurfaceDecisionResolver(chunk));
         this.applyCubeNetInlandFlow(chunk);
         this.applyCubeNetBadlandsBands(chunk);
   }

   /**
    * Resolves the same cover, climate and shoreline palette used by Earth for
    * a canonical spherical column. The resolver is scoped to one fill so all
    * neighbour water lookups stay deterministic and face-local caches are not
    * shared between chunk generation tasks.
    */
   private SphereTerrainMaterializer.SurfaceDecisionResolver cubeNetSurfaceDecisionResolver(ChunkAccess chunk) {
      Map<Long, SphereChunkSampler.SurfaceData> cache = new HashMap<>();
      return column -> this.resolveCubeNetSurfaceDecision(column, cache);
   }

   private CubeNetSurfacePolicy.Decision resolveCubeNetSurfaceDecision(
      SphereColumnContext column, Map<Long, SphereChunkSampler.SurfaceData> cache
   ) {
      if (!(this.biomeSource instanceof CubeNetBiomeSource)) {
         return CubeNetSurfacePolicy.resolve(column.point(), column.surface(), this.worldScale(), this.worldSeed);
      }
      SphereWorldModel.GeoPoint point = column.point();
      SphereChunkSampler.SurfaceData surface = column.surface();
      CubeNetLayout.Face face = CubeNetLayout.Face.of(column.address().face());
      CubeNetLayout.PlanarPoint planar = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         face, column.address().blockX() + 0.5, column.address().blockZ() + 0.5));
      int worldX = (int)Math.floor(planar.x());
      int worldZ = (int)Math.floor(planar.z());
      int surfaceY = this.materializer.height(surface.elevationMeters());
      Holder<Biome> biome = this.biomeSource.getNoiseBiome(
         QuartPos.fromBlock(worldX), QuartPos.fromBlock(surfaceY), QuartPos.fromBlock(worldZ), Climate.empty());
      double earthX = EarthProjection.longitudeToBlockX(point.longitude(), this.worldScale());
      double earthZ = EarthProjection.latToBlockZ(point.latitude(), this.worldScale());
      int earthBlockX = coordinateForEarth(earthX);
      int earthBlockZ = coordinateForEarth(earthZ);
      int visualCover = surface.coverClass();
      try {
         visualCover = TellusWorldgenSources.landCover().sampleVisualCoverClass(earthBlockX, earthBlockZ, this.worldScale());
      } catch (RuntimeException ignored) {
         // Cache misses retain the canonical raw cover, matching the source's
         // non-blocking fallback used by the biome source.
      }
      byte climateGroup = cubeNetClimateGroupForBiome(biome);
      int heightAboveSea = surfaceY - this.seaLevel;
      int slopeDiff = finite(surface.neighbourhood().gradient().slopeDegrees())
         ? Math.max(0, Math.min(8, (int)Math.round(surface.neighbourhood().gradient().slopeDegrees() / 9.0))) : 0;
      int convexity = cubeNetConvexity(worldX, worldZ, surfaceY, cache);
      CubeNetSurfacePolicy.Shoreline shoreline = this.cubeNetShoreline(
         worldX, worldZ, surface, cache);
      CubeNetSurfacePolicy.BiomeSurface biomeSurface = cubeNetBiomeSurface(biome);
      int zoneHeight = MountainElevationZones.zoneHeight(heightAboveSea,
         MountainElevationZones.band(point.latitude(), this.worldScale(), 1.0, 1.0, false, false));
      return CubeNetSurfacePolicy.resolveEarthEquivalent(point, surface, this.worldScale(), this.worldSeed,
         visualCover, climateGroup, zoneHeight, slopeDiff, convexity, shoreline, biomeSurface,
         earthBlockX, earthBlockZ);
   }

   private CubeNetSurfacePolicy.Shoreline cubeNetShoreline(
      int worldX, int worldZ, SphereChunkSampler.SurfaceData surface,
      Map<Long, SphereChunkSampler.SurfaceData> cache
   ) {
      if (surface.waterAvailable()) return CubeNetSurfacePolicy.Shoreline.NONE;
      int maxRadius = Math.max(this.inlandShorelineRadius, this.oceanShorelineRadius);
      int best = Integer.MAX_VALUE;
      boolean inland = false;
      for (int dz = -maxRadius; dz <= maxRadius; dz++) for (int dx = -maxRadius; dx <= maxRadius; dx++) {
         if (dx == 0 && dz == 0) continue;
         int distance = Math.max(Math.abs(dx), Math.abs(dz));
         if (distance > maxRadius) continue;
         SphereChunkSampler.SurfaceData neighbour = this.surfaceDataAtPlanar(worldX + dx, worldZ + dz, cache);
         if (neighbour == null || !neighbour.waterAvailable()) continue;
         int radius = neighbour.inlandWater() ? this.inlandShorelineRadius : this.oceanShorelineRadius;
         if (distance <= radius && distance < best) {
            best = distance;
            inland = neighbour.inlandWater();
         }
      }
      return best == Integer.MAX_VALUE ? CubeNetSurfacePolicy.Shoreline.NONE
         : new CubeNetSurfacePolicy.Shoreline(
            inland ? MountainSurfaceRules.ShorelineKind.INLAND : MountainSurfaceRules.ShorelineKind.OCEAN, best);
   }

   private int cubeNetConvexity(int worldX, int worldZ, int surfaceY,
      Map<Long, SphereChunkSampler.SurfaceData> cache) {
      int sum = 0;
      int count = 0;
      int step = TerrainSlopePolicy.SAMPLE_RADIUS_BLOCKS;
      for (int[] offset : new int[][]{{step, 0}, {-step, 0}, {0, step}, {0, -step}}) {
         SphereChunkSampler.SurfaceData neighbour = this.surfaceDataAtPlanar(
            worldX + offset[0], worldZ + offset[1], cache);
         if (neighbour == null || !neighbour.elevationAvailable()) continue;
         sum += this.materializer.height(neighbour.elevationMeters());
         count++;
      }
      return count == 4 ? Math.max(-8, Math.min(8, sum / 4 - surfaceY)) : 0;
   }

   private static byte cubeNetClimateGroupForBiome(Holder<Biome> biome) {
      if (biome.is(Biomes.MANGROVE_SWAMP) || biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA)) return 1;
      if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS)) return 2;
      if (biome.is(Biomes.FROZEN_PEAKS) || biome.is(Biomes.SNOWY_PLAINS)
         || biome.is(Biomes.SNOWY_SLOPES) || biome.is(Biomes.ICE_SPIKES)) return 5;
      if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.GROVE)
         || biome.is(Biomes.JAGGED_PEAKS) || biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) return 4;
      return (byte)(!biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_RIVER) ? 3 : 0);
   }

   private static CubeNetSurfacePolicy.BiomeSurface cubeNetBiomeSurface(Holder<Biome> biome) {
      if (biome.is(BiomeTags.IS_OCEAN)) return CubeNetSurfacePolicy.BiomeSurface.OCEAN;
      if (biome.is(BiomeTags.IS_RIVER)) return CubeNetSurfacePolicy.BiomeSurface.RIVER;
      if (biome.is(Biomes.MUSHROOM_FIELDS)) return CubeNetSurfacePolicy.BiomeSurface.MUSHROOM;
      if (biome.is(Biomes.BEACH)) return CubeNetSurfacePolicy.BiomeSurface.BEACH;
      if (biome.is(Biomes.SNOWY_BEACH)) return CubeNetSurfacePolicy.BiomeSurface.SNOWY_BEACH;
      if (biome.is(Biomes.STONY_SHORE)) return CubeNetSurfacePolicy.BiomeSurface.STONY_SHORE;
      if (biome.is(BiomeTags.IS_BADLANDS)) return CubeNetSurfacePolicy.BiomeSurface.BADLANDS;
      if (biome.is(Biomes.DESERT)) return CubeNetSurfacePolicy.BiomeSurface.DESERT;
      if (biome.is(Biomes.MANGROVE_SWAMP)) return CubeNetSurfacePolicy.BiomeSurface.MANGROVE;
      if (biome.is(Biomes.SWAMP)) return CubeNetSurfacePolicy.BiomeSurface.SWAMP;
      if (biome.is(Biomes.STONY_PEAKS)) return CubeNetSurfacePolicy.BiomeSurface.STONY_PEAKS;
      if (biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS)) return CubeNetSurfacePolicy.BiomeSurface.GRAVELLY;
      return CubeNetSurfacePolicy.BiomeSurface.DEFAULT;
   }

   private static boolean finite(double value) { return Double.isFinite(value); }

   /** Applies Earth landmark flattening to canonical columns before materialization. */
   private List<SphereChunkSampler.Column> applyCubeNetLandmarkGround(List<SphereChunkSampler.Column> columns) {
      List<LandmarkPlacement> placements = this.landmarks.placements();
      if (placements.isEmpty()) return columns;
      List<SphereChunkSampler.Column> adjusted = null;
      for (int index = 0; index < columns.size(); index++) {
         SphereChunkSampler.Column column = columns.get(index);
         SphereWorldModel.GeoPoint point = column.point();
         int earthX = coordinateForEarth(EarthProjection.longitudeToBlockX(point.longitude(), this.worldScale()));
         int earthZ = coordinateForEarth(EarthProjection.latToBlockZ(point.latitude(), this.worldScale()));
         int flattened = Integer.MIN_VALUE;
         for (LandmarkPlacement landmark : placements) {
            int candidate = landmark.flattenedSurface(earthX, earthZ);
            if (candidate != Integer.MIN_VALUE) {
               flattened = candidate;
               break;
            }
         }
         if (flattened == Integer.MIN_VALUE) continue;
         if (adjusted == null) adjusted = new ArrayList<>(columns);
         double elevation = (flattened - this.seaLevel) * this.worldScale();
         adjusted.set(index, column.withData(column.data().withoutWater().withElevation(elevation)));
      }
      return adjusted == null ? columns : List.copyOf(adjusted);
   }

   private void applyCubeNetInlandFlow(ChunkAccess chunk) {
      if (this.inlandWaterResolver == null) return;
      for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
         int worldX = chunk.getPos().getMinBlockX() + localX;
         int worldZ = chunk.getPos().getMinBlockZ() + localZ;
         BlockAddress block = this.resolveBlock(worldX, worldZ).orElse(null);
         if (block == null) continue;
         SphereWorldModel.GeoPoint point = this.model.exactSurfacePoint(
            block.address().face(), block.x() + 0.5, block.z() + 0.5);
         SphereChunkSampler.SurfaceData surface = this.faces[block.address().face()].terrainPoint(
            new SphereWorldModel.SphereBlockAddress(block.address().face(), block.x(), block.z()), 1).data();
         if (surface == null || !surface.inlandWater()) continue;
         CubeNetInlandWaterResolver.Sample flow = this.inlandWaterResolver.sample(point, surface);
         if (!flow.hasWater() || flow.flowLevel() <= 0) continue;
         int waterY = this.materializer.height(surface.waterSurfaceMeters());
         int terrainY = flow.falling() ? this.materializer.height(flow.terrainSurfaceMeters()) : 0;
         var resolved = com.yucareux.tellus.worldgen.decision.TellusCubeInlandFlowDecisionKernel.resolve(
            this.minY, this.maxY(), flow.hasWater(), flow.flowLevel(), flow.falling(), waterY, terrainY);
         if (resolved.isEmpty()) continue;
         var band = resolved.orElseThrow();
         BlockState state = Blocks.WATER.defaultBlockState().setValue(
            net.minecraft.world.level.block.LiquidBlock.LEVEL, band.level());
         for (int y = band.bottomY(); y <= band.topY(); y++) {
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            MinecraftVersionCompat.setBlockState(chunk, pos, state);
            if (chunk instanceof ProtoChunk proto) MinecraftVersionCompat.markPosForPostProcessing(proto, pos);
         }
      }
   }

   private List<SphereChunkSampler.Column> applyCubeNetShorelineBankRamp(
      ChunkAccess chunk, List<SphereChunkSampler.Column> columns
   ) {
      if (!this.shorelineBlendCliffLimit
         || Math.max(this.inlandShorelineRadius, this.oceanShorelineRadius) <= 0) {
         return columns;
      }
      Map<Long, SphereChunkSampler.SurfaceData> cache = new HashMap<>();
      List<SphereChunkSampler.Column> adjusted = new ArrayList<>(columns);
      int minX = chunk.getPos().getMinBlockX();
      int minZ = chunk.getPos().getMinBlockZ();
      for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
         int index = localZ * 16 + localX;
         adjusted.set(index, this.applyCubeNetShorelineBankRampColumn(
            minX + localX, minZ + localZ, columns.get(index), cache));
      }
      return List.copyOf(adjusted);
   }

   private SphereChunkSampler.Column applyCubeNetShorelineBankRampColumn(
      int worldX, int worldZ, SphereChunkSampler.Column column,
      Map<Long, SphereChunkSampler.SurfaceData> cache
   ) {
      if (!this.shorelineBlendCliffLimit
         || Math.max(this.inlandShorelineRadius, this.oceanShorelineRadius) <= 0) {
         return column;
      }
      SphereChunkSampler.SurfaceData surface = column.data();
      if (!surface.elevationAvailable() || surface.waterAvailable()) return column;
      BlockAddress resolved = this.resolveBlock(worldX, worldZ).orElse(null);
      if (resolved != null) {
         SphereWorldModel.GeoPoint point = this.model.exactSurfacePoint(
            resolved.address().face(), resolved.x() + 0.5, resolved.z() + 0.5);
         if (this.inlandWaterResolver != null && this.inlandWaterResolver.isInlandBank(point)) return column;
      }
      int terrainY = this.materializer.height(surface.elevationMeters());
      int allowedY = Integer.MAX_VALUE;
      int searchRadius = Math.max(this.inlandShorelineRadius, this.oceanShorelineRadius);
      for (int dz = -searchRadius; dz <= searchRadius; dz++) {
         for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            if (dx == 0 && dz == 0) continue;
            int distance = Math.max(Math.abs(dx), Math.abs(dz));
            if (distance > searchRadius) continue;
            SphereChunkSampler.SurfaceData neighbour = this.surfaceDataAtPlanar(
               worldX + dx, worldZ + dz, cache);
            if (neighbour == null || !neighbour.waterAvailable()) continue;
            int radius = neighbour.inlandWater()
               ? this.inlandShorelineRadius : this.oceanShorelineRadius;
            if (distance > radius) continue;
            int waterY = this.materializer.height(neighbour.waterSurfaceMeters());
            allowedY = Math.min(allowedY, waterY + distance);
         }
      }
      if (allowedY == Integer.MAX_VALUE || terrainY - allowedY < CUBE_NET_SHORELINE_MIN_CLIFF
         || terrainY <= allowedY) return column;
      int rampedY = Math.max(this.minY + 1, allowedY);
      double elevation = (rampedY - this.seaLevel) * this.worldScale();
      return new SphereChunkSampler.Column(column.address(), column.point(), surface.withElevation(elevation));
   }

   /** Applies Tellus's layered badlands cliff material to canonical columns. */
   private void applyCubeNetBadlandsBands(ChunkAccess chunk) {
      if (!(this.biomeSource instanceof CubeNetBiomeSource)) return;
      Map<Long, SphereChunkSampler.SurfaceData> cache = new HashMap<>();
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      boolean changed = false;
      for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
         int worldX = chunk.getPos().getMinBlockX() + localX;
         int worldZ = chunk.getPos().getMinBlockZ() + localZ;
         SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(worldX, worldZ, cache);
         if (surface == null || !surface.elevationAvailable() || surface.waterAvailable()) continue;
         int surfaceY = this.materializer.height(surface.elevationMeters());
         Holder<Biome> biome = this.biomeSource.getNoiseBiome(
            QuartPos.fromBlock(worldX), QuartPos.fromBlock(surfaceY), QuartPos.fromBlock(worldZ), Climate.empty());
         if (!biome.is(BiomeTags.IS_BADLANDS)) continue;
         BlockAddress resolved = this.resolveBlock(worldX, worldZ).orElse(null);
         int materialX = worldX;
         int materialZ = worldZ;
         if (resolved != null) {
            SphereWorldModel.GeoPoint point = this.model.exactSurfacePoint(
               resolved.address().face(), resolved.x() + 0.5, resolved.z() + 0.5);
            materialX = coordinateForEarth(EarthProjection.longitudeToBlockX(point.longitude(), this.worldScale()));
            materialZ = coordinateForEarth(EarthProjection.latToBlockZ(point.latitude(), this.worldScale()));
         }
         var cells = com.yucareux.tellus.worldgen.decision.TellusCubeBadlandsDecisionKernel.resolve(
            this.minY, surfaceY, this.worldScale(), surface.neighbourhood().relief(),
            surface.neighbourhood().gradient().slopeDegrees(), materialX, materialZ);
         for (var cell : cells) {
            cursor.set(worldX, cell.y(), worldZ);
            MinecraftVersionCompat.setBlockState(chunk, cursor,
               com.yucareux.tellus.worldgen.write.TellusNativeSemanticMaterials.state(cell.material()));
         }
         changed |= !cells.isEmpty();
      }
      if (changed) chunk.setUnsaved(true);
   }

   private BlockState cubeNetBadlandsBand(int worldX, int worldZ, int y) {
      return switch (BadlandsTerrainPolicy.bandMaterialIndex(worldX, worldZ, y)) {
         case BadlandsTerrainPolicy.ORANGE_TERRACOTTA -> TellusBlockReferences.terracottaState("ORANGE");
         case BadlandsTerrainPolicy.YELLOW_TERRACOTTA -> TellusBlockReferences.terracottaState("YELLOW");
         case BadlandsTerrainPolicy.BROWN_TERRACOTTA -> TellusBlockReferences.terracottaState("BROWN");
         case BadlandsTerrainPolicy.RED_TERRACOTTA -> TellusBlockReferences.terracottaState("RED");
         case BadlandsTerrainPolicy.LIGHT_GRAY_TERRACOTTA -> TellusBlockReferences.terracottaState("LIGHT_GRAY");
         case BadlandsTerrainPolicy.WHITE_TERRACOTTA -> TellusBlockReferences.terracottaState("WHITE");
         default -> Blocks.TERRACOTTA.defaultBlockState();
      };
   }

   private BlockState cubeNetBadlandsPlateauTop(int worldX, int worldZ) {
      return switch (BadlandsTerrainPolicy.plateauMaterialIndex(worldX, worldZ)) {
         case BadlandsTerrainPolicy.PLATEAU_TERRACOTTA,
            BadlandsTerrainPolicy.PLATEAU_BROWN_TERRACOTTA -> Blocks.TERRACOTTA.defaultBlockState();
         case BadlandsTerrainPolicy.PLATEAU_COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
         default -> Blocks.RED_SAND.defaultBlockState();
      };
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
      if (!this.cubeNetCaves && !this.cubeNetOreVeins && !this.cubeNetGeologicalStonePatches) return;
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
         this.cubeNetCaves,
         this.cubeNetCavesReachSurface,
         this.cubeNetOreVeins,
         this.cubeNetGeologicalStonePatches,
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
               surfaceY, this.cubeNetUndergroundDepth, this.minY
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
         this.spawnCubeNetAxolotls(level, chunk);
         this.placeCubeNetTrees(level, chunk);
         this.applyRealtimeSnowCover(level, chunk);
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
       @Override
    public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> structures,
       BlockPos position, int searchRadius, boolean skipKnownStructures) {
       if (skipKnownStructures) return null;
       boolean ancientCityQuery = this.structurePolicy.enabled("ancient_city")
          && structures.stream().anyMatch(holder -> holder.unwrapKey().map(MinecraftVersionCompat::keyPath)
             .filter("ancient_city"::equals).isPresent());
       CubeNetBiomeSource source = this.biomeSource instanceof CubeNetBiomeSource value ? value : null;
       if (!ancientCityQuery || source == null || !source.settings().caveGeneration() || !source.settings().deepDark()) {
          return super.findNearestMapStructure(level, structures, position, searchRadius, false);
       }
       source.beginStructureBiomeQueries();
       try {
          return super.findNearestMapStructure(level, structures, position, searchRadius, false);
       } finally {
          source.endStructureBiomeQueries();
       }
    }
@Override public void createStructures(RegistryAccess registry, ChunkGeneratorStructureState state,
      StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates) {
      // Structures have one canonical owner. Aprons receive the owner's block
      // projection later and never create a second start at the seam view.
      if (this.address(chunk.getPos()).filter(this.singularityPolicy::admitsTerrainMaterialization).isPresent()) {
         super.createStructures(registry, state, structures, chunk, templates);
         this.filterCubeNetSurfaceStructureStarts(registry, chunk);
         this.filterCubeNetOsmCollisions(registry, chunk);
         this.retargetCubeNetUndergroundStarts(registry, chunk);
      }
   }
   @Override public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
      if (this.address(chunk.getPos()).filter(this.singularityPolicy::admitsTerrainMaterialization).isPresent()) {
         super.createReferences(level, structures, chunk);
      }
   }

   private void retargetCubeNetUndergroundStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
      int chunkMinY = MinecraftVersionCompat.minY(chunk);
      int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
      Map<Long, SphereChunkSampler.SurfaceData> cache = new HashMap<>();
      for (var entry : new ArrayList<>(chunk.getAllStarts().entrySet())) {
         StructureStart start = entry.getValue();
         if (start == null || !start.isValid()) continue;
         String path = MinecraftVersionCompat.registryKeyPath(registry, entry.getKey());
         if (path == null) continue;
         BoundingBox box = start.getBoundingBox();
         boolean underground = path.equals("stronghold") || path.startsWith("mineshaft")
            || path.equals("ancient_city") || path.equals("trial_chambers");
         if (!underground) continue;
         int lowestSurface = Integer.MAX_VALUE;
         for (int z = box.minZ(); z <= box.maxZ(); z += 8) {
            for (int x = box.minX(); x <= box.maxX(); x += 8) {
               lowestSurface = Math.min(lowestSurface, this.structureSurfaceY(x, z, cache));
            }
         }
         lowestSurface = Math.min(lowestSurface,
            this.structureSurfaceY(box.maxX(), box.maxZ(), cache));
         int clearance = path.startsWith("mineshaft") && path.endsWith("_mesa") ? 4 : 8;
         int depth = path.equals("stronghold") ? 42 : path.startsWith("mineshaft") ? 30
            : path.equals("ancient_city") ? 92 : 72;
         int topTarget = lowestSurface - depth;
         VanillaStructurePlacement.VerticalPlacementBounds bounds = VanillaStructurePlacement.verticalPlacementBounds(
            box.minY(), box.maxY(), lowestSurface, chunkMinY, chunkMaxY, 4, clearance);
         if (!bounds.canFit()) {
            chunk.setStartForStructure(entry.getKey(), StructureStart.INVALID_START);
            continue;
         }
         int offsetY = Mth.clamp(topTarget - box.getCenter().getY(), bounds.minOffsetY(), bounds.maxOffsetY());
         if (offsetY == 0) continue;
         List<StructurePiece> movedPieces = new ArrayList<>(start.getPieces().size());
         for (StructurePiece piece : start.getPieces()) {
            piece.move(0, offsetY, 0);
            movedPieces.add(piece);
         }
         chunk.setStartForStructure(entry.getKey(), new StructureStart(entry.getKey(), start.getChunkPos(),
            start.getReferences(), new PiecesContainer(movedPieces)));
      }
   }

   private void filterCubeNetOsmCollisions(RegistryAccess registryAccess, ChunkAccess chunk) {
      if (!this.cubeNetRoads && !this.cubeNetBuildings) return;
      SphereWorldModel.SphereChunkAddress address = this.address(chunk.getPos())
         .filter(this.singularityPolicy::admitsTerrainMaterialization).orElse(null);
      if (address == null) return;
      SphereChunkSampler.FeatureData data = this.faces[address.face()].sampleFeatures(address, 96.0, OsmQueryMode.NON_BLOCKING);
      boolean buildingsMiss = data.buildings().hadCacheMiss() && data.buildings().features().isEmpty();
      boolean roadsMiss = data.roads().hadCacheMiss() && data.roads().features().isEmpty();
      if (buildingsMiss && roadsMiss) return;
      Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
      for (var entry : new ArrayList<>(chunk.getAllStarts().entrySet())) {
         StructureStart start = entry.getValue();
         if (start == null || !start.isValid() || !cubeNetOsmCollisionStructure(registry, entry.getKey())) continue;
         BoundingBox box = start.getBoundingBox();
         if (this.cubeNetStructureCollides(box, data, address)) {
            chunk.setStartForStructure(entry.getKey(), StructureStart.INVALID_START);
         }
      }
   }

   private boolean cubeNetOsmCollisionStructure(Registry<Structure> registry, Structure structure) {
      String path = MinecraftVersionCompat.registryKeyPath(registry, structure);
      if (path == null) return false;
      return path.startsWith("village") || VanillaStructurePlacement.isWoodlandMansionPath(path)
         || path.equals("desert_pyramid") || path.equals("desert_temple")
         || path.equals("jungle_pyramid") || path.equals("jungle_temple")
         || path.equals("pillager_outpost") || path.equals("igloo") || path.equals("swamp_hut")
         || path.equals("witch_hut") || path.startsWith("ruined_portal") || path.startsWith("trail_ruins")
         || VanillaStructurePlacement.isAbandonedCampPath(path);
   }

   private boolean cubeNetStructureCollides(BoundingBox box, SphereChunkSampler.FeatureData data,
      SphereWorldModel.SphereChunkAddress address) {
      CubeNetLayout.Face face = CubeNetLayout.Face.of(address.face());
      int chunkMinX = Math.toIntExact(address.blockX() * 16L);
      int chunkMinZ = Math.toIntExact(address.blockZ() * 16L);
      int minX = Math.max(chunkMinX - 96, box.minX());
      int minZ = Math.max(chunkMinZ - 96, box.minZ());
      int maxX = Math.min(chunkMinX + 111, box.maxX());
      int maxZ = Math.min(chunkMinZ + 111, box.maxZ());
      if (minX > maxX || minZ > maxZ) return false;
      if (this.cubeNetBuildings && !this.landmarks.intersecting(box.minX(), box.minZ(), box.maxX(), box.maxZ()).isEmpty()) {
         return true;
      }
      if (this.cubeNetBuildings && !data.buildings().features().isEmpty()) {
         for (OsmBuildingFeature building : data.buildings().features()) {
            if (!CubeNetFeatureRasterizer.buildingCells(this.model, this.adapter.layout(), building,
               face, minX, minZ, maxX, maxZ).isEmpty()) return true;
         }
      }
      if (this.cubeNetRoads && !data.roads().features().isEmpty()) {
         for (RoadFeature road : data.roads().features()) {
            if (!CubeNetFeatureRasterizer.roadCells(this.model, this.adapter.layout(), road,
               face, minX, minZ, maxX, maxZ, this.worldScale()).isEmpty()) return true;
         }
         for (RoadAreaFeature area : data.roadAreas().features()) {
            if (!CubeNetFeatureRasterizer.roadAreaCells(this.model, this.adapter.layout(), area,
               face, minX, minZ, maxX, maxZ).isEmpty()) return true;
         }
      }
      return false;
   }

   private void filterCubeNetSurfaceStructureStarts(RegistryAccess registryAccess, ChunkAccess chunk) {
      Registry<Structure> registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
      Map<Long, SphereChunkSampler.SurfaceData> cache = new HashMap<>();
      for (var entry : new ArrayList<>(chunk.getAllStarts().entrySet())) {
         StructureStart start = entry.getValue();
         if (start == null || !start.isValid()) continue;
         String path = MinecraftVersionCompat.registryKeyPath(registry, entry.getKey());
         if (path == null) continue;
         BoundingBox box = start.getBoundingBox();
         boolean invalid = false;
         if (path.startsWith("village")) {
            invalid = this.structureStartTooSteep(box, 4, 4, 6, cache)
               || CubeNetStructurePlacement.footprintTouchesWater(
                  box.minX() - 2, box.maxX() + 2, box.minZ() - 2, box.maxZ() + 2, 8,
                  (x, z) -> this.structureWaterColumn(x, z, cache));
         } else if (VanillaStructurePlacement.isWoodlandMansionPath(path)) {
            invalid = this.structureStartTooSteep(box, 8, 6, 8, cache);
         } else if (VanillaStructurePlacement.isAbandonedCampPath(path)) {
            // Path-based discovery also guards versions without an abandoned-camp registry entry.
            invalid = !this.abandonedCampSiteUsable(start, cache);
         }
         if (invalid) chunk.setStartForStructure(entry.getKey(), StructureStart.INVALID_START);
      }
   }

   private boolean structureStartTooSteep(BoundingBox box, int margin, int step, int maxDelta,
      Map<Long, SphereChunkSampler.SurfaceData> cache) {
      return CubeNetStructurePlacement.surfaceTooSteep(
         box.minX() - margin, box.maxX() + margin, box.minZ() - margin, box.maxZ() + margin, step, maxDelta,
         (x, z) -> this.structureSurfaceY(x, z, cache));
   }

   private int structureSurfaceY(int x, int z, Map<Long, SphereChunkSampler.SurfaceData> cache) {
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(x, z, cache);
      return surface == null || !surface.elevationAvailable() ? this.minY : this.materializer.height(surface.elevationMeters());
   }

   private boolean structureWaterColumn(int x, int z, Map<Long, SphereChunkSampler.SurfaceData> cache) {
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(x, z, cache);
      return this.cubeNetEnableWater && surface != null && surface.waterAvailable()
         && this.materializer.height(surface.waterSurfaceMeters()) > this.materializer.height(surface.elevationMeters());
   }

   private boolean abandonedCampSiteUsable(StructureStart start, Map<Long, SphereChunkSampler.SurfaceData> cache) {
      if (start.getPieces().isEmpty()) return false;
      int floorY = start.getPieces().get(0).getBoundingBox().minY();
      for (StructurePiece piece : start.getPieces()) {
         BoundingBox box = piece.getBoundingBox();
         for (int z = box.minZ(); z <= box.maxZ(); z++) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
               if (this.structureWaterColumn(x, z, cache)
                  || !VanillaStructurePlacement.supportsRigidFloor(this.structureSurfaceY(x, z, cache), floorY)) return false;
            }
         }
      }
      return true;
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
      if (!this.cubeNetRoads && !this.cubeNetBuildings) return;
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
      if (apron == null && this.cubeNetBuildings) {
         this.placeCubeNetLandmarks(level, chunk, queryAddress);
      }
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
      if (this.cubeNetRoads) {
         for (RoadFeature road : data.roads().features()) {
            for (CubeNetFeatureRasterizer.FaceCell cell : CubeNetFeatureRasterizer.roadCells(
               this.model, this.adapter.layout(), road, face, minX, minZ, maxX, maxZ, this.worldScale())) {
               if (apron == null) this.placeCubeNetRoad(level, chunk, road, cell,
                  featureSurfaceCache, featureColumnCache);
               else this.placeCubeNetApronRoad(level, chunk, apron, road, cell,
                  featureSurfaceCache, featureColumnCache);
            }
            for (CubeNetFeatureRasterizer.FaceCell cell : CubeNetFeatureRasterizer.roadMarkingCells(
               this.model, this.adapter.layout(), road, face, minX, minZ, maxX, maxZ, this.worldScale())) {
               if (apron == null) this.placeCubeNetRoadMarking(level, chunk, road, cell, featureColumnCache);
               else this.placeCubeNetApronRoadMarking(level, chunk, apron, road, cell, featureColumnCache);
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
      if (this.cubeNetBuildings) {
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

   private void placeCubeNetLandmarks(WorldGenLevel level, ChunkAccess chunk,
      SphereWorldModel.SphereChunkAddress queryAddress) {
      SphereWorldModel.GeoPoint center = this.model.exactSurfacePoint(queryAddress);
      double chunkRadius = 24.0 * this.worldScale();
      for (LandmarkPlacement landmark : this.landmarks.placements()) {
         if (surfaceDistanceMeters(center.latitude(), center.longitude(), landmark.latitude(), landmark.longitude())
            > landmark.radiusMeters() + chunkRadius) continue;
         CubeNetLayout.Face face = CubeNetLayout.Face.of(queryAddress.face());
         int minX = Math.toIntExact(queryAddress.blockX());
         int minZ = Math.toIntExact(queryAddress.blockZ());
         for (int z = minZ; z <= minZ + 15; z++) for (int x = minX; x <= minX + 15; x++) {
            SphereWorldModel.GeoPoint point = this.model.exactSurfacePoint(face.index(), x + 0.5, z + 0.5);
            int earthX = coordinateForEarth(EarthProjection.longitudeToBlockX(point.longitude(), this.worldScale()));
            int earthZ = coordinateForEarth(EarthProjection.latToBlockZ(point.latitude(), this.worldScale()));
            CubeNetFeatureRasterizer.FaceCell cell = new CubeNetFeatureRasterizer.FaceCell(face.index(), x, z);
            landmark.columnSpans(earthX, earthZ, this.minY, this.maxY() - 1,
               (bottomY, topY, state) -> {
                  for (int y = bottomY; y <= topY; y++) this.placeFeatureBlock(level, chunk, cell, state, y);
               });
         }
      }
   }

   private int surfaceYAtEarthCoordinate(int earthX, int earthZ) {
      double longitude = EarthProjection.blockXToLongitude(earthX, this.worldScale());
      double latitude = EarthProjection.blockZToLat(earthZ, this.worldScale());
      CubeNetFeatureProjector.Projection projection = CubeNetFeatureProjector.project(
         this.model, this.adapter.layout(), latitude, longitude).orElse(null);
      if (projection == null) return this.seaLevel;
      return this.surfaceYAtPlanar((int)Math.floor(projection.planarX()), (int)Math.floor(projection.planarZ()),
         new HashMap<>(), new HashMap<>());
   }

   private static double surfaceDistanceMeters(double latitudeA, double longitudeA,
      double latitudeB, double longitudeB) {
      double latA = Math.toRadians(latitudeA), latB = Math.toRadians(latitudeB);
      double deltaLat = latB - latA;
      double deltaLon = Math.toRadians(longitudeB - longitudeA);
      double h = Math.sin(deltaLat * 0.5) * Math.sin(deltaLat * 0.5)
         + Math.cos(latA) * Math.cos(latB) * Math.sin(deltaLon * 0.5) * Math.sin(deltaLon * 0.5);
      return 2.0 * SphereWorldModel.EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1.0, Math.max(0.0, h))));
   }

   private void placeCubeNetRoadArea(WorldGenLevel level, ChunkAccess chunk,
      RoadAreaFeature area,
      CubeNetFeatureRasterizer.FaceCell cell, Map<Long, Integer> surfaceCache,
      Map<Long, SphereChunkSampler.SurfaceData> columnCache) {
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int worldX = (int)Math.floor(point.x());
      int worldZ = (int)Math.floor(point.z());
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(worldX, worldZ, columnCache);
      if (surface == null || surface.waterAvailable() && surface.waterSurfaceMeters() > surface.elevationMeters()) return;
      int surfaceY = this.materializer.height(surface.elevationMeters());
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
      int pointX = (int)Math.floor(point.x());
      int pointZ = (int)Math.floor(point.z());
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(pointX, pointZ, columnCache);
      if (surface == null || surface.waterAvailable() && surface.waterSurfaceMeters() > surface.elevationMeters()) return;
      int surfaceY = this.materializer.height(surface.elevationMeters());
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
      this.placeCubeNetInfrastructurePoints(level, chunk, points, roads);
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
      List<OsmStreetLightFeature> points, List<RoadFeature> roads) {
      Map<Long, SphereChunkSampler.SurfaceData> surfaceCache = new HashMap<>();
      for (OsmStreetLightFeature point : points) {
         if (point.kind() == null || point.kind() == RoadPointKind.STREET_LIGHT) continue;
         CubeNetFeatureProjector.Projection projection = CubeNetFeatureProjector.project(
            this.model, this.adapter.layout(), point.latitude(), point.longitude()).orElse(null);
         if (projection == null || !this.chunkOwnsPlanarPoint(chunk, projection.planarX(), projection.planarZ())) continue;
         int worldX = (int)Math.floor(projection.planarX());
         int worldZ = (int)Math.floor(projection.planarZ());
         SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(worldX, worldZ, surfaceCache);
         if (surface == null || surface.waterAvailable() && surface.waterSurfaceMeters() > surface.elevationMeters()) continue;
         int surfaceY = this.materializer.height(surface.elevationMeters());
         Direction facing = this.infrastructureFacing(projection, point.featureId(), roads);
         switch (point.kind()) {
            case TRAFFIC_SIGNAL -> this.placeCubeNetTrafficSignal(level, chunk, worldX, worldZ, surfaceY, facing);
            case BUS_STOP -> this.placeCubeNetBusStop(level, chunk, worldX, worldZ, surfaceY, facing);
            case CROSSING -> this.placeCubeNetInfrastructureBlock(level, chunk, worldX, worldZ, surfaceY + 1,
               Blocks.WHITE_CARPET.defaultBlockState());
            case BENCH -> this.placeCubeNetBench(level, chunk, worldX, worldZ, surfaceY + 1, facing);
            case WASTE_BASKET -> this.placeCubeNetInfrastructureBlock(level, chunk, worldX, worldZ, surfaceY + 1, CUBE_NET_ROAD_WASTE_BIN);
            case RECYCLING -> this.placeCubeNetInfrastructureBlock(level, chunk, worldX, worldZ, surfaceY + 1, CUBE_NET_ROAD_RECYCLING_BIN);
            case BICYCLE_PARKING -> this.placeCubeNetDoubleBlock(level, chunk, worldX, worldZ, surfaceY + 1,
               facing, CUBE_NET_ROAD_BICYCLE_RACK);
            case FOUNTAIN -> this.placeCubeNetInfrastructureBlock(level, chunk, worldX, worldZ, surfaceY + 1, CUBE_NET_ROAD_FOUNTAIN);
            case BOLLARD -> this.placeCubeNetInfrastructureBlock(level, chunk, worldX, worldZ, surfaceY + 1, CUBE_NET_ROAD_BOLLARD);
            default -> { }
         }
      }
   }

   private Direction infrastructureFacing(CubeNetFeatureProjector.Projection projection,
      long featureId, List<RoadFeature> roads) {
      double bestDistance = Double.POSITIVE_INFINITY;
      double bestDx = 0.0;
      double bestDz = 0.0;
      for (RoadFeature road : roads) {
         for (int index = 1; index < road.pointCount(); index++) {
            CubeNetFeatureProjector.Projection start = CubeNetFeatureProjector.project(
               this.model, this.adapter.layout(), road.latAt(index - 1), road.lonAt(index - 1)).orElse(null);
            CubeNetFeatureProjector.Projection end = CubeNetFeatureProjector.project(
               this.model, this.adapter.layout(), road.latAt(index), road.lonAt(index)).orElse(null);
            if (start == null || end == null || start.face() != projection.face() || end.face() != projection.face()) continue;
            double dx = end.faceX() - start.faceX();
            double dz = end.faceZ() - start.faceZ();
            double lengthSq = dx * dx + dz * dz;
            if (lengthSq <= 1.0E-8) continue;
            double t = Math.max(0.0, Math.min(1.0,
               ((projection.faceX() - start.faceX()) * dx + (projection.faceZ() - start.faceZ()) * dz) / lengthSq));
            double nearestX = start.faceX() + t * dx;
            double nearestZ = start.faceZ() + t * dz;
            double distance = Math.hypot(projection.faceX() - nearestX, projection.faceZ() - nearestZ);
            if (distance < bestDistance) {
               bestDistance = distance;
               bestDx = dx;
               bestDz = dz;
            }
         }
      }
      if (Double.isFinite(bestDistance)) {
         if (Math.abs(bestDx) >= Math.abs(bestDz)) return bestDx >= 0.0 ? Direction.EAST : Direction.WEST;
         return bestDz >= 0.0 ? Direction.SOUTH : Direction.NORTH;
      }
      // Isolated infrastructure points still need a deterministic face-local orientation.
      boolean xAxis = Math.floorMod(featureId, 2L) == 0L;
      boolean positive = Math.floorMod(featureId, 4L) < 2L;
      if (xAxis) return positive ? Direction.EAST : Direction.WEST;
      return positive ? Direction.SOUTH : Direction.NORTH;
   }

   private void placeCubeNetTrafficSignal(WorldGenLevel level, ChunkAccess chunk,
      int x, int z, int baseY, Direction facing) {
      if (!this.placeCubeNetInfrastructureBlock(level, chunk, x, z, baseY + 1, CUBE_NET_ROAD_POINT_BASE)) return;
      for (int y = baseY + 2; y <= baseY + 5; y++) {
         this.placeCubeNetInfrastructureBlock(level, chunk, x, z, y, CUBE_NET_ROAD_SIGNAL_POLE);
      }
      this.placeCubeNetInfrastructureBlock(level, chunk, x, z, baseY + 6, CUBE_NET_ROAD_SIGNAL_HEAD);
      int headX = x + facing.getStepX();
      int headZ = z + facing.getStepZ();
      this.placeCubeNetInfrastructureBlock(level, chunk, headX, headZ, baseY + 4, CUBE_NET_ROAD_SIGNAL_GREEN);
      this.placeCubeNetInfrastructureBlock(level, chunk, headX, headZ, baseY + 5, CUBE_NET_ROAD_SIGNAL_YELLOW);
      this.placeCubeNetInfrastructureBlock(level, chunk, headX, headZ, baseY + 6, CUBE_NET_ROAD_SIGNAL_RED);
   }

   private void placeCubeNetBusStop(WorldGenLevel level, ChunkAccess chunk,
      int x, int z, int baseY, Direction facing) {
      if (!this.placeCubeNetInfrastructureBlock(level, chunk, x, z, baseY + 1, CUBE_NET_ROAD_POINT_BASE)) return;
      this.placeCubeNetInfrastructureBlock(level, chunk, x, z, baseY + 2, CUBE_NET_ROAD_SIGNAL_POLE);
      this.placeCubeNetInfrastructureBlock(level, chunk, x, z, baseY + 3, CUBE_NET_ROAD_SIGNAL_POLE);
      this.placeCubeNetInfrastructureBlock(level, chunk, x, z, baseY + 4, CUBE_NET_ROAD_BUS_SIGN);
      this.placeCubeNetInfrastructureBlock(level, chunk, x + facing.getStepX(), z + facing.getStepZ(), baseY + 4,
         CUBE_NET_ROAD_BUS_PANEL);
   }

   private void placeCubeNetBench(WorldGenLevel level, ChunkAccess chunk,
      int x, int z, int y, Direction facing) {
      BlockState bench = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
      this.placeCubeNetInfrastructureBlock(level, chunk, x, z, y, bench);
      Direction along = facing.getClockWise();
      this.placeCubeNetInfrastructureBlock(level, chunk, x + along.getStepX(), z + along.getStepZ(), y, bench);
   }

   private void placeCubeNetDoubleBlock(WorldGenLevel level, ChunkAccess chunk,
      int x, int z, int y, Direction facing, BlockState state) {
      this.placeCubeNetInfrastructureBlock(level, chunk, x, z, y, state);
      Direction along = facing.getClockWise();
      this.placeCubeNetInfrastructureBlock(level, chunk, x + along.getStepX(), z + along.getStepZ(), y, state);
   }

   private boolean placeCubeNetInfrastructureBlock(WorldGenLevel level, ChunkAccess chunk,
      int x, int z, int y, BlockState state) {
      if (x < chunk.getPos().getMinBlockX() || x > chunk.getPos().getMaxBlockX()
         || z < chunk.getPos().getMinBlockZ() || z > chunk.getPos().getMaxBlockZ()
         || y < this.minY || y >= this.maxY()) return false;
      BlockPos target = new BlockPos(x, y, z);
      if (!level.getBlockState(target).isAir()) return false;
      level.setBlock(target, state, 2);
      return true;
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
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(worldX, worldZ, new HashMap<>());
      if (surface == null || surface.waterAvailable() && surface.waterSurfaceMeters() > surface.elevationMeters()) return;
      int baseY = this.materializer.height(surface.elevationMeters());
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
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(worldX, worldZ, columnCache);
      if (surface == null || road.mode() == RoadMode.NORMAL && surface.waterAvailable()
         && surface.waterSurfaceMeters() > surface.elevationMeters()) return;
      int terrainSurface = this.materializer.height(surface.elevationMeters());
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
      int pointX = (int)Math.floor(point.x());
      int pointZ = (int)Math.floor(point.z());
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(pointX, pointZ, columnCache);
      if (surface == null || road.mode() == RoadMode.NORMAL && surface.waterAvailable()
         && surface.waterSurfaceMeters() > surface.elevationMeters()) return;
      int terrainSurface = this.materializer.height(surface.elevationMeters());
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

   private void placeCubeNetRoadMarking(WorldGenLevel level, ChunkAccess chunk, RoadFeature road,
      CubeNetFeatureRasterizer.FaceCell cell, Map<Long, SphereChunkSampler.SurfaceData> columnCache) {
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int worldX = (int)Math.floor(point.x());
      int worldZ = (int)Math.floor(point.z());
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(worldX, worldZ, columnCache);
      if (surface == null || road.mode() == RoadMode.NORMAL && surface.waterAvailable()
         && surface.waterSurfaceMeters() > surface.elevationMeters()) return;
      int terrainSurface = this.materializer.height(surface.elevationMeters());
      int y = switch (road.mode()) {
         case NORMAL -> terrainSurface;
         case BRIDGE -> CubeNetRoadVerticalPolicy.bridgeDeckY(terrainSurface, road.bridgeLevel());
         case TUNNEL -> CubeNetRoadVerticalPolicy.tunnelDeckY(terrainSurface);
      };
      this.placeFeatureBlock(level, chunk, cell, TellusBlockReferences.concreteState("WHITE"), y);
   }

   private void placeCubeNetApronRoadMarking(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, RoadFeature road,
      CubeNetFeatureRasterizer.FaceCell cell, Map<Long, SphereChunkSampler.SurfaceData> columnCache) {
      int[] local = this.sourceLocalForTarget(apron, cell.x(), cell.z());
      if (local == null) return;
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int worldX = (int)Math.floor(point.x());
      int worldZ = (int)Math.floor(point.z());
      SphereChunkSampler.SurfaceData surface = this.surfaceDataAtPlanar(worldX, worldZ, columnCache);
      if (surface == null || road.mode() == RoadMode.NORMAL && surface.waterAvailable()
         && surface.waterSurfaceMeters() > surface.elevationMeters()) return;
      int terrainSurface = this.materializer.height(surface.elevationMeters());
      int y = switch (road.mode()) {
         case NORMAL -> terrainSurface;
         case BRIDGE -> CubeNetRoadVerticalPolicy.bridgeDeckY(terrainSurface, road.bridgeLevel());
         case TUNNEL -> CubeNetRoadVerticalPolicy.tunnelDeckY(terrainSurface);
      };
      this.placeApronFeatureBlock(level, chunk, apron, cell,
         TellusBlockReferences.concreteState("WHITE"), y, local);
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
      if (centroid == null) return;
      int groundY;
      BuildingProfile profile;
      BuildingBlueprint blueprint;
      TellusBuildingInteriors interior;
      TellusBuildingMaterials.BuildingMaterialPalette palette;
      int height;
      try {
         groundY = this.surfaceYAtPlanar((int)Math.floor(centroid.planarX()), (int)Math.floor(centroid.planarZ()),
            new HashMap<>(), new HashMap<>());
         profile = TellusBuildingProfiles.resolveProfile(building, this.worldScale(), null,
            Double.compare(this.worldScale(), 1.0) == 0);
         blueprint = TellusBuildingBlueprints.createExterior(
            "cube-net:" + building.featureId(), building, profile, this.worldSeed,
            groundY, groundY, groundY + Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale())),
            groundY + Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale())), this.worldScale());
         palette = TellusBuildingMaterials.resolvePalette(blueprint);
         interior = new TellusBuildingInteriors(BuildingFloorPlan.create(blueprint, building, this.worldScale()));
         height = Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale()));
      } catch (RuntimeException unavailableTerrain) {
         return;
      }
      java.util.Set<Long> occupied = new java.util.HashSet<>();
      for (var cell : cells) occupied.add(packFaceCell(cell.x(), cell.z()));
      for (var cell : cells) {
         if (cell.x() < minX || cell.x() > maxX || cell.z() < minZ || cell.z() > maxZ) continue;
         boolean boundary = !this.cubeNetBuildingOccupies(cell, -1, 0, occupied, building)
            || !this.cubeNetBuildingOccupies(cell, 1, 0, occupied, building)
            || !this.cubeNetBuildingOccupies(cell, 0, -1, occupied, building)
            || !this.cubeNetBuildingOccupies(cell, 0, 1, occupied, building);
         int[] local = this.sourceLocalForTarget(apron, cell);
         if (local == null) continue;
         int[] earth = this.earthCoordinates(cell);
         int boundaryDistance = interior.plan().boundaryDistanceAt(earth[0], earth[1]);
         if (boundaryDistance < 0) boundaryDistance = boundary ? 0 : 2;
         for (int y = groundY; y <= groundY + height; y++) {
            int floorIndex = blueprint.floorIndexAtY(y);
            BlockState state = y == groundY ? palette.floor()
               : y >= blueprint.roofBaseY(boundaryDistance)
                  ? TellusBuildingMaterials.resolveLodRoofBlock(blueprint, palette, boundaryDistance, earth[0], earth[1])
                  : blueprint.interiorsEnabled()
                     ? interior.blockAt(palette, boundaryDistance, earth[0], earth[1], floorIndex, y)
                     : boundary
                        ? TellusBuildingMaterials.resolveLodFacadeBlock(blueprint, palette, boundaryDistance,
                           earth[0], earth[1], floorIndex)
                        : Blocks.AIR.defaultBlockState();
            this.placeApronFeatureBlock(level, chunk, apron, cell, state, y, local);
         }
         this.placeCubeNetApronBuildingExteriorDetails(level, chunk, apron, cell, building, occupied, blueprint, palette,
            boundaryDistance, groundY, height, earth[0], earth[1]);
         this.placeCubeNetRoofDetail(level, chunk, apron, cell, local, blueprint, palette,
            boundaryDistance, earth[0], earth[1], groundY + height + 1);
      }
   }

   private void placeCubeNetApronBuildingExteriorDetails(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, CubeNetFeatureRasterizer.FaceCell cell,
      OsmBuildingFeature building,
      java.util.Set<Long> occupied, BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette, int boundaryDistance,
      int groundY, int height, int earthX, int earthZ) {
      Direction facing = this.cubeNetFacadeFacing(cell, occupied, building);
      if (facing == null) return;
      CubeNetFeatureRasterizer.FaceCell outside = this.offsetFaceCell(
         cell, facing.getStepX(), facing.getStepZ());
      if (outside == null) return;
      int[] local = this.sourceLocalForTarget(apron, outside);
      if (local == null) return;
      Direction blockFacing = this.transportFaceDirection(cell, facing);
      int highestFloor = blueprint.highestActiveFloor(boundaryDistance);
      for (int floorIndex = 0; floorIndex <= highestFloor; floorIndex++) {
         if (!blueprint.isFacadeCell(boundaryDistance, floorIndex)) continue;
         int floorBottom = blueprint.floorBottomY(floorIndex);
         int floorTop = blueprint.floorTopY(floorIndex);
         ArnisBuildingRules.ResidentialDecoration residential = ArnisBuildingRules.residentialWindowDecoration(
            blueprint, boundaryDistance, earthX, earthZ, floorIndex, floorBottom, floorTop);
         if (floorIndex == 0 && blueprint.isEntranceCell(earthX, earthZ) && this.worldScale() <= 1.0) {
            this.placeCubeNetApronEntranceDoor(level, chunk, apron, cell, blockFacing, blueprint.floorY() + 1, local);
         }
         if (floorIndex == 0 && blueprint.nearEntranceApproach(earthX, earthZ)
            && blueprint.isEntranceCell(earthX, earthZ)) {
            this.placeApronFeatureBlock(level, chunk, apron, outside, palette.slab(), floorBottom + 3, local);
            continue;
         }
         this.placeCubeNetApronFacadeDepth(level, chunk, apron, outside, blueprint, palette,
            earthX, earthZ, floorIndex, floorBottom, floorTop, local);
         if (TellusBuildingFacade.shouldPlaceAwning(blueprint, boundaryDistance, earthX, earthZ, floorIndex)) {
            this.placeApronFeatureBlock(level, chunk, apron, outside, palette.awning(), floorBottom + 3, local);
         }
         int detailY = Math.min(floorTop, floorBottom + 2);
         if (TellusBuildingFacade.shouldPlaceBalcony(blueprint, boundaryDistance, earthX, earthZ, floorIndex, floorBottom, floorTop)
            || residential == ArnisBuildingRules.ResidentialDecoration.BALCONY) {
            this.placeCubeNetApronBalcony(level, chunk, apron, cell, palette, detailY, facing);
         } else if (TellusBuildingFacade.shouldPlaceFireEscape(blueprint, boundaryDistance, earthX, earthZ, floorIndex)) {
            this.placeApronFeatureBlock(level, chunk, apron, outside, palette.railing(), floorBottom + 1, local);
            this.placeApronFeatureBlock(level, chunk, apron, outside, palette.railing(), floorBottom + 2, local);
         } else if (residential == ArnisBuildingRules.ResidentialDecoration.WINDOW_SILL) {
            this.placeApronFeatureBlock(level, chunk, apron, outside, palette.slab(), detailY, local);
            this.placeApronFeatureBlock(level, chunk, apron, outside, palette.planter(), detailY + 1, local);
         }
         if (ArnisBuildingRules.shouldPlaceResidentialShutter(
            blueprint, boundaryDistance, earthX, earthZ, floorIndex, floorBottom, floorTop)) {
            BlockState shutter = Blocks.OAK_TRAPDOOR.defaultBlockState()
               .setValue(BlockStateProperties.HORIZONTAL_FACING, blockFacing)
               .setValue(BlockStateProperties.OPEN, Boolean.FALSE);
            for (int y = floorBottom + 1; y <= floorTop; y++) {
               if (y > blueprint.floorY() + 1 && ArnisBuildingRules.floorRow(y, blueprint.floorY()) != 0) {
                  this.placeApronFeatureBlock(level, chunk, apron, outside, shutter, y, local);
               }
            }
         }
      }
      int roofTop = groundY + height;
      if (roofTop >= blueprint.roofBaseY(boundaryDistance)
         && blueprint.isFacadeCell(boundaryDistance, highestFloor)) {
         this.placeApronFeatureBlock(level, chunk, apron, outside, palette.trim(), roofTop, local);
         this.placeApronFeatureBlock(level, chunk, apron, outside, palette.slab(), roofTop + 1, local);
      }
      if (roofTop >= blueprint.roofBaseY(boundaryDistance)) {
         this.placeCubeNetApronRooftopEquipment(level, chunk, apron, cell, blueprint,
            boundaryDistance, earthX, earthZ, roofTop, local);
      }
   }

   private void placeCubeNetApronEntranceDoor(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, CubeNetFeatureRasterizer.FaceCell cell,
      Direction facing, int lowerY, int[] local) {
      if (lowerY < this.minY || lowerY + 1 >= this.maxY()) return;
      BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
         .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
         .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
      BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
      this.placeApronFeatureBlock(level, chunk, apron, cell, lower, lowerY, local);
      this.placeApronFeatureBlock(level, chunk, apron, cell, upper, lowerY + 1, local);
   }

   private void placeCubeNetApronRooftopEquipment(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, CubeNetFeatureRasterizer.FaceCell cell,
      BuildingBlueprint blueprint,
      int boundaryDistance, int earthX, int earthZ, int roofTop, int[] local) {
      if (blueprint.isFacadeCell(boundaryDistance, blueprint.highestActiveFloor(boundaryDistance))) return;
      switch (ArnisBuildingRules.rooftopEquipmentAt(blueprint, boundaryDistance, earthX, earthZ)) {
         case HVAC -> {
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.IRON_BLOCK.defaultBlockState(), roofTop + 1, local);
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), roofTop + 2, local);
         }
         case SOLAR_PANEL -> this.placeApronFeatureBlock(level, chunk, apron, cell,
            Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), roofTop + 1, local);
         case ANTENNA -> {
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.IRON_BARS.defaultBlockState(), roofTop + 1, local);
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.IRON_BARS.defaultBlockState(), roofTop + 2, local);
            this.placeApronFeatureBlock(level, chunk, apron, cell, TellusBlockReferences.lightningRodState(), roofTop + 3, local);
         }
         case WATER_TANK -> {
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.BARREL.defaultBlockState(), roofTop + 1, local);
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.CAULDRON.defaultBlockState(), roofTop + 2, local);
         }
         case VENT_STACK -> {
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.COBBLESTONE_WALL.defaultBlockState(), roofTop + 1, local);
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.COBBLESTONE_WALL.defaultBlockState(), roofTop + 2, local);
         }
         case ROOF_ACCESS -> {
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.STONE_BRICKS.defaultBlockState(), roofTop + 1, local);
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.STONE_BRICKS.defaultBlockState(), roofTop + 2, local);
            this.placeApronFeatureBlock(level, chunk, apron, cell, Blocks.STONE_BRICK_SLAB.defaultBlockState(), roofTop + 3, local);
         }
         case NONE -> {
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
      if (centroidProjection == null) return;
      int groundY;
      BuildingProfile profile;
      BuildingBlueprint blueprint;
      TellusBuildingInteriors interior;
      TellusBuildingMaterials.BuildingMaterialPalette palette;
      int height;
      try {
         groundY = this.surfaceYAtPlanar(
            (int)Math.floor(centroidProjection.planarX()), (int)Math.floor(centroidProjection.planarZ()),
            new HashMap<>(), new HashMap<>());
         profile = TellusBuildingProfiles.resolveProfile(building, this.worldScale(), null,
            Double.compare(this.worldScale(), 1.0) == 0);
         blueprint = TellusBuildingBlueprints.createExterior(
            "cube-net:" + building.featureId(), building, profile, this.worldSeed,
            groundY, groundY, groundY + Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale())),
            groundY + Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale())), this.worldScale());
         palette = TellusBuildingMaterials.resolvePalette(blueprint);
         interior = new TellusBuildingInteriors(BuildingFloorPlan.create(blueprint, building, this.worldScale()));
         height = Math.max(3, TellusBuildingProfiles.heightBlocks(building, this.worldScale()));
      } catch (RuntimeException unavailableTerrain) {
         return;
      }
      java.util.Set<Long> occupied = new java.util.HashSet<>();
      for (CubeNetFeatureRasterizer.FaceCell cell : cells) occupied.add(packFaceCell(cell.x(), cell.z()));
      this.prepareCubeNetBuildingFoundation(level, chunk, cells, groundY);
      for (CubeNetFeatureRasterizer.FaceCell cell : cells) {
         if (cell.x() < minX || cell.x() > maxX || cell.z() < minZ || cell.z() > maxZ) continue;
         boolean boundary = !this.cubeNetBuildingOccupies(cell, -1, 0, occupied, building)
            || !this.cubeNetBuildingOccupies(cell, 1, 0, occupied, building)
            || !this.cubeNetBuildingOccupies(cell, 0, -1, occupied, building)
            || !this.cubeNetBuildingOccupies(cell, 0, 1, occupied, building);
         int[] earth = this.earthCoordinates(cell);
         int boundaryDistance = interior.plan().boundaryDistanceAt(earth[0], earth[1]);
         if (boundaryDistance < 0) boundaryDistance = boundary ? 0 : 2;
         for (int y = groundY; y <= groundY + height; y++) {
            int floorIndex = blueprint.floorIndexAtY(y);
            BlockState state = y == groundY ? palette.floor()
               : y >= blueprint.roofBaseY(boundaryDistance)
                  ? TellusBuildingMaterials.resolveLodRoofBlock(blueprint, palette, boundaryDistance, earth[0], earth[1])
                  : blueprint.interiorsEnabled()
                     ? interior.blockAt(palette, boundaryDistance, earth[0], earth[1], floorIndex, y)
                     : boundary
                        ? TellusBuildingMaterials.resolveLodFacadeBlock(blueprint, palette, boundaryDistance,
                           earth[0], earth[1], floorIndex)
                        : Blocks.AIR.defaultBlockState();
            this.placeFeatureBlock(level, chunk, cell, state, y);
         }
         this.placeCubeNetBuildingExteriorDetails(level, chunk, building, cell, occupied, blueprint, palette,
            boundaryDistance, groundY, height, earth[0], earth[1]);
         this.placeCubeNetRoofDetail(level, chunk, cell, blueprint, palette,
            boundaryDistance, earth[0], earth[1], groundY + height + 1);
      }
      this.placeCubeNetSeamBuildingExteriorDetails(level, chunk, building, face,
         minX, minZ, maxX, maxZ, blueprint, palette, interior, groundY, height);
   }

   /**
    * A source-face facade can project its outside block into a different
    * canonical face. The source chunk intentionally cannot write that block;
    * resolve it again from the target face so normal chunk ownership is kept.
    */
   private void placeCubeNetSeamBuildingExteriorDetails(WorldGenLevel level, ChunkAccess chunk,
      OsmBuildingFeature building, CubeNetLayout.Face targetFace,
      int minX, int minZ, int maxX, int maxZ, BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette, TellusBuildingInteriors interior,
      int groundY, int height) {
      int faceSize = this.adapter.layout().faceSize();
      int fromX = Math.max(0, minX), toX = Math.min(faceSize - 1, maxX);
      int fromZ = Math.max(0, minZ), toZ = Math.min(faceSize - 1, maxZ);
      java.util.Set<String> emitted = new java.util.HashSet<>();
      for (int z = fromZ; z <= toZ; z++) for (int x = fromX; x <= toX; x++) {
         boolean edgeX = x == 0 || x == faceSize - 1;
         boolean edgeZ = z == 0 || z == faceSize - 1;
         if (!edgeX && !edgeZ || edgeX && edgeZ) continue;
         CubeNetFeatureRasterizer.FaceCell target = new CubeNetFeatureRasterizer.FaceCell(
            targetFace.index(), x, z);
         for (CubeNetLayout.Side targetSide : CubeNetLayout.Side.values()) {
            if ((targetSide == CubeNetLayout.Side.WEST || targetSide == CubeNetLayout.Side.EAST) != edgeX) continue;
            double edge = targetSide == CubeNetLayout.Side.WEST || targetSide == CubeNetLayout.Side.EAST
               ? z + 0.5 : x + 0.5;
            CubeNetLayout.FacePoint sourcePoint = this.adapter.layout().mapAcross(
               targetFace, targetSide, edge, 0.5);
            CubeNetFeatureRasterizer.FaceCell source = new CubeNetFeatureRasterizer.FaceCell(
               sourcePoint.face().index(), (int)Math.floor(sourcePoint.u()), (int)Math.floor(sourcePoint.v()));
            List<CubeNetFeatureRasterizer.FaceCell> sourceCells = CubeNetFeatureRasterizer.buildingCells(
               this.model, this.adapter.layout(), building, sourcePoint.face(), source.x() - 1,
               source.z() - 1, source.x() + 1, source.z() + 1);
            if (!sourceCells.contains(source)) continue;
            java.util.Set<Long> occupied = new java.util.HashSet<>();
            for (CubeNetFeatureRasterizer.FaceCell cell : sourceCells) {
               occupied.add(packFaceCell(cell.x(), cell.z()));
            }
            Direction facing = this.cubeNetFacadeFacing(source, occupied, building);
            if (facing == null) continue;
            CubeNetFeatureRasterizer.FaceCell projected = this.offsetFaceCell(
               source, facing.getStepX(), facing.getStepZ());
            if (projected == null || projected.face() != target.face()
               || projected.x() != target.x() || projected.z() != target.z()) continue;
            String key = source.face() + ":" + source.x() + ":" + source.z() + ":" + facing;
            if (!emitted.add(key)) continue;
            int[] earth = this.earthCoordinates(source);
            int boundaryDistance = interior.plan().boundaryDistanceAt(earth[0], earth[1]);
            if (boundaryDistance < 0) boundaryDistance = 0;
            this.placeCubeNetBuildingExteriorDetails(level, chunk, building, source, occupied,
               blueprint, palette, boundaryDistance, groundY, height, earth[0], earth[1]);
         }
      }
   }

   /**
    * Reuses Earth's flat building foundation contract on the canonical face.
    * Buildings use one blueprint floor height, so the terrain inside the
    * footprint is raised or cleared to that floor before the body is written.
    * Apron chunks mirror this owner result and never perform a second edit.
    */
   private void prepareCubeNetBuildingFoundation(WorldGenLevel level, ChunkAccess chunk,
      List<CubeNetFeatureRasterizer.FaceCell> cells, int groundY) {
      for (CubeNetFeatureRasterizer.FaceCell cell : cells) {
         CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
            CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
         int worldX = (int)Math.floor(point.x());
         int worldZ = (int)Math.floor(point.z());
         if (Math.floorDiv(worldX, 16) != chunk.getPos().x || Math.floorDiv(worldZ, 16) != chunk.getPos().z) continue;
         int currentSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
         if (currentSurface < groundY - 1) {
            for (int y = currentSurface + 1; y < groundY; y++) {
               this.placeFeatureBlock(level, chunk, cell, Blocks.STONE.defaultBlockState(), y);
            }
         } else if (currentSurface > groundY - 1) {
            for (int y = groundY; y <= currentSurface; y++) {
               this.placeFeatureBlock(level, chunk, cell, Blocks.AIR.defaultBlockState(), y);
            }
         }
      }
   }

   private void placeCubeNetBuildingExteriorDetails(WorldGenLevel level, ChunkAccess chunk,
      OsmBuildingFeature building, CubeNetFeatureRasterizer.FaceCell cell, java.util.Set<Long> occupied,
      BuildingBlueprint blueprint, TellusBuildingMaterials.BuildingMaterialPalette palette,
      int boundaryDistance, int groundY, int height, int earthX, int earthZ) {
      Direction facing = this.cubeNetFacadeFacing(cell, occupied, building);
      if (facing == null) return;
      CubeNetFeatureRasterizer.FaceCell outside = this.offsetFaceCell(
         cell, facing.getStepX(), facing.getStepZ());
      if (outside == null) return;
      Direction blockFacing = this.transportFaceDirection(cell, facing);
      int highestFloor = blueprint.highestActiveFloor(boundaryDistance);
      for (int floorIndex = 0; floorIndex <= highestFloor; floorIndex++) {
         if (!blueprint.isFacadeCell(boundaryDistance, floorIndex)) continue;
         int floorBottom = blueprint.floorBottomY(floorIndex);
         int floorTop = blueprint.floorTopY(floorIndex);
         ArnisBuildingRules.ResidentialDecoration residential = ArnisBuildingRules.residentialWindowDecoration(
            blueprint, boundaryDistance, earthX, earthZ, floorIndex, floorBottom, floorTop);
         if (floorIndex == 0 && blueprint.isEntranceCell(earthX, earthZ) && this.worldScale() <= 1.0) {
            this.placeCubeNetEntranceDoor(level, chunk, cell, blockFacing, blueprint.floorY() + 1);
         }
         if (floorIndex == 0 && blueprint.nearEntranceApproach(earthX, earthZ)
            && blueprint.isEntranceCell(earthX, earthZ)) {
            this.placeCubeNetDecorationBlock(level, chunk, outside, palette.slab(), floorBottom + 3);
            continue;
         }
         this.placeCubeNetFacadeDepth(level, chunk, outside, blueprint, palette,
            earthX, earthZ, floorIndex, floorBottom, floorTop);
         if (TellusBuildingFacade.shouldPlaceAwning(blueprint, boundaryDistance, earthX, earthZ, floorIndex)) {
            this.placeCubeNetDecorationBlock(level, chunk, outside, palette.awning(), floorBottom + 3);
         }
         if (TellusBuildingFacade.shouldPlaceBalcony(blueprint, boundaryDistance, earthX, earthZ, floorIndex, floorBottom, floorTop)
            || residential == ArnisBuildingRules.ResidentialDecoration.BALCONY) {
            this.placeCubeNetBalcony(level, chunk, cell, palette, Math.min(floorTop, floorBottom + 2), facing,
               blockFacing, blueprint.blueprintSeed(), earthX, earthZ, floorIndex);
         } else if (TellusBuildingFacade.shouldPlaceFireEscape(blueprint, boundaryDistance, earthX, earthZ, floorIndex)) {
            this.placeCubeNetDecorationBlock(level, chunk, outside, palette.railing(), floorBottom + 1);
            this.placeCubeNetDecorationBlock(level, chunk, outside, palette.railing(), floorBottom + 2);
         } else if (residential == ArnisBuildingRules.ResidentialDecoration.WINDOW_SILL) {
            this.placeCubeNetDecorationBlock(level, chunk, outside, palette.slab(), Math.min(floorTop, floorBottom + 2));
            this.placeCubeNetDecorationBlock(level, chunk, outside, palette.planter(), Math.min(floorTop, floorBottom + 2) + 1);
         }
         if (ArnisBuildingRules.shouldPlaceResidentialShutter(
            blueprint, boundaryDistance, earthX, earthZ, floorIndex, floorBottom, floorTop)) {
            BlockState shutter = Blocks.OAK_TRAPDOOR.defaultBlockState()
               .setValue(BlockStateProperties.HORIZONTAL_FACING, blockFacing)
               .setValue(BlockStateProperties.OPEN, Boolean.FALSE);
            for (int y = floorBottom + 1; y <= floorTop; y++) {
               if (y > blueprint.floorY() + 1 && ArnisBuildingRules.floorRow(y, blueprint.floorY()) != 0) {
                  this.placeCubeNetDecorationBlock(level, chunk, outside, shutter, y);
               }
            }
         }
      }
      int roofTop = groundY + height;
      if (roofTop >= blueprint.roofBaseY(boundaryDistance)
         && blueprint.isFacadeCell(boundaryDistance, highestFloor)) {
         this.placeCubeNetDecorationBlock(level, chunk, outside, palette.trim(), roofTop);
         this.placeCubeNetDecorationBlock(level, chunk, outside, palette.slab(), roofTop + 1);
      }
      if (roofTop >= blueprint.roofBaseY(boundaryDistance)) {
         this.placeCubeNetRooftopEquipment(level, chunk, cell, blueprint,
            boundaryDistance, earthX, earthZ, roofTop);
      }
   }

   private void placeCubeNetEntranceDoor(WorldGenLevel level, ChunkAccess chunk,
      CubeNetFeatureRasterizer.FaceCell cell, Direction facing, int lowerY) {
      if (lowerY < this.minY || lowerY + 1 >= this.maxY()) return;
      BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
         .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
         .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
      BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
      this.placeFeatureBlock(level, chunk, cell, lower, lowerY);
      this.placeFeatureBlock(level, chunk, cell, upper, lowerY + 1);
   }

   private void placeCubeNetRooftopEquipment(WorldGenLevel level, ChunkAccess chunk,
      CubeNetFeatureRasterizer.FaceCell cell, BuildingBlueprint blueprint,
      int boundaryDistance,
      int earthX, int earthZ, int roofTop) {
      if (!blueprint.isFacadeCell(boundaryDistance, blueprint.highestActiveFloor(boundaryDistance))) {
         switch (ArnisBuildingRules.rooftopEquipmentAt(blueprint, boundaryDistance, earthX, earthZ)) {
            case HVAC -> {
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.IRON_BLOCK.defaultBlockState(), roofTop + 1);
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), roofTop + 2);
            }
            case SOLAR_PANEL -> this.placeCubeNetDecorationBlock(level, chunk, cell,
               Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), roofTop + 1);
            case ANTENNA -> {
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.IRON_BARS.defaultBlockState(), roofTop + 1);
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.IRON_BARS.defaultBlockState(), roofTop + 2);
               this.placeCubeNetDecorationBlock(level, chunk, cell, TellusBlockReferences.lightningRodState(), roofTop + 3);
            }
            case WATER_TANK -> {
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.BARREL.defaultBlockState(), roofTop + 1);
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.CAULDRON.defaultBlockState(), roofTop + 2);
            }
            case VENT_STACK -> {
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.COBBLESTONE_WALL.defaultBlockState(), roofTop + 1);
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.COBBLESTONE_WALL.defaultBlockState(), roofTop + 2);
            }
            case ROOF_ACCESS -> {
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.STONE_BRICKS.defaultBlockState(), roofTop + 1);
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.STONE_BRICKS.defaultBlockState(), roofTop + 2);
               this.placeCubeNetDecorationBlock(level, chunk, cell, Blocks.STONE_BRICK_SLAB.defaultBlockState(), roofTop + 3);
            }
            case NONE -> {
            }
         }
      }
   }

   private void placeCubeNetFacadeDepth(WorldGenLevel level, ChunkAccess chunk,
      CubeNetFeatureRasterizer.FaceCell outside, BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette, int earthX, int earthZ,
      int floorIndex, int floorBottom, int floorTop) {
      if (floorTop <= floorBottom) return;
      int bay = ArnisBuildingRules.facadeBay(blueprint, earthX, earthZ);
      BlockState lower = palette.accent();
      switch (blueprint.style().wallDepthStyle()) {
         case SUBTLE_PILASTERS -> { if (bay != 3) return; }
         case MODERN_PILLARS, INSTITUTIONAL_BANDS -> {
            if (bay == 3) {
               // Continue with the full pilaster below.
            } else if (bay < 3) {
               this.placeCubeNetDecorationBlock(level, chunk, outside, lower, floorBottom + 1);
               this.placeCubeNetDecorationBlock(level, chunk, outside, palette.trim(), floorTop);
               return;
            } else return;
         }
         case INDUSTRIAL_BEAMS, GLASS_CURTAIN -> {
            if (!ArnisBuildingRules.isFacadeCorner(blueprint, earthX, earthZ, floorIndex)) return;
         }
         case HISTORIC_ORNATE -> {
            this.placeCubeNetDecorationBlock(level, chunk, outside, lower, floorBottom + 1);
            if (bay == 0 || bay == 2) {
               this.placeCubeNetDecorationBlock(level, chunk, outside, palette.trim(), Math.max(floorBottom + 2, floorTop - 1));
            }
            this.placeCubeNetDecorationBlock(level, chunk, outside, palette.trim(), floorTop + 1);
            return;
         }
         case RELIGIOUS_BUTTRESS -> {
            if (bay != 0) {
               this.placeCubeNetDecorationBlock(level, chunk, outside, palette.trim(), floorTop + 1);
               return;
            }
         }
         case SKYSCRAPER_FINS -> {
            this.placeCubeNetDecorationBlock(level, chunk, outside, lower, floorBottom + 1);
            if (bay != 3) {
               this.placeCubeNetDecorationBlock(level, chunk, outside, palette.trim(), floorTop);
               return;
            }
         }
         case NONE -> { return; }
      }
      for (int y = floorBottom + 1; y <= floorTop; y++) {
         this.placeCubeNetDecorationBlock(level, chunk, outside, y == floorTop ? palette.accent() : lower, y);
      }
   }

   private void placeCubeNetBalcony(WorldGenLevel level, ChunkAccess chunk,
      CubeNetFeatureRasterizer.FaceCell cell, TellusBuildingMaterials.BuildingMaterialPalette palette,
      int balconyY, Direction facing, Direction blockFacing,
      long blueprintSeed, int earthX, int earthZ, int floorIndex) {
      Direction tangent = facing.getClockWise();
      for (int offset = -1; offset <= 1; offset++) {
         for (int depth = 1; depth <= 2; depth++) {
            CubeNetFeatureRasterizer.FaceCell target = this.offsetFaceCell(cell,
               facing.getStepX() * depth + tangent.getStepX() * offset,
               facing.getStepZ() * depth + tangent.getStepZ() * offset);
            if (target != null) this.placeCubeNetDecorationBlock(level, chunk, target, palette.slab(), balconyY);
         }
      }
      for (int offset = -1; offset <= 1; offset++) {
         CubeNetFeatureRasterizer.FaceCell target = this.offsetFaceCell(cell,
            facing.getStepX() * 3 + tangent.getStepX() * offset,
            facing.getStepZ() * 3 + tangent.getStepZ() * offset);
         if (target != null) this.placeCubeNetDecorationBlock(level, chunk, target, palette.railing(), balconyY + 1);
      }
      for (int depth = 1; depth <= 2; depth++) {
         CubeNetFeatureRasterizer.FaceCell left = this.offsetFaceCell(cell,
            facing.getStepX() * depth - tangent.getStepX() * 2,
            facing.getStepZ() * depth - tangent.getStepZ() * 2);
         CubeNetFeatureRasterizer.FaceCell right = this.offsetFaceCell(cell,
            facing.getStepX() * depth + tangent.getStepX() * 2,
            facing.getStepZ() * depth + tangent.getStepZ() * 2);
         if (left != null) this.placeCubeNetDecorationBlock(level, chunk, left, palette.railing(), balconyY + 1);
         if (right != null) this.placeCubeNetDecorationBlock(level, chunk, right, palette.railing(), balconyY + 1);
      }
      int roll = Math.floorMod((int)(blueprintSeed ^ (long)earthX * 341873128712L
         ^ (long)earthZ * 132897987541L ^ (long)floorIndex * 31L), 100);
      CubeNetFeatureRasterizer.FaceCell furniture = this.offsetFaceCell(cell,
         facing.getStepX() + tangent.getStepX() * (roll < 50 ? -1 : 1),
         facing.getStepZ() + tangent.getStepZ() * (roll < 50 ? -1 : 1));
      if (furniture != null) {
         if (roll < 30) {
            this.placeCubeNetDecorationBlock(level, chunk, furniture, Blocks.CAULDRON.defaultBlockState(), balconyY + 1);
            this.placeCubeNetDecorationBlock(level, chunk, furniture, Blocks.OAK_LEAVES.defaultBlockState(), balconyY + 2);
         } else if (roll < 55) {
            this.placeCubeNetDecorationBlock(level, chunk, furniture,
               Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, blockFacing.getOpposite()), balconyY + 1);
         }
      }
   }

   private void placeCubeNetApronBalcony(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, CubeNetFeatureRasterizer.FaceCell cell,
      TellusBuildingMaterials.BuildingMaterialPalette palette, int balconyY, Direction facing) {
      Direction tangent = facing.getClockWise();
      for (int offset = -1; offset <= 1; offset++) {
         for (int depth = 1; depth <= 2; depth++) {
            this.placeCubeNetApronBalconyCell(level, chunk, apron, cell,
               facing.getStepX() * depth + tangent.getStepX() * offset,
               facing.getStepZ() * depth + tangent.getStepZ() * offset,
               palette.slab(), balconyY);
         }
      }
      for (int offset = -1; offset <= 1; offset++) {
         this.placeCubeNetApronBalconyCell(level, chunk, apron, cell,
            facing.getStepX() * 3 + tangent.getStepX() * offset,
            facing.getStepZ() * 3 + tangent.getStepZ() * offset,
            palette.railing(), balconyY + 1);
      }
      for (int depth = 1; depth <= 2; depth++) {
         this.placeCubeNetApronBalconyCell(level, chunk, apron, cell,
            facing.getStepX() * depth - tangent.getStepX() * 2,
            facing.getStepZ() * depth - tangent.getStepZ() * 2,
            palette.railing(), balconyY + 1);
         this.placeCubeNetApronBalconyCell(level, chunk, apron, cell,
            facing.getStepX() * depth + tangent.getStepX() * 2,
            facing.getStepZ() * depth + tangent.getStepZ() * 2,
            palette.railing(), balconyY + 1);
      }
   }

   private void placeCubeNetApronBalconyCell(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, CubeNetFeatureRasterizer.FaceCell origin,
      int dx, int dz, BlockState state, int y) {
      CubeNetFeatureRasterizer.FaceCell target = this.offsetFaceCell(origin, dx, dz);
      if (target == null) return;
      int[] local = this.sourceLocalForTarget(apron, target);
      if (local != null) this.placeApronFeatureBlock(level, chunk, apron, target, state, y, local);
   }

   private CubeNetFeatureRasterizer.FaceCell offsetFaceCell(
      CubeNetFeatureRasterizer.FaceCell cell, int dx, int dz
   ) {
      int faceSize = this.adapter.layout().faceSize();
      CubeNetLayout.Face face = CubeNetLayout.Face.of(cell.face());
      double u = cell.x() + 0.5 + dx;
      double v = cell.z() + 0.5 + dz;
      CubeNetLayout layout = this.adapter.layout();
      for (int step = 0; step < CubeNetLayout.FACE_COUNT; step++) {
         if (u >= 0.0 && u < faceSize && v >= 0.0 && v < faceSize) {
            return new CubeNetFeatureRasterizer.FaceCell(face.index(),
               (int)Math.floor(u), (int)Math.floor(v));
         }
         boolean outsideU = u < 0.0 || u >= faceSize;
         boolean outsideV = v < 0.0 || v >= faceSize;
         // A displacement that reaches two edges at once lands on a cube
         // vertex. There is no unique tangent continuation at that point.
         if (outsideU && outsideV) return null;
         if (outsideU) {
            CubeNetLayout.Side side = u < 0.0 ? CubeNetLayout.Side.WEST : CubeNetLayout.Side.EAST;
            double penetration = u < 0.0 ? -u : u - faceSize;
            CubeNetLayout.FacePoint mapped = layout.mapAcross(face, side, v, penetration);
            face = mapped.face();
            u = mapped.u();
            v = mapped.v();
         } else {
            CubeNetLayout.Side side = v < 0.0 ? CubeNetLayout.Side.NORTH : CubeNetLayout.Side.SOUTH;
            double penetration = v < 0.0 ? -v : v - faceSize;
            CubeNetLayout.FacePoint mapped = layout.mapAcross(face, side, u, penetration);
            face = mapped.face();
            u = mapped.u();
            v = mapped.v();
         }
      }
      return null;
   }

   private void placeCubeNetApronFacadeDepth(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, CubeNetFeatureRasterizer.FaceCell outside,
      BuildingBlueprint blueprint, TellusBuildingMaterials.BuildingMaterialPalette palette,
      int earthX, int earthZ, int floorIndex, int floorBottom, int floorTop, int[] local) {
      if (floorTop <= floorBottom) return;
      int bay = ArnisBuildingRules.facadeBay(blueprint, earthX, earthZ);
      BlockState lower = palette.accent();
      switch (blueprint.style().wallDepthStyle()) {
         case SUBTLE_PILASTERS -> { if (bay != 3) return; }
         case MODERN_PILLARS, INSTITUTIONAL_BANDS -> {
            if (bay == 3) {
               // Continue with the full pilaster below.
            } else if (bay < 3) {
               this.placeApronFeatureBlock(level, chunk, apron, outside, lower, floorBottom + 1, local);
               this.placeApronFeatureBlock(level, chunk, apron, outside, palette.trim(), floorTop, local);
               return;
            } else return;
         }
         case INDUSTRIAL_BEAMS, GLASS_CURTAIN -> {
            if (!ArnisBuildingRules.isFacadeCorner(blueprint, earthX, earthZ, floorIndex)) return;
         }
         case HISTORIC_ORNATE -> {
            this.placeApronFeatureBlock(level, chunk, apron, outside, lower, floorBottom + 1, local);
            if (bay == 0 || bay == 2) {
               this.placeApronFeatureBlock(level, chunk, apron, outside, palette.trim(), Math.max(floorBottom + 2, floorTop - 1), local);
            }
            this.placeApronFeatureBlock(level, chunk, apron, outside, palette.trim(), floorTop + 1, local);
            return;
         }
         case RELIGIOUS_BUTTRESS -> {
            if (bay != 0) {
               this.placeApronFeatureBlock(level, chunk, apron, outside, palette.trim(), floorTop + 1, local);
               return;
            }
         }
         case SKYSCRAPER_FINS -> {
            this.placeApronFeatureBlock(level, chunk, apron, outside, lower, floorBottom + 1, local);
            if (bay != 3) {
               this.placeApronFeatureBlock(level, chunk, apron, outside, palette.trim(), floorTop, local);
               return;
            }
         }
         case NONE -> { return; }
      }
      for (int y = floorBottom + 1; y <= floorTop; y++) {
         this.placeApronFeatureBlock(level, chunk, apron, outside,
            y == floorTop ? palette.accent() : lower, y, local);
      }
   }

   private Direction cubeNetFacadeFacing(CubeNetFeatureRasterizer.FaceCell cell,
      java.util.Set<Long> occupied, OsmBuildingFeature building) {
      if (!this.cubeNetBuildingOccupies(cell, -1, 0, occupied, building)) return Direction.WEST;
      if (!this.cubeNetBuildingOccupies(cell, 1, 0, occupied, building)) return Direction.EAST;
      if (!this.cubeNetBuildingOccupies(cell, 0, -1, occupied, building)) return Direction.NORTH;
      if (!this.cubeNetBuildingOccupies(cell, 0, 1, occupied, building)) return Direction.SOUTH;
      return null;
   }

   private boolean cubeNetBuildingOccupies(CubeNetFeatureRasterizer.FaceCell cell,
      int dx, int dz, java.util.Set<Long> occupied, OsmBuildingFeature building) {
      CubeNetFeatureRasterizer.FaceCell neighbor = this.offsetFaceCell(cell, dx, dz);
      if (neighbor == null) return false;
      if (neighbor.face() == cell.face()) return occupied.contains(packFaceCell(neighbor.x(), neighbor.z()));
      SphereWorldModel.GeoPoint point = this.model.exactSurfacePoint(
         neighbor.face(), neighbor.x() + 0.5, neighbor.z() + 0.5);
      return building.containsLonLat(point.longitude(), point.latitude());
   }

   private Direction transportFaceDirection(CubeNetFeatureRasterizer.FaceCell cell, Direction direction) {
      CubeNetLayout.Face face = CubeNetLayout.Face.of(cell.face());
      int faceSize = this.adapter.layout().faceSize();
      CubeNetLayout.Side side = null;
      if (direction == Direction.WEST && cell.x() == 0) side = CubeNetLayout.Side.WEST;
      else if (direction == Direction.EAST && cell.x() == faceSize - 1) side = CubeNetLayout.Side.EAST;
      else if (direction == Direction.NORTH && cell.z() == 0) side = CubeNetLayout.Side.NORTH;
      else if (direction == Direction.SOUTH && cell.z() == faceSize - 1) side = CubeNetLayout.Side.SOUTH;
      if (side == null) return direction;
      double[] transported = this.adapter.layout().transportVector(face, side,
         direction.getStepX(), direction.getStepZ());
      if (Math.abs(transported[0]) >= Math.abs(transported[1])) {
         return transported[0] < 0.0 ? Direction.WEST : Direction.EAST;
      }
      return transported[1] < 0.0 ? Direction.NORTH : Direction.SOUTH;
   }

   private void placeCubeNetDecorationBlock(WorldGenLevel level, ChunkAccess chunk,
      CubeNetFeatureRasterizer.FaceCell cell, BlockState state, int y) {
      CubeNetLayout.PlanarPoint point = this.adapter.layout().toPlanar(new CubeNetLayout.FacePoint(
         CubeNetLayout.Face.of(cell.face()), cell.x() + 0.5, cell.z() + 0.5));
      int worldX = (int)Math.floor(point.x());
      int worldZ = (int)Math.floor(point.z());
      if (Math.floorDiv(worldX, 16) != chunk.getPos().x || Math.floorDiv(worldZ, 16) != chunk.getPos().z
         || y < this.minY || y >= this.maxY()) return;
      BlockPos pos = new BlockPos(worldX, y, worldZ);
      if (level.isEmptyBlock(pos)) level.setBlock(pos, state, 2);
   }

   private void placeCubeNetRoofDetail(WorldGenLevel level, ChunkAccess chunk,
      CubeNetApronResolver.ChunkProjection apron, CubeNetFeatureRasterizer.FaceCell cell, int[] local,
      BuildingBlueprint blueprint, TellusBuildingMaterials.BuildingMaterialPalette palette,
      int boundaryDistance, int earthX, int earthZ, int detailBaseY) {
      BlockState detail = TellusBuildingMaterials.resolveLodRoofDetailBlock(
         blueprint, palette, boundaryDistance, earthX, earthZ);
      if (detail == null) return;
      int height = TellusBuildingMaterials.resolveLodRoofDetailHeight(blueprint, boundaryDistance, earthX, earthZ);
      for (int dy = 0; dy < Math.max(1, height); dy++) {
         this.placeApronFeatureBlock(level, chunk, apron, cell, detail, detailBaseY + dy, local);
      }
   }

   private void placeCubeNetRoofDetail(WorldGenLevel level, ChunkAccess chunk,
      CubeNetFeatureRasterizer.FaceCell cell, BuildingBlueprint blueprint,
      TellusBuildingMaterials.BuildingMaterialPalette palette, int boundaryDistance,
      int earthX, int earthZ, int detailBaseY) {
      BlockState detail = TellusBuildingMaterials.resolveLodRoofDetailBlock(
         blueprint, palette, boundaryDistance, earthX, earthZ);
      if (detail == null) return;
      int height = TellusBuildingMaterials.resolveLodRoofDetailHeight(blueprint, boundaryDistance, earthX, earthZ);
      for (int dy = 0; dy < Math.max(1, height); dy++) {
         this.placeFeatureBlock(level, chunk, cell, detail, detailBaseY + dy);
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

   private int[] sourceLocalForTarget(CubeNetApronResolver.ChunkProjection apron,
      CubeNetFeatureRasterizer.FaceCell target) {
      if (target.face() != apron.targetFace().index()) return null;
      return this.sourceLocalForTarget(apron, target.x(), target.z());
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
            if (this.hasNearbyTree(level, ground)) continue;
            TellusCanopyHeightSource.CanopySample sample = canopy.sampleCanopy(
               column.point(), this.worldScale(), this.worldScale()
            );
            TellusProceduralTreeGenerator.place(
               level, ground, level.getBiome(ground.above()), sample, anchor.seed()
            );
         }
      }
   }

   private boolean hasNearbyTree(WorldGenLevel level, BlockPos ground) {
      for (int y = 1; y <= 12; y++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
         BlockState state = level.getBlockState(ground.offset(dx, y, dz));
         if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES)) return true;
      }
      return false;
   }

   private void spawnCubeNetAxolotls(WorldGenLevel level, ChunkAccess chunk) {
      ServerLevel serverLevel = level.getLevel();
      if (!serverLevel.getServer().isSameThread()) return;
      SphereWorldModel.SphereChunkAddress owner = this.address(chunk.getPos())
         .filter(this.singularityPolicy::admitsTerrainMaterialization).orElse(null);
      if (owner == null) return;
      long seed = ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z)
         ^ this.worldSeed ^ (long)owner.face() * 0x9E3779B97F4A7C15L ^ 8943931030581793800L;
      RandomSource random = RandomSource.create(seed);
      if (random.nextFloat() > AXOLOTL_CHUNK_CHANCE) return;
      int chunkMinX = chunk.getPos().getMinBlockX();
      int chunkMinZ = chunk.getPos().getMinBlockZ();
      int minY = this.minY + 4;
      int spawned = 0;
      int attempts = 6 + random.nextInt(6);
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      for (int attempt = 0; attempt < attempts; attempt++) {
         int worldX = chunkMinX + random.nextInt(16);
         int worldZ = chunkMinZ + random.nextInt(16);
         int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
         int maxY = Math.min(surface - 4, this.seaLevel - 1);
         if (maxY <= minY) continue;
         for (int y = maxY; y >= minY; y--) {
            cursor.set(worldX, y, worldZ);
            if (!level.getBlockState(cursor).getFluidState().is(Fluids.WATER)) continue;
            cursor.set(worldX, y - 1, worldZ);
            if (!level.getBlockState(cursor).is(Blocks.CLAY)) continue;
            cursor.set(worldX, y + 1, worldZ);
            if (!level.getBlockState(cursor).isAir()) continue;
            cursor.set(worldX, y, worldZ);
            if (level.getBiome(cursor).is(Biomes.LUSH_CAVES) && random.nextFloat() <= AXOLOTL_POND_CHANCE) {
               Axolotl axolotl = MinecraftVersionCompat.createAxolotl(serverLevel, cursor.immutable());
               if (axolotl == null) return;
               axolotl.setPersistenceRequired();
               MinecraftVersionCompat.snapTo(axolotl, worldX + 0.5, y + 0.1, worldZ + 0.5,
                  random.nextFloat() * 360.0F, 0.0F);
               if (serverLevel.addFreshEntity(axolotl)) spawned++;
            }
            break;
         }
         if (spawned >= MAX_AXOLOTLS_PER_CHUNK) return;
      }
   }

   /** Applies Tellus's dynamic snow state in geographic coordinates on the canonical face. */
   @Override
   public void applyRealtimeSnowCover(WorldGenLevel level, ChunkAccess chunk) {
      if (!TellusRealtimeState.isHistoricalSnowEnabled()
         && !(TellusRealtimeState.isWeatherEnabled()
            && TellusRealtimeState.precipitationMode() == TellusRealtimeState.PrecipitationMode.SNOW)) {
         return;
      }
      SphereWorldModel.SphereChunkAddress owner = this.address(chunk.getPos())
         .filter(this.singularityPolicy::admitsTerrainMaterialization).orElse(null);
      if (owner == null) return;
      CubeNetLayout.Face face = CubeNetLayout.Face.of(owner.face());
      for (int localX = 0; localX < 16; localX++) {
         for (int localZ = 0; localZ < 16; localZ++) {
            int worldX = chunk.getPos().getMinBlockX() + localX;
            int worldZ = chunk.getPos().getMinBlockZ() + localZ;
            BlockAddress block = this.resolveBlock(worldX, worldZ).orElse(null);
            if (block == null || block.address().face() != face.index()) continue;
            SphereChunkSampler.SurfaceData surface = this.faces[face.index()].terrainPoint(
               new SphereWorldModel.SphereBlockAddress(face.index(), block.x(), block.z()), 1).data();
            if (surface == null || !surface.elevationAvailable() || surface.waterAvailable()) continue;
            SphereWorldModel.GeoPoint point = this.model.exactSurfacePoint(
               face.index(), block.x() + 0.5, block.z() + 0.5);
            double slope = surface.neighbourhood().gradient().slopeDegrees();
            int earthX = coordinateForEarth(EarthProjection.longitudeToBlockX(point.longitude(), this.worldScale()));
            int earthZ = coordinateForEarth(EarthProjection.latToBlockZ(point.latitude(), this.worldScale()));
            if (!TellusRealtimeState.shouldApplySnow(earthX, earthZ)
               || !SnowSlopePolicy.shouldCover(earthX, earthZ, slope)) continue;
            int surfaceY = this.materializer.height(surface.elevationMeters());
            BlockPos ground = new BlockPos(worldX, surfaceY, worldZ);
            BlockState surfaceState = level.getBlockState(ground);
            if (!surfaceState.getFluidState().isEmpty()) continue;
            BlockPos above = ground.above();
            BlockState snow = Blocks.SNOW.defaultBlockState();
            if (level.getBlockState(above).isAir() && snow.canSurvive(level, above)) {
               level.setBlock(above, snow, 2);
               if (surfaceState.hasProperty(BlockStateProperties.SNOWY)
                  && !(Boolean)surfaceState.getValue(BlockStateProperties.SNOWY)) {
                  level.setBlock(ground, surfaceState.setValue(BlockStateProperties.SNOWY, Boolean.TRUE), 2);
               }
            }
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
