package com.yucareux.tellus.worldgen.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.write.WriteContext;
import java.util.List;
import java.util.Map;

/** Pure owner/conflict/status guards for FeaturePlacementSink. */
class FeaturePlacementSinkGuardTest {
   private static final double SCALE = 1.0;
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
   private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
      "tellus:feature-sink", "feature-sink-v1", "feature-sink-projection", "feature-sink-noise", SCALE);
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
      7L, SCALE, 64, -64, 320, GEOMETRY);

   @org.junit.jupiter.api.Test
   void statusOwnerAndConflictGuardsAreExplicit() {
      TellusCoordinateContext coordinate = TellusCoordinateContextFactory.fromCubeNetCanonical(
         MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
         new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));
      WriteContext context = WriteContext.canonical(coordinate, GENERATION, "feature-sink");
      assertEquals(FeaturePlacementStatus.NO_WRITE_UNKNOWN,
         FeaturePlacementSinkGuard.planResult(FeaturePlan.unknown("unknown")).status());
      assertEquals(FeaturePlacementStatus.NO_WRITE_QUEUED,
         FeaturePlacementSinkGuard.planResult(FeaturePlan.queued("queued")).status());
      assertEquals(FeaturePlacementStatus.NO_WRITE_EMPTY,
         FeaturePlacementSinkGuard.planResult(FeaturePlan.empty("empty")).status());

      FeaturePlacement valid = FeaturePlacement.canonical(
         FeatureKind.VEGETATION, "grass", coordinate, PlacementOperation.SET,
         PlacementConflictPolicy.OWNER_ONLY, com.yucareux.tellus.worldgen.decision.SemanticMaterial.GRASS,
         0, 64, 0, 1, Map.of());
      assertEquals(null, FeaturePlacementSinkGuard.validatePlacement(context, valid));
      FeaturePlacement conflict = new FeaturePlacement(
         FeatureKind.VEGETATION, "bad-mirror", context.targetOwner(), context.targetOwner(),
         PlacementOperation.SET, PlacementConflictPolicy.MIRROR_OWNER,
         com.yucareux.tellus.worldgen.decision.SemanticMaterial.GRASS, 0, 64, 0, 1, false, Map.of());
      assertTrue(FeaturePlacementSinkGuard.validatePlacement(context, conflict).contains("MIRROR_OWNER"));
   }
}
