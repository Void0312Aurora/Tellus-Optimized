package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.StrataDecision;
import com.yucareux.tellus.worldgen.decision.WaterDecision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Pure column expansion. No Minecraft states, chunks, source reads or registration. */
public final class TellusColumnMaterializer {
   /** Compatibility projection; adapters use materializeCells to retain absolute Y. */
   public WriteResult<SemanticMaterial> materialize(WriteContext context, ColumnDecision decision) {
      WriteResult<SemanticColumnCell> cells = materializeCells(context, decision);
      return new WriteResult<>(cells.status(), cells.mappedWrites().stream()
         .map(SemanticColumnCell::material).toList(), cells.diagnostics(), cells.persistenceIdentity());
   }

   /**
    * Expands known strata, filler, top and water in ascending Y, with one write per Y.
    * Surface depth is the spherical convention: filler count, excluding the top cell.
    * Unknown components are skipped only for an explicit PARTIAL known-layers policy.
    * Air above the known column and bedrock not explicitly in strata are never invented.
    */
   public WriteResult<SemanticColumnCell> materializeCells(WriteContext context, ColumnDecision decision) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(decision, "decision");
      TellusPersistenceIdentity identity;
      try {
         identity = TellusPersistenceIdentity.from(context.generation().geometry(), context.targetCoordinate());
      } catch (IllegalArgumentException error) {
         return WriteResult.invalid("persistence identity unavailable: " + error.getMessage());
      }
      if (!context.acceptsDecision(decision.coordinate())) {
         return WriteResult.<SemanticColumnCell>invalid("decision coordinate is not the canonical write source")
            .withPersistenceIdentity(identity);
      }
      if (decision.status() == DecisionStatus.UNKNOWN) {
         return WriteResult.<SemanticColumnCell>unknown("column decision is UNKNOWN; no semantic writes permitted")
            .withPersistenceIdentity(identity);
      }
      if (decision.status() == DecisionStatus.QUEUED) {
         return WriteResult.<SemanticColumnCell>queued("column decision is QUEUED; no semantic writes permitted")
            .withPersistenceIdentity(identity);
      }
      boolean partial = decision.status() == DecisionStatus.PARTIAL;
      if (partial && !context.partialWritePolicy().allowsKnownLayers()) {
         return WriteResult.<SemanticColumnCell>partialRejected("PARTIAL policy requires a complete decision")
            .withPersistenceIdentity(identity);
      }
      int minY = context.generation().minY();
      int maxY = context.generation().maxYExclusive();
      if (decision.terrainY().isEmpty()) {
         return WriteResult.<SemanticColumnCell>partialRejected("terrain height is unknown")
            .withPersistenceIdentity(identity);
      }
      int terrainY = decision.terrainY().getAsInt();
      if (terrainY < minY || terrainY >= maxY) {
         return WriteResult.<SemanticColumnCell>invalid("terrain height is outside generation range")
            .withPersistenceIdentity(identity);
      }
      if ((long)maxY - minY > 1_048_576L) {
         return WriteResult.<SemanticColumnCell>invalid("column exceeds materializer cell budget")
            .withPersistenceIdentity(identity);
      }
      TreeMap<Integer, SemanticMaterial> cells = new TreeMap<>();
      List<StrataDecision.Layer> layers = new ArrayList<>(decision.strata().layers());
      layers.sort(Comparator.comparingInt(StrataDecision.Layer::minimumY));
      long previousMaximum = Long.MIN_VALUE;
      for (StrataDecision.Layer layer : layers) {
         if (layer.minimumY() <= previousMaximum) {
            return WriteResult.<SemanticColumnCell>invalid("strata layers overlap")
               .withPersistenceIdentity(identity);
         }
         previousMaximum = layer.maximumY();
         if (decision.strata().known() && layer.material() != SemanticMaterial.UNKNOWN) {
            fill(cells, Math.max((long)minY, layer.minimumY()),
               Math.min((long)terrainY, layer.maximumY()), layer.material());
         }
      }
      boolean bottomBedrock = cells.get(minY) == SemanticMaterial.BEDROCK;
      if (bottomBedrock && terrainY == minY
         && (decision.surface().top() != SemanticMaterial.BEDROCK || decision.surface().depth() != 0)) {
         return WriteResult.<SemanticColumnCell>invalid("terrain top collides with explicit bottom bedrock")
            .withPersistenceIdentity(identity);
      }
      var surface = decision.surface();
      if (surface.filler() != SemanticMaterial.UNKNOWN) {
         fill(cells, Math.max(bottomBedrock ? (long)minY + 1L : minY,
               (long)terrainY - surface.depth()),
            (long)terrainY - 1L, surface.filler());
      } else if (surface.depth() > 0) {
         int start = (int)Math.max(bottomBedrock ? (long)minY + 1L : minY,
            (long)terrainY - surface.depth());
         cells.subMap(start, true, terrainY, false).clear();
      }
      if (surface.top() != SemanticMaterial.UNKNOWN) cells.put(terrainY, surface.top());
      else cells.remove(terrainY);
      WaterDecision water = decision.water();
      if (water.kind() == WaterDecision.Kind.INLAND || water.kind() == WaterDecision.Kind.OCEAN) {
         int waterY = water.surfaceY().orElseThrow();
         if (waterY >= maxY) {
            return WriteResult.<SemanticColumnCell>invalid("water surface is outside generation range")
               .withPersistenceIdentity(identity);
         }
         fill(cells, (long)terrainY + 1L, waterY, SemanticMaterial.WATER);
      }
      if (!partial) {
         boolean unknown = !decision.strata().known()
            || layers.stream().anyMatch(layer -> layer.material() == SemanticMaterial.UNKNOWN)
            || surface.top() == SemanticMaterial.UNKNOWN
            || surface.depth() > 0 && surface.filler() == SemanticMaterial.UNKNOWN
            || water.kind() == WaterDecision.Kind.UNKNOWN;
         if (unknown || cells.headMap(terrainY, true).size() != (long)terrainY - minY + 1L) {
            return WriteResult.<SemanticColumnCell>partialRejected("complete decision has unknown or missing terrain layers")
               .withPersistenceIdentity(identity);
         }
      }
      if (cells.isEmpty()) {
         return WriteResult.<SemanticColumnCell>partialRejected("decision has no known layers to write")
            .withPersistenceIdentity(identity);
      }
      List<SemanticColumnCell> writes = cells.entrySet().stream()
         .map(entry -> new SemanticColumnCell(entry.getKey(), entry.getValue())).toList();
      return new WriteResult<>(partial ? WriteStatus.PARTIAL : WriteStatus.WRITTEN, writes,
         List.of("expanded known column layers; surface depth excludes top"), Optional.of(identity));
   }

   private static void fill(TreeMap<Integer, SemanticMaterial> cells, long from, long to,
                            SemanticMaterial material) {
      for (long y = from; y <= to; y++) cells.put((int)y, material);
   }
}
