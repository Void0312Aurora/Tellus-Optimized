package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/** Exact, Minecraft-free snapshot of caller-resolved native Earth column cells. */
public final class TellusEarthNativeColumnSnapshotReader {
   private static final long MAX_COLUMN_CELLS = 1_048_576L;

   private TellusEarthNativeColumnSnapshotReader() { }

   /**
    * Reads each body cell in ascending order, then verifies only the explicitly supplied water
    * interval above terrain. The caller owns the terrain/water bounds; no support, height, material
    * or water inference is performed, and no cell above those bounds is queried. Known AIR and
    * BEDROCK anywhere in the body are ordinary exact materials, including at the terrain top.
    *
    * <p>Null arguments/results are programmer errors ({@link NullPointerException}); out-of-range
    * heights and generation ranges exceeding the cell budget are {@link IllegalArgumentException}s.
    * All supplied arguments, ranges and budgets are checked before calling {@code materialAt}.
    * Callback exceptions propagate. UNKNOWN water/material or a non-WATER cell in the explicit
    * above-terrain water interval returns an empty snapshot, never a substitute body or fallback.
    * A supplied water height at/below terrain has no above-terrain interval, matching the existing
    * WaterDecision/column expansion contract; its identity is retained without inventing cells.</p>
    */
   public static Optional<TellusEarthColumnSnapshot> read(
      TellusGenerationContext generation, TellusCoordinateContext coordinate, int terrainY,
      WaterDecision water, BiomeDecision biome, IntFunction<SemanticMaterial> materialAt
   ) {
      Objects.requireNonNull(generation, "generation");
      Objects.requireNonNull(coordinate, "coordinate");
      Objects.requireNonNull(water, "water");
      Objects.requireNonNull(biome, "biome");
      Objects.requireNonNull(materialAt, "materialAt");
      if (terrainY < generation.minY() || terrainY >= generation.maxYExclusive()) {
         throw new IllegalArgumentException("native Earth terrain is outside generation range");
      }
      if (water.surfaceY().isPresent() && (water.surfaceY().getAsInt() < generation.minY()
         || water.surfaceY().getAsInt() >= generation.maxYExclusive())) {
         throw new IllegalArgumentException("native Earth water is outside generation range");
      }
      if ((long)generation.maxYExclusive() - generation.minY() > MAX_COLUMN_CELLS) {
         throw new IllegalArgumentException("native Earth column exceeds cell budget");
      }
      if (water.kind() == WaterDecision.Kind.UNKNOWN) return Optional.empty();

      List<StrataDecision.Layer> layers = new ArrayList<>();
      int runStart = generation.minY();
      SemanticMaterial previous = null;
      for (long position = generation.minY(); position <= terrainY; position++) {
         int y = (int)position;
         SemanticMaterial material = Objects.requireNonNull(materialAt.apply(y), "native Earth material");
         if (material == SemanticMaterial.UNKNOWN) return Optional.empty();
         if (previous != null && material != previous) {
            layers.add(new StrataDecision.Layer(runStart, y - 1, previous));
            runStart = y;
         }
         previous = material;
      }
      layers.add(new StrataDecision.Layer(runStart, terrainY, previous));
      if (water.surfaceY().isPresent()) {
         for (long position = (long)terrainY + 1L; position <= water.surfaceY().getAsInt(); position++) {
            SemanticMaterial material = Objects.requireNonNull(
               materialAt.apply((int)position), "native Earth water material");
            if (material != SemanticMaterial.WATER) return Optional.empty();
         }
      }
      return Optional.of(new TellusEarthColumnSnapshot(generation, coordinate, terrainY,
         new SurfaceDecision(previous, previous, 0), water, biome, new StrataDecision(layers, true),
         List.of("exact native Earth body and explicit water cells; no inferred support or fallback")));
   }
}
