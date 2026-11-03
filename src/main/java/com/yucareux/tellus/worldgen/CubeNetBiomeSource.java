package com.yucareux.tellus.worldgen;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
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
   private final FixedBiomeSource fallback;
   private final Holder<Biome> plains;
   private final Holder<Biome> ocean;
   private final Holder<Biome> snowyPlains;
   private final Holder<Biome> desert;
   private final Holder<Biome> swamp;
   private final Holder<Biome> mangrove;
   private final Supplier<SphereChunkSampler> sampler;

   CubeNetBiomeSource(
      HolderGetter<Biome> biomes, double scale, int apronChunks,
      SphereSingularityPolicy.Config policy, FixedBiomeSource fallback,
      SphereChunkSampler.PointSampler pointSampler
   ) {
      this.model = SphereWorldModel.forWorldScale(scale);
      if (apronChunks < 0 || apronChunks > 35 || (long)apronChunks * 16L > this.model.config().faceBlocks()) {
         throw new IllegalArgumentException("apron_chunks");
      }
      this.layout = CubeNetLayout.standard(this.model.config().faceBlocks());
      this.apronChunks = apronChunks;
      this.apronResolver = new CubeNetApronResolver(this.layout, Math.max(16, apronChunks * 16));
      this.policy = new SphereSingularityPolicy(this.model, Objects.requireNonNull(policy, "policy"));
      this.fallback = Objects.requireNonNull(fallback, "fallback");
      Objects.requireNonNull(biomes, "biomes");
      this.plains = biomes.getOrThrow(Biomes.PLAINS);
      this.ocean = biomes.getOrThrow(Biomes.OCEAN);
      this.snowyPlains = biomes.getOrThrow(Biomes.SNOWY_PLAINS);
      this.desert = biomes.getOrThrow(Biomes.DESERT);
      this.swamp = biomes.getOrThrow(Biomes.SWAMP);
      this.mangrove = biomes.getOrThrow(Biomes.MANGROVE_SWAMP);
      this.sampler = Suppliers.memoize(() -> pointSampler == null
         ? TellusWorldgenSources.sphericalSampler(this.model)
         : new SphereChunkSampler(this.model, pointSampler));
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
      return Stream.of(this.fallback.getNoiseBiome(0, 0, 0), this.plains, this.ocean,
         this.snowyPlains, this.desert, this.swamp, this.mangrove).distinct();
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
      if (surface.waterAvailable()) return this.ocean;
      return switch (surface.coverClass()) {
         case 70 -> this.snowyPlains;
         case 60 -> this.desert;
         case 90 -> this.swamp;
         case 95 -> this.mangrove;
         default -> this.plains;
      };
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
