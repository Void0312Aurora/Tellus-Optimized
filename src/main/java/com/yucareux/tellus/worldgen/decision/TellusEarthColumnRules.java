package com.yucareux.tellus.worldgen.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Native Earth base-query layering over already resolved integer heights. No source reads or scaling. */
public final class TellusEarthColumnRules {
   private TellusEarthColumnRules() { }

   public record Input(int minY, int maxYExclusive, int terrainY, OptionalInt waterY,
                       boolean terrainShell, int generatorBedrockY, int undergroundDepth,
                       int deepslateBoundaryY) {
      public Input {
         Objects.requireNonNull(waterY, "waterY");
         if (maxYExclusive <= minY || (long)maxYExclusive - minY > 1_048_576L) {
            throw new IllegalArgumentException("Earth base column range exceeds budget or is empty");
         }
         if (terrainY < minY || terrainY >= maxYExclusive || undergroundDepth < 0) {
            throw new IllegalArgumentException("Earth base column terrain/depth is outside its range");
         }
         if (waterY.isPresent() && (waterY.getAsInt() < minY || waterY.getAsInt() >= maxYExclusive)) {
            throw new IllegalArgumentException("Earth base water height is outside its range");
         }
      }

      /** Retains the native int arithmetic and explicit shell support definition. */
      public int supportBottomY() { return Math.max(minY, terrainY - undergroundDepth); }
   }

   public static SemanticMaterial materialAt(Input input, int y) {
      Objects.requireNonNull(input, "input");
      if (y < input.minY() || y >= input.maxYExclusive()) return SemanticMaterial.AIR;
      SemanticMaterial material = SemanticMaterial.AIR;
      if (input.terrainShell()) {
         int support = input.supportBottomY();
         if (y >= support && y <= input.terrainY()) {
            material = y == support ? SemanticMaterial.BEDROCK
               : y < input.deepslateBoundaryY() ? SemanticMaterial.DEEPSLATE : SemanticMaterial.STONE;
         }
      } else {
         if (y <= input.terrainY()) material = y < 0 ? SemanticMaterial.DEEPSLATE : SemanticMaterial.STONE;
         // In the native query this explicit generator bedrock is set after the solid span.
         if (y == input.generatorBedrockY()) material = SemanticMaterial.BEDROCK;
      }
      // Native water is applied last, so it also overrides a bedrock above the terrain in
      // an unusual non-shell accessor. The ordinary semantic snapshot rejects unrepresented
      // above-terrain bedrock rather than changing the reported terrain height.
      if (input.waterY().isPresent() && y > input.terrainY() && y <= input.waterY().getAsInt()) {
         material = SemanticMaterial.WATER;
      }
      return material;
   }

   /** Complete known body, including explicitly resolved air below a shell support. */
   public static StrataDecision strata(Input input) {
      List<StrataDecision.Layer> layers = new ArrayList<>();
      int start = input.minY();
      SemanticMaterial previous = materialAt(input, start);
      for (long position = (long)start + 1L; position <= input.terrainY(); position++) {
         int y = (int)position;
         SemanticMaterial next = materialAt(input, y);
         if (next != previous) {
            layers.add(new StrataDecision.Layer(start, y - 1, previous));
            start = y;
            previous = next;
         }
      }
      layers.add(new StrataDecision.Layer(start, input.terrainY(), previous));
      return new StrataDecision(layers, true);
   }
}
