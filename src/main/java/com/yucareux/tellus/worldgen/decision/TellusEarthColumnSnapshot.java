package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Caller-resolved Earth facts, bound to exact generation and coordinate identities. */
public record TellusEarthColumnSnapshot(
   TellusGenerationContext generation, TellusCoordinateContext coordinate, int terrainY,
   SurfaceDecision surface, WaterDecision water, BiomeDecision biome, StrataDecision strata,
   List<String> diagnostics
) {
   public TellusEarthColumnSnapshot {
      Objects.requireNonNull(generation, "generation");
      Objects.requireNonNull(coordinate, "coordinate");
      Objects.requireNonNull(surface, "surface");
      Objects.requireNonNull(water, "water");
      Objects.requireNonNull(biome, "biome");
      Objects.requireNonNull(strata, "strata");
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      if (terrainY < generation.minY() || terrainY >= generation.maxYExclusive()) {
         throw new IllegalArgumentException("resolved Earth terrain is outside generation range");
      }
      if (water.surfaceY().isPresent() && (water.surfaceY().getAsInt() < generation.minY()
         || water.surfaceY().getAsInt() >= generation.maxYExclusive())) {
         throw new IllegalArgumentException("resolved Earth water is outside generation range");
      }
   }

   public boolean completeLayers() {
      return strata.known() && strata.layers().stream().noneMatch(layer -> layer.material() == SemanticMaterial.UNKNOWN)
         && surface.top() != SemanticMaterial.UNKNOWN
         && (surface.depth() == 0 || surface.filler() != SemanticMaterial.UNKNOWN)
         && water.kind() != WaterDecision.Kind.UNKNOWN;
   }

   public static Optional<TellusEarthColumnSnapshot> baseColumn(
      TellusGenerationContext generation, TellusCoordinateContext coordinate,
      TellusEarthColumnRules.Input input, WaterDecision.Kind waterKind, BiomeDecision biome
   ) {
      Objects.requireNonNull(input, "input");
      if (input.minY() != generation.minY() || input.maxYExclusive() != generation.maxYExclusive()) {
         throw new IllegalArgumentException("resolved base range differs from generation identity");
      }
      // A classic accessor can put generator bedrock above the reported terrain. ColumnDecision
      // cannot represent that extra dry block; decline instead of shifting the terrain height.
      int bedrock = input.generatorBedrockY();
      if (!input.terrainShell() && bedrock > input.terrainY() && bedrock >= input.minY()
         && bedrock < input.maxYExclusive()
         && (input.waterY().isEmpty() || bedrock > input.waterY().getAsInt())) return Optional.empty();
      WaterDecision water;
      if (input.waterY().isPresent()) {
         if (waterKind != WaterDecision.Kind.INLAND && waterKind != WaterDecision.Kind.OCEAN) {
            throw new IllegalArgumentException("resolved wet base requires an explicit water kind");
         }
         water = new WaterDecision(waterKind, input.waterY());
      } else {
         if (waterKind != WaterDecision.Kind.NONE) throw new IllegalArgumentException("dry base requires NONE kind");
         water = WaterDecision.dry();
      }
      SemanticMaterial top = TellusEarthColumnRules.materialAt(input, input.terrainY());
      return Optional.of(new TellusEarthColumnSnapshot(generation, coordinate, input.terrainY(),
         new SurfaceDecision(top, top, 0), water, Objects.requireNonNull(biome, "biome"),
         TellusEarthColumnRules.strata(input), List.of("native resolved Earth base column; no raw elevation rescaling")));
   }
}
