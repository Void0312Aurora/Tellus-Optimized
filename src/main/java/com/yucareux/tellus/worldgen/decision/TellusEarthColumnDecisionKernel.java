package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Earth decisions over caller-resolved native facts, without source access in the kernel.
 * resolvedColumns preserves supplied integer heights and geological layers. The surface-only
 * constructors retain the legacy generic height/stone bridge for caller compatibility.
 * Source access, biome registry lookup and generator state remain in the injected resolver.
 */
public final class TellusEarthColumnDecisionKernel {
   @FunctionalInterface
   public interface SurfaceResolver {
      EarthSurfacePalette resolve(TellusSample sample, TellusCoordinateContext coordinate,
                                  int terrainY, boolean underwater);
   }

   @FunctionalInterface
   public interface ResolvedColumnResolver {
      Optional<TellusEarthColumnSnapshot> resolve(TellusGenerationContext generation,
         TellusCoordinateContext coordinate, TellusSample sample, String koppenCode);
   }

   private final SurfaceResolver surfaceResolver;
   private final TellusEarthBiomeDecisionKernel biomeKernel;
   private final ResolvedColumnResolver resolvedResolver;

   private TellusEarthColumnDecisionKernel(ResolvedColumnResolver resolver) {
      this.surfaceResolver = null;
      this.biomeKernel = null;
      this.resolvedResolver = Objects.requireNonNull(resolver, "resolver");
   }

   /** Resolved native/source inputs bypass the legacy generic height and stone fallback. */
   public static TellusEarthColumnDecisionKernel resolvedColumns(ResolvedColumnResolver resolver) {
      return new TellusEarthColumnDecisionKernel(resolver);
   }

   public TellusEarthColumnDecisionKernel(SurfaceResolver surfaceResolver) {
      this(surfaceResolver, new TellusEarthBiomeDecisionKernel((cover, koppen, x, z, scale) -> null));
   }

   public TellusEarthColumnDecisionKernel(
      SurfaceResolver surfaceResolver, TellusEarthBiomeDecisionKernel biomeKernel
   ) {
      this.surfaceResolver = Objects.requireNonNull(surfaceResolver, "surfaceResolver");
      this.biomeKernel = Objects.requireNonNull(biomeKernel, "biomeKernel");
      this.resolvedResolver = null;
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
      if (this.resolvedResolver != null && sample.request().cacheKey().worldSeed() != generation.worldSeed()) {
         throw new IllegalArgumentException("resolved Earth sample seed differs from generation identity");
      }
      if (sample.status() == SampleStatus.QUEUED) {
         return ColumnDecision.unavailable(coordinate, DecisionStatus.QUEUED, "Earth sample queued");
      }
      if (sample.status() == SampleStatus.UNKNOWN) return ColumnDecision.unknown(coordinate, "Earth sample unavailable");
      if (this.resolvedResolver != null) {
         if ((long)generation.maxYExclusive() - generation.minY() > 1_048_576L) {
            return ColumnDecision.unknown(coordinate, "resolved Earth column exceeds cell budget");
         }
         Optional<TellusEarthColumnSnapshot> resolved = Objects.requireNonNull(
            this.resolvedResolver.resolve(generation, coordinate, sample, koppenCode), "resolved snapshot");
         if (resolved.isEmpty()) return ColumnDecision.unknown(coordinate, "native Earth column unavailable");
         TellusEarthColumnSnapshot snapshot = resolved.orElseThrow();
         if (!generation.equals(snapshot.generation()) || !coordinate.equals(snapshot.coordinate())) {
            throw new IllegalArgumentException("resolved Earth snapshot identity differs from request");
         }
         DecisionStatus status = sample.complete() && snapshot.completeLayers()
            ? DecisionStatus.AVAILABLE : DecisionStatus.PARTIAL;
         return new ColumnDecision(coordinate, status, OptionalInt.of(snapshot.terrainY()),
            snapshot.surface(), snapshot.water(), snapshot.biome(), snapshot.strata(), snapshot.diagnostics());
      }
      if (!sample.elevation().complete()) {
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
