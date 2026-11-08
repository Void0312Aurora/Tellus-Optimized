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

   /** Overlays exact resolved native cells on a complete depth-zero body; no fallback materials. */
   public Optional<TellusEarthColumnSnapshot> withResolvedSurface(
      List<TellusEarthSurfaceColumnRules.Cell<SemanticMaterial>> patch, BiomeDecision resolvedBiome
   ) {
      Objects.requireNonNull(patch, "patch");
      Objects.requireNonNull(resolvedBiome, "resolvedBiome");
      if ((long)generation.maxYExclusive() - generation.minY() > 1_048_576L || patch.size() > 1_048_576) {
         throw new IllegalArgumentException("resolved surface exceeds cell budget");
      }
      java.util.Set<Integer> positions = new java.util.HashSet<>();
      boolean known = true;
      for (var cell : patch) {
         Objects.requireNonNull(cell, "surface cell");
         if (cell.y() < generation.minY() || cell.y() > terrainY || !positions.add(cell.y())) {
            throw new IllegalArgumentException("resolved surface coordinate duplicate or outside body");
         }
         known &= cell.material() != SemanticMaterial.UNKNOWN;
      }
      if (!known || surface.depth() != 0 || !completeLayers()) return Optional.empty();
      java.util.TreeMap<Integer, SemanticMaterial> cells = new java.util.TreeMap<>();
      var ordered = new java.util.ArrayList<>(strata.layers());
      ordered.sort(java.util.Comparator.comparingInt(StrataDecision.Layer::minimumY));
      long previous = Long.MIN_VALUE;
      for (var layer : ordered) {
         if (layer.minimumY() <= previous) throw new IllegalArgumentException("resolved body strata overlap");
         previous = layer.maximumY();
         for (long y = Math.max((long)generation.minY(), layer.minimumY()); y <= Math.min((long)terrainY, layer.maximumY()); y++) {
            cells.put((int)y, layer.material());
         }
      }
      if (cells.size() != (long)terrainY - generation.minY() + 1L) return Optional.empty();
      if (terrainY == generation.minY() && cells.get(terrainY) == SemanticMaterial.BEDROCK
         && surface.top() != SemanticMaterial.BEDROCK) return Optional.empty();
      cells.put(terrainY, surface.top());
      if (patch.isEmpty()) return Optional.of(new TellusEarthColumnSnapshot(generation, coordinate, terrainY,
         surface, water, resolvedBiome, strata, diagnostics));
      for (var cell : patch) cells.put(cell.y(), cell.material());
      var layers = new java.util.ArrayList<StrataDecision.Layer>();
      int start = generation.minY();
      SemanticMaterial material = cells.get(start);
      for (var entry : cells.entrySet()) {
         if (entry.getValue() != material) {
            layers.add(new StrataDecision.Layer(start, entry.getKey() - 1, material));
            start = entry.getKey(); material = entry.getValue();
         }
      }
      layers.add(new StrataDecision.Layer(start, terrainY, material));
      var messages = new java.util.ArrayList<>(diagnostics);
      messages.add("resolved exact native surface patch");
      SemanticMaterial top = cells.get(terrainY);
      return Optional.of(new TellusEarthColumnSnapshot(generation, coordinate, terrainY,
         new SurfaceDecision(top, top, 0), water, resolvedBiome, new StrataDecision(layers, true), messages));
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
