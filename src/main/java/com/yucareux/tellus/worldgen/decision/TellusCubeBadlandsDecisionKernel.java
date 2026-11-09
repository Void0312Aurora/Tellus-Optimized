package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.BadlandsTerrainPolicy;
import java.util.ArrayList;
import java.util.List;

/** Pure native Cube badlands post-pass rules over already-resolved dry/tagged inputs. */
public final class TellusCubeBadlandsDecisionKernel {
   private TellusCubeBadlandsDecisionKernel() { }
   public record Cell(int y, SemanticMaterial material) {
      public Cell {
         java.util.Objects.requireNonNull(material, "material");
         if (material == SemanticMaterial.UNKNOWN) throw new IllegalArgumentException("unknown badlands material");
      }
   }

   /** Top-first native order; the top write is retained even below the lower clipping bound. */
   public static List<Cell> resolve(int minY, int surfaceY, double worldScale, double reliefMeters,
                                    double slopeDegrees, int materialX, int materialZ) {
      int localReliefBlocks = Double.isFinite(reliefMeters)
         ? Math.max(0, (int)Math.ceil(reliefMeters / Math.max(0.0001, worldScale))) : 0;
      if (localReliefBlocks < 3 && Double.isFinite(slopeDegrees)) {
         localReliefBlocks = Math.max(0, (int)Math.round(slopeDegrees / 9.0) * 3);
      }
      if (localReliefBlocks < 3) return List.of(new Cell(surfaceY, plateau(materialX, materialZ)));
      int depth = BadlandsTerrainPolicy.cliffBandDepth(3, localReliefBlocks);
      int bottom = Math.max(minY + 1, surfaceY - depth + 1);
      List<Cell> cells = new ArrayList<>();
      cells.add(new Cell(surfaceY, band(materialX, materialZ, surfaceY)));
      for (long y = (long)surfaceY - 1L; y >= bottom; y--) cells.add(new Cell((int)y, band(materialX, materialZ, (int)y)));
      return List.copyOf(cells);
   }

   private static SemanticMaterial plateau(int x, int z) {
      return switch (BadlandsTerrainPolicy.plateauMaterialIndex(x, z)) {
         case BadlandsTerrainPolicy.PLATEAU_TERRACOTTA, BadlandsTerrainPolicy.PLATEAU_BROWN_TERRACOTTA -> SemanticMaterial.TERRACOTTA;
         case BadlandsTerrainPolicy.PLATEAU_COARSE_DIRT -> SemanticMaterial.COARSE_DIRT;
         default -> SemanticMaterial.RED_SAND;
      };
   }
   private static SemanticMaterial band(int x, int z, int y) {
      return switch (BadlandsTerrainPolicy.bandMaterialIndex(x, z, y)) {
         case BadlandsTerrainPolicy.ORANGE_TERRACOTTA -> SemanticMaterial.ORANGE_TERRACOTTA;
         case BadlandsTerrainPolicy.YELLOW_TERRACOTTA -> SemanticMaterial.YELLOW_TERRACOTTA;
         case BadlandsTerrainPolicy.BROWN_TERRACOTTA -> SemanticMaterial.BROWN_TERRACOTTA;
         case BadlandsTerrainPolicy.RED_TERRACOTTA -> SemanticMaterial.RED_TERRACOTTA;
         case BadlandsTerrainPolicy.LIGHT_GRAY_TERRACOTTA -> SemanticMaterial.LIGHT_GRAY_TERRACOTTA;
         case BadlandsTerrainPolicy.WHITE_TERRACOTTA -> SemanticMaterial.WHITE_TERRACOTTA;
         default -> SemanticMaterial.TERRACOTTA;
      };
   }
}
