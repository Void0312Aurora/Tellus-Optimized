package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy;
import com.yucareux.tellus.worldgen.StrataPolicy;
import com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView;
import com.yucareux.tellus.worldgen.global.SphereColumnContext;
import com.yucareux.tellus.worldgen.global.SphericalOwnerView;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Production spherical terrain rules over an explicit complete surface snapshot. */
public final class TellusSphericalDecisionKernel {
   public record Settings(boolean enableWater, double terrestrialHeightScale, double oceanicHeightScale) {
      public static final Settings DEFAULT = new Settings(true, 1.0, 1.0);
      public Settings {
         if (!(terrestrialHeightScale > 0.0) || !Double.isFinite(terrestrialHeightScale)
            || !(oceanicHeightScale > 0.0) || !Double.isFinite(oceanicHeightScale)) {
            throw new IllegalArgumentException("height scales must be finite and positive");
         }
      }
   }

   /** Resolved native biome at the proposed column top; it is not a vertical biome palette. */
   @FunctionalInterface
   public interface BiomeResolver {
      BiomeDecision resolve(TellusGenerationContext generation, TellusCoordinateContext coordinate,
                            TellusSample sample, int terrainY);
   }

   private final Settings settings;
   private final BiomeResolver biomeResolver;

   public TellusSphericalDecisionKernel(Settings settings) {
      this(settings, (generation, coordinate, sample, terrainY) -> BiomeDecision.unknown());
   }

   /** Explicit opt-in source boundary; terminal/incomplete requests never invoke the resolver. */
   public TellusSphericalDecisionKernel(Settings settings, BiomeResolver biomeResolver) {
      this.settings = Objects.requireNonNull(settings, "settings");
      this.biomeResolver = Objects.requireNonNull(biomeResolver, "biomeResolver");
   }

   public ColumnDecision decide(TellusGenerationContext generation, TellusCoordinateContext coordinate,
                                TellusSample sample) {
      Objects.requireNonNull(generation, "generation");
      Objects.requireNonNull(coordinate, "coordinate");
      Objects.requireNonNull(sample, "sample");
      if (!coordinate.equals(sample.request().coordinate())
         || !generation.geometry().equals(sample.request().geometry())
         || generation.worldSeed() != sample.request().cacheKey().worldSeed()) {
         throw new IllegalArgumentException("spherical decision context does not match sample identity");
      }
      if (sample.status() == SampleStatus.QUEUED) {
         return ColumnDecision.unavailable(coordinate, DecisionStatus.QUEUED, "spherical sample queued");
      }
      if (!sample.complete() || sample.sphericalSurface().isEmpty()) {
         return ColumnDecision.unknown(coordinate, "complete spherical surface snapshot required");
      }
      var owner = coordinate.owner();
      var address = owner instanceof CubeNetCanonicalOwnerView cube ? cube.block()
         : owner instanceof SphericalOwnerView sphere ? sphere.block() : null;
      if (address == null) return ColumnDecision.unknown(coordinate, "spherical kernel requires canonical ownership");
      var snapshot = sample.sphericalSurface().orElseThrow();
      if (snapshot.surfaceDecision().isEmpty()) {
         return ColumnDecision.unknown(coordinate, "resolved spherical surface policy required");
      }
      var data = snapshot.surface();
      int terrainY;
      int waterY;
      try {
         terrainY = height(generation, data.elevationMeters());
         waterY = this.settings.enableWater() && data.waterAvailable()
            ? height(generation, data.waterSurfaceMeters()) : terrainY;
      } catch (IllegalArgumentException error) {
         return ColumnDecision.unknown(coordinate, error.getMessage());
      }
      if (terrainY <= generation.minY() || terrainY >= generation.maxYExclusive()
         || waterY >= generation.maxYExclusive()
         || (long)generation.maxYExclusive() - generation.minY() > 1_048_576L) {
         return ColumnDecision.unknown(coordinate, "native spherical column exceeds vertical range/budget");
      }
      SphereColumnContext column = new SphereColumnContext(address, coordinate.geo(), data);
      var surface = snapshot.surfaceDecision().orElseThrow();
      StrataPolicy.Column strata = TellusSphericalColumnRules.strata(column,
         generation.worldScale(), generation.seaLevel(), generation.worldSeed());
      WaterDecision water = this.settings.enableWater() && data.waterAvailable()
         ? new WaterDecision(data.oceanWater() ? WaterDecision.Kind.OCEAN : WaterDecision.Kind.INLAND,
            OptionalInt.of(waterY)) : WaterDecision.dry();
      StrataDecision layers = strata(strata, generation.minY(), terrainY);
      BiomeDecision biome = Objects.requireNonNull(this.biomeResolver.resolve(generation, coordinate, sample, terrainY),
         "resolved spherical biome");
      return new ColumnDecision(coordinate, DecisionStatus.AVAILABLE, OptionalInt.of(terrainY),
         CubeNetSurfaceDecisionAdapter.map(surface), water, biome, layers,
         List.of(biome.known()
            ? "production spherical radial height/surface/geology; explicit native column-top biome"
            : "production spherical radial height/surface/geology; biome classification remains external"));
   }

   private int height(TellusGenerationContext generation, double metres) {
      return TellusSphericalColumnRules.height(metres, generation.worldScale(), generation.seaLevel(),
         this.settings.terrestrialHeightScale(), this.settings.oceanicHeightScale());
   }

   private static StrataDecision strata(StrataPolicy.Column strata, int minY, int terrainY) {
      List<StrataDecision.Layer> layers = new ArrayList<>();
      layers.add(new StrataDecision.Layer(minY, minY, SemanticMaterial.BEDROCK));
      int start = minY + 1;
      SemanticMaterial previous = material(strata, start);
      for (long y = (long)start + 1L; y <= terrainY; y++) {
         SemanticMaterial next = material(strata, (int)y);
         if (next != previous) {
            layers.add(new StrataDecision.Layer(start, (int)y - 1, previous));
            start = (int)y;
            previous = next;
         }
      }
      layers.add(new StrataDecision.Layer(start, terrainY, previous));
      return new StrataDecision(layers, true);
   }

   private static SemanticMaterial material(StrataPolicy.Column strata, int y) {
      return strata == null ? SemanticMaterial.STONE : TellusSphericalColumnRules.material(strata.material(y));
   }
}
