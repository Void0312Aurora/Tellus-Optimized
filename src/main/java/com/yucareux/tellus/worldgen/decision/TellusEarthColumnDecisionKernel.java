package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Pure Earth column kernel over an already resolved surface palette.
 * Source access, biome registry lookup and generator state remain in the injected resolver.
 */
public final class TellusEarthColumnDecisionKernel {
   @FunctionalInterface
   public interface SurfaceResolver {
      EarthSurfacePalette resolve(TellusSample sample, TellusCoordinateContext coordinate,
                                  int terrainY, boolean underwater);
   }

   private final SurfaceResolver surfaceResolver;
   private final TellusEarthBiomeDecisionKernel biomeKernel;

   public TellusEarthColumnDecisionKernel(SurfaceResolver surfaceResolver) {
      this(surfaceResolver, new TellusEarthBiomeDecisionKernel((cover, koppen, x, z, scale) -> null));
   }

   public TellusEarthColumnDecisionKernel(
      SurfaceResolver surfaceResolver, TellusEarthBiomeDecisionKernel biomeKernel
   ) {
      this.surfaceResolver = Objects.requireNonNull(surfaceResolver, "surfaceResolver");
      this.biomeKernel = Objects.requireNonNull(biomeKernel, "biomeKernel");
   }

   public ColumnDecision decide(
      TellusGenerationContext generation, TellusCoordinateContext coordinate,
      TellusSample sample, String koppenCode
   ) {
      Objects.requireNonNull(generation, "generation");
      Objects.requireNonNull(coordinate, "coordinate");
      Objects.requireNonNull(sample, "sample");
      if (!(coordinate.owner() instanceof EarthOwnerView)
         || !coordinate.equals(sample.request().coordinate())
         || !generation.geometry().equals(sample.request().geometry())) {
         throw new IllegalArgumentException("Earth decision context does not match sample identity");
      }
      if (sample.status() == SampleStatus.QUEUED) {
         return ColumnDecision.unavailable(coordinate, DecisionStatus.QUEUED, "Earth sample queued");
      }
      if (sample.status() == SampleStatus.UNKNOWN || !sample.elevation().complete()) {
         return ColumnDecision.unknown(coordinate, "Earth elevation unavailable");
      }
      int terrainY = height(generation, sample.elevation().metres());
      if (terrainY <= generation.minY() || terrainY >= generation.maxYExclusive()) {
         return ColumnDecision.unknown(coordinate, "Earth terrain is outside generation range");
      }
      boolean underwater = sample.water().known() && sample.water().hasWater()
         && sample.water().surfaceMetres() > sample.elevation().metres();
      EarthSurfacePalette palette = Objects.requireNonNull(
         this.surfaceResolver.resolve(sample, coordinate, terrainY, underwater), "Earth surface palette");
      int semanticDepth = Math.subtractExact(palette.depth(), 1);
      int waterY = underwater ? height(generation, sample.water().surfaceMetres()) : terrainY;
      WaterDecision water = sample.water().known()
         ? sample.water().hasWater()
            ? new WaterDecision(sample.water().ocean() ? WaterDecision.Kind.OCEAN : WaterDecision.Kind.INLAND,
               OptionalInt.of(waterY))
            : WaterDecision.dry()
         : WaterDecision.unknown();
      BiomeDecision biome = this.biomeKernel.decide(sample, coordinate, koppenCode);
      return new ColumnDecision(coordinate, sample.complete() ? DecisionStatus.AVAILABLE : DecisionStatus.PARTIAL,
         OptionalInt.of(terrainY), new SurfaceDecision(underwater ? palette.underwaterTop() : palette.top(), palette.filler(),
            semanticDepth, palette.form()), water, biome,
         StrataDecision.stone(generation.minY(), terrainY),
         List.of("Earth production surface palette; source and biome resolution injected"));
   }

   private static int height(TellusGenerationContext generation, double metres) {
      if (!Double.isFinite(metres)) throw new IllegalArgumentException("Earth elevation must be finite");
      double scaled = metres / generation.worldScale();
      double rounded = Math.round(scaled) + (long)generation.seaLevel();
      return Math.toIntExact(Math.max((long)generation.minY(),
         Math.min((long)generation.maxYExclusive() - 1L, (long)rounded)));
   }
}

// End of file
