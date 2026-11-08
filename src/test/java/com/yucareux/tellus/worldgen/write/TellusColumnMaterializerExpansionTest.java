package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.*;
import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.global.*;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class TellusColumnMaterializerExpansionTest {
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1.0);
   private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromSphericalBlock(
      MODEL, new SphereWorldModel.SphereBlockAddress(0, 32, 48));
   private static final TellusGenerationContext GENERATION = new TellusGenerationContext(1, 1, 0, -4, 12);
   private static final WriteContext CONTEXT = WriteContext.canonical(COORDINATE, GENERATION, "expand-test");
   private final TellusColumnMaterializer materializer = new TellusColumnMaterializer();

   @Test void expandsInclusiveStrataFillerTopAndWaterInYOrder() {
      var decision = decision(DecisionStatus.AVAILABLE, 4,
         new SurfaceDecision(SemanticMaterial.GRASS, SemanticMaterial.DIRT, 2),
         new WaterDecision(WaterDecision.Kind.INLAND, OptionalInt.of(7)), strata());
      var result = materializer.materializeCells(CONTEXT, decision);
      assertEquals(WriteStatus.WRITTEN, result.status());
      assertEquals(12, result.mappedWrites().size());
      assertEquals(SemanticMaterial.BEDROCK, at(result, -4));
      assertEquals(SemanticMaterial.DEEPSLATE, at(result, 0));
      assertEquals(SemanticMaterial.STONE, at(result, 1));
      assertEquals(SemanticMaterial.DIRT, at(result, 2));
      assertEquals(SemanticMaterial.DIRT, at(result, 3));
      assertEquals(SemanticMaterial.GRASS, at(result, 4));
      assertEquals(SemanticMaterial.WATER, at(result, 5));
      assertEquals(SemanticMaterial.WATER, at(result, 7));
      assertTrue(result.mappedWrites().stream().noneMatch(cell -> cell.y() > 7));
      assertEquals(result, materializer.materializeCells(CONTEXT, decision));
      assertTrue(result.persistenceIdentity().isPresent());
   }

   @Test void depthCountsFillerAndNeverOverwritesExplicitBottomBedrock() {
      for (int depth : new int[]{0, 1, 3, Integer.MAX_VALUE}) {
         var result = materializer.materializeCells(CONTEXT, decision(DecisionStatus.AVAILABLE, 4,
            new SurfaceDecision(SemanticMaterial.GRASS, SemanticMaterial.DIRT, depth), WaterDecision.dry(), strata()));
         assertEquals(WriteStatus.WRITTEN, result.status());
         assertEquals(SemanticMaterial.BEDROCK, at(result, -4));
         assertEquals(SemanticMaterial.GRASS, at(result, 4));
         assertEquals(Math.min(depth, 7), result.mappedWrites().stream()
            .filter(cell -> cell.material() == SemanticMaterial.DIRT).count());
      }
   }

   @Test void dryOrSubterraneanWaterAddsNoWaterCells() {
      for (int water : new int[]{Integer.MIN_VALUE, 3, 4, 5, 11, 12}) {
         var result = materializer.materializeCells(CONTEXT, decision(DecisionStatus.AVAILABLE, 4,
            SurfaceDecision.defaultStone(), new WaterDecision(WaterDecision.Kind.OCEAN, OptionalInt.of(water)), strata()));
         if (water == 12) assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
         else {
            assertEquals(WriteStatus.WRITTEN, result.status());
            assertEquals(Math.max(0, water > 4 ? water - 4 : 0), result.mappedWrites().stream()
               .filter(cell -> cell.material() == SemanticMaterial.WATER).count());
         }
      }
   }

   @Test void missingAndOverlappingLayersFailBeforeAnyMappedPayload() {
      for (StrataDecision strata : List.of(StrataDecision.unknown(),
         new StrataDecision(List.of(new StrataDecision.Layer(-3, 4, SemanticMaterial.STONE)), true),
         new StrataDecision(List.of(new StrataDecision.Layer(-4, 0, SemanticMaterial.STONE),
            new StrataDecision.Layer(0, 4, SemanticMaterial.DEEPSLATE)), true))) {
         var result = materializer.materializeCells(CONTEXT, decision(DecisionStatus.AVAILABLE, 4,
            SurfaceDecision.defaultStone(), WaterDecision.dry(), strata));
         assertTrue(result.status().noWrite());
         assertTrue(result.mappedWrites().isEmpty());
      }
   }

   @Test void partialEmitsOnlyExplicitKnownLayersAndHonorsNoWritePolicy() {
      var partial = decision(DecisionStatus.PARTIAL, 4,
         new SurfaceDecision(SemanticMaterial.GRASS, SemanticMaterial.UNKNOWN, 2),
         WaterDecision.unknown(), StrataDecision.unknown());
      var result = materializer.materializeCells(CONTEXT, partial);
      assertEquals(WriteStatus.PARTIAL, result.status());
      assertEquals(List.of(new SemanticColumnCell(4, SemanticMaterial.GRASS)), result.mappedWrites());
      assertEquals(WriteStatus.NO_WRITE_PARTIAL, materializer.materializeCells(
         WriteContext.canonical(COORDINATE, GENERATION, "reject", PartialWritePolicy.NO_WRITE), partial).status());
      for (DecisionStatus status : List.of(DecisionStatus.UNKNOWN, DecisionStatus.QUEUED)) {
         assertTrue(materializer.materializeCells(CONTEXT,
            ColumnDecision.unavailable(COORDINATE, status, "unavailable")).mappedWrites().isEmpty());
      }
   }

   @Test void partialUnknownSurfaceDoesNotLeakUnderlyingStrata() {
      var partial = decision(DecisionStatus.PARTIAL, 4,
         new SurfaceDecision(SemanticMaterial.UNKNOWN, SemanticMaterial.UNKNOWN, 2),
         WaterDecision.unknown(), strata());
      var result = materializer.materializeCells(CONTEXT, partial);
      assertEquals(WriteStatus.PARTIAL, result.status());
      assertEquals(6, result.mappedWrites().size());
      assertTrue(result.mappedWrites().stream().noneMatch(cell -> cell.y() >= 2));
   }

   @Test void rejectsInvalidTerrainAndBedrockCollision() {
      for (int y : new int[]{-5, -4, 12}) {
         var result = materializer.materializeCells(CONTEXT, decision(DecisionStatus.AVAILABLE, y,
            SurfaceDecision.defaultStone(), WaterDecision.dry(), strata()));
         assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
      }
      assertEquals(WriteStatus.WRITTEN, materializer.materializeCells(CONTEXT,
         decision(DecisionStatus.AVAILABLE, 11, SurfaceDecision.defaultStone(), WaterDecision.dry(),
            StrataDecision.stone(-4, 11))).status());
   }

   private static StrataDecision strata() {
      return new StrataDecision(List.of(new StrataDecision.Layer(1, 4, SemanticMaterial.STONE),
         new StrataDecision.Layer(-4, -4, SemanticMaterial.BEDROCK),
         new StrataDecision.Layer(-3, 0, SemanticMaterial.DEEPSLATE)), true);
   }
   private static ColumnDecision decision(DecisionStatus status, int y, SurfaceDecision surface,
                                         WaterDecision water, StrataDecision strata) {
      return new ColumnDecision(COORDINATE, status, OptionalInt.of(y), surface, water,
         BiomeDecision.defaultBiome(), strata, List.of());
   }
   private static SemanticMaterial at(WriteResult<SemanticColumnCell> result, int y) {
      return result.mappedWrites().stream().filter(cell -> cell.y() == y).findFirst().orElseThrow().material();
   }
}
