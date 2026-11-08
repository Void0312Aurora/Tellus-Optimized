package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy;
import com.yucareux.tellus.worldgen.StrataPolicy;
import com.yucareux.tellus.worldgen.global.SphereColumnContext;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Objects;

/** Shared production radial/rock rules used by both native materializers and the semantic kernel. */
public final class TellusSphericalColumnRules {
   private TellusSphericalColumnRules() { }

   /** Exact native radial rounding: ceil above sea level, floor below, no Mercator correction or clamping. */
   public static int height(double elevationMeters, double worldScale, int seaLevel,
                            double terrestrialHeightScale, double oceanicHeightScale) {
      if (!Double.isFinite(elevationMeters)) throw new IllegalArgumentException("elevation must be finite");
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)
         || !(terrestrialHeightScale > 0.0) || !Double.isFinite(terrestrialHeightScale)
         || !(oceanicHeightScale > 0.0) || !Double.isFinite(oceanicHeightScale)) {
         throw new IllegalArgumentException("spherical height scales must be finite and positive");
      }
      double scaled = elevationMeters >= 0.0
         ? elevationMeters * terrestrialHeightScale : elevationMeters * oceanicHeightScale;
      double blocks = scaled / worldScale;
      double height = seaLevel + (scaled >= 0.0 ? Math.ceil(blocks) : Math.floor(blocks));
      if (height < Integer.MIN_VALUE || height > Integer.MAX_VALUE || !Double.isFinite(height)) {
         throw new IllegalArgumentException("elevation exceeds supported block heights");
      }
      return (int)height;
   }

   /** Retains the native MapUnit-before-lithology priority, ECEF coordinates, scale and seed. */
   public static StrataPolicy.Column strata(SphereColumnContext column, double scale, int seaLevel, long seed) {
      Objects.requireNonNull(column, "column");
      var geology = column.surface().geology();
      if (geology == null || !column.surface().elevationAvailable()) return null;
      SphereWorldModel.GeoPoint point = column.point();
      return geology.mapUnit() != null
         ? StrataPolicy.sphericalColumn(geology.mapUnit(), point, scale, seaLevel, seed)
         : StrataPolicy.sphericalColumn(geology.lithology(), column.surface().coverClass() == 60,
            point, scale, seaLevel, seed);
   }

   /** Existing production surface policy, including its existing nonblocking sand-source probe. */
   public static CubeNetSurfacePolicy.Decision surface(SphereColumnContext column, double scale, long seed) {
      return CubeNetSurfacePolicy.resolve(column.point(), column.surface(), scale, seed);
   }

   public static SemanticMaterial material(int nativeMaterial) {
      return switch (nativeMaterial) {
         case StrataPolicy.STONE -> SemanticMaterial.STONE;
         case StrataPolicy.ANDESITE -> SemanticMaterial.ANDESITE;
         case StrataPolicy.DIORITE -> SemanticMaterial.DIORITE;
         case StrataPolicy.GRANITE -> SemanticMaterial.GRANITE;
         case StrataPolicy.CALCITE -> SemanticMaterial.CALCITE;
         case StrataPolicy.DEEPSLATE -> SemanticMaterial.DEEPSLATE;
         case StrataPolicy.TUFF -> SemanticMaterial.TUFF;
         case StrataPolicy.BASALT -> SemanticMaterial.BASALT;
         case StrataPolicy.SMOOTH_BASALT -> SemanticMaterial.SMOOTH_BASALT;
         case StrataPolicy.BLACKSTONE -> SemanticMaterial.BLACKSTONE;
         case StrataPolicy.SANDSTONE -> SemanticMaterial.SANDSTONE;
         case StrataPolicy.SMOOTH_SANDSTONE -> SemanticMaterial.SMOOTH_SANDSTONE;
         case StrataPolicy.RED_SANDSTONE -> SemanticMaterial.RED_SANDSTONE;
         case StrataPolicy.TERRACOTTA -> SemanticMaterial.TERRACOTTA;
         case StrataPolicy.WHITE_TERRACOTTA -> SemanticMaterial.WHITE_TERRACOTTA;
         case StrataPolicy.LIGHT_GRAY_TERRACOTTA -> SemanticMaterial.LIGHT_GRAY_TERRACOTTA;
         case StrataPolicy.RED_TERRACOTTA -> SemanticMaterial.RED_TERRACOTTA;
         case StrataPolicy.ORANGE_TERRACOTTA -> SemanticMaterial.ORANGE_TERRACOTTA;
         case StrataPolicy.PACKED_MUD -> SemanticMaterial.PACKED_MUD;
         case StrataPolicy.COARSE_DIRT -> SemanticMaterial.COARSE_DIRT;
         case StrataPolicy.CLAY -> SemanticMaterial.CLAY;
         case StrataPolicy.COBBLESTONE -> SemanticMaterial.COBBLESTONE;
         default -> throw new IllegalArgumentException("unknown native strata material " + nativeMaterial);
      };
   }
}
