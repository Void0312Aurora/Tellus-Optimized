package com.yucareux.tellus.worldgen;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.world.data.biome.BiomeClassification;
import com.yucareux.tellus.world.data.biome.BiomeClassificationProviders;
import com.yucareux.tellus.world.data.ocean.OisstOceanClimateSource;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;

/**
 * Geodetic surface biome source for the Cube-Net coordinate view.
 *
 * <p>The codec receives biome holders from the current world registry. The
 * source resolves a planar quart position to one canonical spherical column,
 * then applies the first-stage cover/water policy. More detailed climate and
 * inland-water rules can extend this policy without changing coordinate
 * ownership.</p>
 */
public final class CubeNetBiomeSource extends TellusBiomeSourceBridge {
   public static final MapCodec<CubeNetBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
      RegistryOps.retrieveGetter(Registries.BIOME),
      com.mojang.serialization.Codec.DOUBLE.fieldOf("world_scale").forGetter(source -> source.model.config().worldScale()),
      com.mojang.serialization.Codec.intRange(0, 35).optionalFieldOf("apron_chunks", 5)
         .forGetter(source -> source.apronChunks),
      com.mojang.serialization.Codec.DOUBLE.optionalFieldOf("excluded_radius_meters",
         SphereSingularityPolicy.DEFAULT_CONFIG.excludedRadiusMeters())
         .forGetter(source -> source.policy.config().excludedRadiusMeters()),
      com.mojang.serialization.Codec.DOUBLE.optionalFieldOf("transition_width_meters",
         SphereSingularityPolicy.DEFAULT_CONFIG.transitionWidthMeters())
         .forGetter(source -> source.policy.config().transitionWidthMeters()),
      FixedBiomeSource.CODEC.fieldOf("fallback").forGetter(source -> source.fallback)
   ).apply(instance, (biomes, scale, apronChunks, excludedRadius, transitionWidth, fallback) ->
      new CubeNetBiomeSource(biomes, scale, apronChunks,
         new SphereSingularityPolicy.Config(excludedRadius, transitionWidth), fallback, null)));

   private final SphereWorldModel model;
   private final CubeNetLayout layout;
   private final CubeNetApronResolver apronResolver;
   private final SphereSingularityPolicy policy;
   private final int apronChunks;
   private final HolderGetter<Biome> biomes;
   private final FixedBiomeSource fallback;
   private final Holder<Biome> plains;
   private final Holder<Biome> ocean;
   private final Holder<Biome> warmOcean;
   private final Holder<Biome> lukewarmOcean;
   private final Holder<Biome> coldOcean;
   private final Holder<Biome> frozenOcean;
   private final Holder<Biome> deepOcean;
   private final Holder<Biome> deepLukewarmOcean;
   private final Holder<Biome> deepColdOcean;
   private final Holder<Biome> deepFrozenOcean;
   private final Holder<Biome> snowyPlains;
   private final Holder<Biome> desert;
   private final Holder<Biome> swamp;
   private final Holder<Biome> mangrove;
   private final Supplier<SphereChunkSampler> sampler;
   private final ClimateSampler climateSampler;

   @FunctionalInterface
   interface ClimateSampler {
      ClimateData sample(SphereWorldModel.GeoPoint point, double worldScale, boolean water);
   }

   record ClimateData(String koppen, OisstOceanClimateSource.Sample ocean) { }

   CubeNetBiomeSource(
      HolderGetter<Biome> biomes, double scale, int apronChunks,
      SphereSingularityPolicy.Config policy, FixedBiomeSource fallback,
      SphereChunkSampler.PointSampler pointSampler
   ) {
      this(biomes, scale, apronChunks, policy, fallback, pointSampler, null);
   }

   CubeNetBiomeSource(
      HolderGetter<Biome> biomes, double scale, int apronChunks,
      SphereSingularityPolicy.Config policy, FixedBiomeSource fallback,
      SphereChunkSampler.PointSampler pointSampler, ClimateSampler climateSampler
   ) {
      this.model = SphereWorldModel.forWorldScale(scale);
      if (apronChunks < 0 || apronChunks > 35 || (long)apronChunks * 16L > this.model.config().faceBlocks()) {
         throw new IllegalArgumentException("apron_chunks");
      }
      this.layout = CubeNetLayout.standard(this.model.config().faceBlocks());
      this.apronChunks = apronChunks;
      this.apronResolver = new CubeNetApronResolver(this.layout, Math.max(16, apronChunks * 16));
      this.policy = new SphereSingularityPolicy(this.model, Objects.requireNonNull(policy, "policy"));
      this.biomes = Objects.requireNonNull(biomes, "biomes");
      this.fallback = Objects.requireNonNull(fallback, "fallback");
      this.plains = biomes.getOrThrow(Biomes.PLAINS);
      this.ocean = biomes.getOrThrow(Biomes.OCEAN);
      this.warmOcean = this.resolveBiome(Biomes.WARM_OCEAN, this.ocean);
      this.lukewarmOcean = this.resolveBiome(Biomes.LUKEWARM_OCEAN, this.ocean);
      this.coldOcean = this.resolveBiome(Biomes.COLD_OCEAN, this.ocean);
      this.frozenOcean = this.resolveBiome(Biomes.FROZEN_OCEAN, this.ocean);
      this.deepOcean = this.resolveBiome(Biomes.DEEP_OCEAN, this.ocean);
      this.deepLukewarmOcean = this.resolveBiome(Biomes.DEEP_LUKEWARM_OCEAN, this.deepOcean);
      this.deepColdOcean = this.resolveBiome(Biomes.DEEP_COLD_OCEAN, this.deepOcean);
      this.deepFrozenOcean = this.resolveBiome(Biomes.DEEP_FROZEN_OCEAN, this.deepOcean);
      this.snowyPlains = biomes.getOrThrow(Biomes.SNOWY_PLAINS);
      this.desert = biomes.getOrThrow(Biomes.DESERT);
      this.swamp = biomes.getOrThrow(Biomes.SWAMP);
      this.mangrove = biomes.getOrThrow(Biomes.MANGROVE_SWAMP);
      this.sampler = Suppliers.memoize(() -> pointSampler == null
         ? TellusWorldgenSources.sphericalSampler(this.model)
         : new SphereChunkSampler(this.model, pointSampler));
      this.climateSampler = climateSampler == null ? CubeNetBiomeSource::sampleDefaultClimate : climateSampler;
   }

   public CubeNetBiomeSource(HolderGetter<Biome> biomes, double scale, int apronChunks,
      FixedBiomeSource fallback) {
      this(biomes, scale, apronChunks, SphereSingularityPolicy.DEFAULT_CONFIG, fallback, null);
   }

   FixedBiomeSource fallback() {
      return this.fallback;
   }

   double worldScale() {
      return this.model.config().worldScale();
   }

   int apronChunks() {
      return this.apronChunks;
   }

   SphereSingularityPolicy.Config singularityConfig() {
      return this.policy.config();
   }

   @Override
   protected Codec<? extends BiomeSource> codec() {
      return CODEC.codec();
   }

   @Override
   protected Stream<Holder<Biome>> collectPossibleBiomes() {
      Set<Holder<Biome>> result = new HashSet<>();
      result.add(this.fallback.getNoiseBiome(0, 0, 0));
      result.add(this.plains);
      result.add(this.ocean);
      result.add(this.warmOcean);
      result.add(this.lukewarmOcean);
      result.add(this.coldOcean);
      result.add(this.frozenOcean);
      result.add(this.deepOcean);
      result.add(this.deepLukewarmOcean);
      result.add(this.deepColdOcean);
      result.add(this.deepFrozenOcean);
      result.add(this.snowyPlains);
      result.add(this.desert);
      result.add(this.swamp);
      result.add(this.mangrove);
      for (ResourceKey<Biome> key : BiomeClassification.allBiomeKeys()) result.add(this.resolveBiome(key, this.plains));
      for (ResourceKey<Biome> key : BiomeClassificationProviders.allBiomeKeys()) result.add(this.resolveBiome(key, this.plains));
      return result.stream();
   }

   @Override
   public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
      int blockX = QuartPos.toBlock(quartX);
      int blockZ = QuartPos.toBlock(quartZ);
      Optional<SphereWorldModel.SphereBlockAddress> address = this.resolveAddress(blockX, blockZ);
      if (address.isEmpty()) return this.fallback.getNoiseBiome(quartX, quartY, quartZ);
      SphereWorldModel.SphereBlockAddress canonical = address.get();
      if (!this.policy.admitsTerrainMaterialization(
         new SphereWorldModel.SphereChunkAddress(canonical.face(),
            Math.floorDiv(canonical.blockX(), 16L), Math.floorDiv(canonical.blockZ(), 16L)))) {
         return this.fallback.getNoiseBiome(quartX, quartY, quartZ);
      }
      SphereChunkSampler.SurfaceData surface = this.sampler.get().samplePoint(this.model.surfacePoint(canonical));
      if (!surface.elevationAvailable()) return this.fallback.getNoiseBiome(quartX, quartY, quartZ);
      SphereWorldModel.GeoPoint point = this.model.surfacePoint(canonical);
      ClimateData climate = Objects.requireNonNull(this.climateSampler.sample(
         point, this.model.config().worldScale(), surface.waterAvailable()), "climateData");
      if (surface.waterAvailable()) return this.resolveOceanBiome(surface, climate.ocean());
      String koppen = climate.koppen();
      double earthBlockX = EarthProjection.longitudeToBlockX(point.longitude(), this.model.config().worldScale());
      double earthBlockZ = EarthProjection.latToBlockZ(point.latitude(), this.model.config().worldScale());
      ResourceKey<Biome> biomeKey = BiomeClassificationProviders.findBiomeKey(
         surface.coverClass(), koppen, this.coordinateForClassification(earthBlockX),
         this.coordinateForClassification(earthBlockZ), this.model.config().worldScale());
      if (biomeKey == null) biomeKey = BiomeClassification.findBiomeKey(surface.coverClass(), koppen);
      if (biomeKey == null) biomeKey = BiomeClassification.findFallbackKey(surface.coverClass());
      return this.resolveBiome(biomeKey, this.plains);
   }

   private Holder<Biome> resolveOceanBiome(SphereChunkSampler.SurfaceData surface,
      OisstOceanClimateSource.Sample climate) {
      if (climate == null || !climate.available()) return this.ocean;
      boolean deep = surface.elevationMeters() <= -200.0;
      if (climate.maxIceFraction() >= 0.15 || climate.meanSstC() <= -1.5) {
         return deep ? this.deepFrozenOcean : this.frozenOcean;
      } else if (climate.meanSstC() >= 23.0) {
         return deep ? this.deepLukewarmOcean : this.warmOcean;
      } else if (climate.meanSstC() >= 13.0) {
         return deep ? this.deepLukewarmOcean : this.lukewarmOcean;
      } else {
         return deep ? this.deepColdOcean : this.coldOcean;
      }
   }

   private Holder<Biome> resolveBiome(ResourceKey<Biome> key, Holder<Biome> fallback) {
      return key == null ? fallback : this.biomes.get(key).map(holder -> (Holder<Biome>)holder).orElse(fallback);
   }

   private static ClimateData sampleDefaultClimate(SphereWorldModel.GeoPoint point, double worldScale, boolean water) {
      double blockX = EarthProjection.longitudeToBlockX(point.longitude(), worldScale);
      double blockZ = EarthProjection.latToBlockZ(point.latitude(), worldScale);
      return new ClimateData(
         water ? null : TellusWorldgenSources.koppen().sampleDitheredCode(blockX, blockZ, worldScale),
         water ? TellusWorldgenSources.oceanClimate().sample(blockX, blockZ, worldScale) : null
      );
   }

   private static int coordinateForClassification(double coordinate) {
      if (!Double.isFinite(coordinate)) return 0;
      return coordinate <= Integer.MIN_VALUE ? Integer.MIN_VALUE
         : coordinate >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)coordinate;
   }

   private Optional<SphereWorldModel.SphereBlockAddress> resolveAddress(int blockX, int blockZ) {
      double planarX = blockX + 0.5;
      double planarZ = blockZ + 0.5;
      try {
         CubeNetLayout.FacePoint point = this.layout.fromPlanar(new CubeNetLayout.PlanarPoint(planarX, planarZ));
         return Optional.of(new SphereWorldModel.SphereBlockAddress(
            point.face().index(), (long)Math.floor(point.u()), (long)Math.floor(point.v())));
      } catch (IllegalArgumentException ignored) {
         return this.apronResolver.resolve(planarX, planarZ).map(apron ->
            new SphereWorldModel.SphereBlockAddress(apron.targetFace().index(), apron.blockX(), apron.blockZ()));
      }
   }
}
