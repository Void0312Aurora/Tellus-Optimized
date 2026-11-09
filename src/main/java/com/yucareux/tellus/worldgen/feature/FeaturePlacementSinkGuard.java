package com.yucareux.tellus.worldgen.feature;

import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.write.SemanticMapper;
import com.yucareux.tellus.worldgen.write.WriteContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared semantic owner/conflict validation used by concrete feature sinks. */
public final class FeaturePlacementSinkGuard {
   private FeaturePlacementSinkGuard() { }

   public static <M> FeaturePlacementResult<M> planResult(FeaturePlan plan) {
      Objects.requireNonNull(plan, "plan");
      return switch (plan.status()) {
         case EMPTY -> FeaturePlacementResult.noWrite(
            FeaturePlacementStatus.NO_WRITE_EMPTY, diagnostic(plan, "feature plan is EMPTY"));
         case UNKNOWN -> FeaturePlacementResult.noWrite(
            FeaturePlacementStatus.NO_WRITE_UNKNOWN, diagnostic(plan, "feature plan is UNKNOWN"));
         case QUEUED -> FeaturePlacementResult.noWrite(
            FeaturePlacementStatus.NO_WRITE_QUEUED, diagnostic(plan, "feature plan is QUEUED"));
         case AVAILABLE -> null;
      };
   }

   public static String validatePlacement(WriteContext context, FeaturePlacement placement) {
      return validatePlacement(context, placement, false);
   }

   public static String validatePlacement(WriteContext context, FeaturePlacement placement, boolean allowFlow) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(placement, "placement");
      if (!allowFlow && placement.flowState().isPresent()) {
         return "exact flow requires a block-state and post-processing composite transaction";
      }
      if (placement.conflictPolicy() == PlacementConflictPolicy.MIRROR_OWNER
         && !placement.apronMirror()) {
         return "MIRROR_OWNER conflict policy requires an apron mirror placement";
      }
      if (context.apronMirror()) {
         if (!placement.apronMirror() || placement.owner() != GeneratorOwner.CUBE_NET_APRON) {
            return "apron target accepts only apron mirror placements";
         }
         GeneratorOwner canonical = context.canonicalSourceCoordinate().orElseThrow()
            .owner().kind();
         if (placement.canonicalOwner() != canonical) {
            return "apron placement canonical owner does not match write context";
         }
      } else if (placement.apronMirror() || placement.owner() != context.targetOwner()
         || placement.canonicalOwner() != context.targetOwner()) {
         return "placement owner does not match canonical write context";
      }
      return null;
   }

   public static long[] targetBlock(WriteContext context) {
      Objects.requireNonNull(context, "context");
      if (context.targetCoordinate().owner() instanceof CubeNetApronOwnerView apron) {
         return new long[]{apron.blockProjection().blockX(), apron.blockProjection().blockZ()};
      }
      var owner = context.targetCoordinate().owner();
      if (owner instanceof com.yucareux.tellus.worldgen.global.EarthOwnerView earth) {
         return new long[]{earth.point().floorBlockX(), earth.point().floorBlockZ()};
      }
      if (owner instanceof com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView cube) {
         return new long[]{cube.block().blockX(), cube.block().blockZ()};
      }
      if (owner instanceof com.yucareux.tellus.worldgen.global.SphericalOwnerView sphere) {
         return new long[]{sphere.block().blockX(), sphere.block().blockZ()};
      }
      throw new IllegalArgumentException("unsupported feature target owner");
   }

   private static String diagnostic(FeaturePlan plan, String fallback) {
      return plan.diagnostics().isEmpty() ? fallback : plan.diagnostics().get(0);
   }
}
