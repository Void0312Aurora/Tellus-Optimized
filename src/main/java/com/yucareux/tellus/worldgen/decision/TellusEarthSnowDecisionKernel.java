package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import com.yucareux.tellus.worldgen.SurfaceMaterialDepthPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure resolved Earth snow overlay rules. Glacier hollows change only the material:
 * the native one-cell/three-to-eight-cell snow depth still applies, at and below
 * the resolved surface (never surface + 1). Snow eligibility stays with the caller.
 */
public final class TellusEarthSnowDecisionKernel {
   private final long worldSeed;
   private final int minY;
   private final long maxYExclusive;

   /** Unbounded-above compatibility constructor; every int Y remains representable. */
   public TellusEarthSnowDecisionKernel(long worldSeed, int minY) {
      this(worldSeed, minY, (long)Integer.MAX_VALUE + 1L);
   }

   /** Detached target bounds, including a possible exclusive upper bound of MAX_VALUE + 1. */
   public TellusEarthSnowDecisionKernel(long worldSeed, int minY, long maxYExclusive) {
      if (maxYExclusive <= minY || maxYExclusive > (long)Integer.MAX_VALUE + 1L) {
         throw new IllegalArgumentException("invalid Earth snow height bounds");
      }
      this.worldSeed = worldSeed;
      this.minY = minY;
      this.maxYExclusive = maxYExclusive;
   }

   /** Already-resolved native surface facts; no snow-retention/source inference. */
   public record Input(
      int worldX, int worldZ, int surfaceY, boolean fullSlopeCoverage, boolean glacierHollow
   ) { }

   /** Exact default-state material and depth used by both native write backends. */
   public record Column(int depth, SemanticMaterial material) {
      public Column {
         if (depth < 1) throw new IllegalArgumentException("snow depth must be positive");
         requireSnowMaterial(material);
      }

      /** Deliberately the native int expression, including its extreme-int wrap semantics. */
      public int sectionBottom(int surfaceY, int minY) {
         return Math.max(minY, surfaceY - this.depth + 1);
      }
   }

   /** Absolute-Y write; cursor and section ordering are exposed separately below. */
   public record Cell(int y, SemanticMaterial material) {
      public Cell { requireSnowMaterial(material); }
   }

   /**
    * Shared production rule, independent of the target bounds/backend. Native
    * methods retain their existing write loops, cursor side effects and bounds checks.
    */
   public static Column resolveColumn(long worldSeed, Input input) {
      Objects.requireNonNull(input, "input");
      var snow = SurfaceMaterialDepthPolicy.snowColumn(
         input.worldX(), input.worldZ(), worldSeed, input.fullSlopeCoverage());
      SemanticMaterial material = input.glacierHollow()
         ? glacierMaterial(input.worldX(), input.worldZ())
         : snow.powderSnow() ? SemanticMaterial.POWDER_SNOW : SemanticMaterial.SNOW;
      return new Column(snow.depth(), material);
   }

   /** Effective in-bounds native cursor writes, top down, retaining int subtraction. */
   public List<Cell> resolve(Input input) { return this.resolveCursor(input); }

   public List<Cell> resolveCursor(Input input) {
      Column column = resolveColumn(this.worldSeed, input);
      List<Cell> cells = new ArrayList<>(column.depth());
      for (int depth = 0; depth < column.depth(); depth++) {
         int y = input.surfaceY() - depth;
         if (y < this.minY) break;
         if ((long)y < this.maxYExclusive) cells.add(new Cell(y, column.material()));
      }
      return List.copyOf(cells);
   }

   /**
    * Effective in-bounds section fill, bottom up. The bottom is first calculated
    * using the original int expression, not a mathematically repaired long range.
    * At extreme MIN_VALUE surfaces it can differ from the cursor loop by design.
    * Long iteration terminates even when the last representable Y is MAX_VALUE.
    */
   public List<Cell> resolveSections(Input input) {
      Column column = resolveColumn(this.worldSeed, input);
      int bottom = column.sectionBottom(input.surfaceY(), this.minY);
      long top = Math.min((long)input.surfaceY(), this.maxYExclusive - 1L);
      List<Cell> cells = new ArrayList<>();
      for (long y = bottom; y <= top; y++) cells.add(new Cell((int)y, column.material()));
      return List.copyOf(cells);
   }

   public long worldSeed() { return this.worldSeed; }
   public int minY() { return this.minY; }
   public long maxYExclusive() { return this.maxYExclusive; }

   private static SemanticMaterial glacierMaterial(int worldX, int worldZ) {
      return switch (MountainSurfaceRules.glacierSurfaceMaterial(worldX, worldZ)) {
         case MountainSurfaceRules.MATERIAL_ANDESITE -> SemanticMaterial.ANDESITE;
         case MountainSurfaceRules.MATERIAL_GRAVEL -> SemanticMaterial.GRAVEL;
         case MountainSurfaceRules.MATERIAL_COBBLESTONE -> SemanticMaterial.COBBLESTONE;
         case MountainSurfaceRules.MATERIAL_DIORITE -> SemanticMaterial.DIORITE;
         case MountainSurfaceRules.MATERIAL_PACKED_ICE -> SemanticMaterial.PACKED_ICE;
         case MountainSurfaceRules.MATERIAL_BLUE_ICE -> SemanticMaterial.BLUE_ICE;
         case MountainSurfaceRules.MATERIAL_SNOW -> SemanticMaterial.SNOW;
         default -> SemanticMaterial.STONE;
      };
   }

   private static void requireSnowMaterial(SemanticMaterial material) {
      Objects.requireNonNull(material, "material");
      switch (material) {
         case SNOW, POWDER_SNOW, PACKED_ICE, BLUE_ICE, STONE, ANDESITE, GRAVEL, COBBLESTONE, DIORITE -> { }
         default -> throw new IllegalArgumentException("not a native Earth snow/glacier material");
      }
   }
}
