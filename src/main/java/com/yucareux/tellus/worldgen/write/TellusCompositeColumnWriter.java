package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.feature.FeaturePlan;
import com.yucareux.tellus.worldgen.feature.FeaturePlanStatus;
import com.yucareux.tellus.worldgen.feature.FeaturePlacement;
import com.yucareux.tellus.worldgen.feature.FeaturePlacementSinkGuard;
import com.yucareux.tellus.worldgen.feature.PlacementConflictPolicy;
import com.yucareux.tellus.worldgen.feature.PlacementOperation;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One caller-owned, block-state transaction for terrain and its complete feature plan. */
public final class TellusCompositeColumnWriter<M> {
   public record Cell(int x, int y, int z) { }

   /** Version-local storage adapter. Reads and writes must address exactly this target chunk. */
   public interface Target<M> {
      int chunkX();
      int chunkZ();
      int minY();
      int maxYExclusive();
      M read(Cell cell);
      void write(Cell cell, M state);
      boolean isAir(M state);
      void markDirty();
      default PostProcessing postProcessing() { return null; }
   }

   /** Snapshot/restore must preserve exact marker lists and associated target dirty state. */
   public interface PostProcessing {
      Object snapshot();
      void mark(Cell cell);
      int occurrences(Cell cell);
      void restore(Object snapshot);
   }

   private record Candidate(Cell cell, SemanticMaterial material, FeaturePlacement feature) { }
   private static final long CELL_BUDGET = 1_048_576L;
   private final TellusColumnMaterializer materializer;
   private final SphereWorldModel model;

   public TellusCompositeColumnWriter(TellusColumnMaterializer materializer) {
      this(materializer, null);
   }

   public TellusCompositeColumnWriter(TellusColumnMaterializer materializer, SphereWorldModel model) {
      this.materializer = Objects.requireNonNull(materializer, "materializer");
      this.model = model;
   }

   public WriteResult<M> write(Target<M> target, WriteContext context, ColumnDecision decision,
                                FeaturePlan features, SemanticMapper<M> mapper) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(decision, "decision");
      Objects.requireNonNull(features, "features");
      Objects.requireNonNull(mapper, "mapper");
      TellusPersistenceIdentity identity;
      try {
         identity = TellusPersistenceIdentity.from(context.generation().geometry(), context.targetCoordinate());
      } catch (IllegalArgumentException error) {
         return WriteResult.invalid("composite persistence identity unavailable: " + error.getMessage());
      }
      if (context.apronMirror() || !context.acceptsDecision(decision.coordinate())) {
         return WriteResult.<M>invalid("composite writer requires matching canonical ownership")
            .withPersistenceIdentity(identity);
      }
      if (decision.status() == DecisionStatus.UNKNOWN || features.status() == FeaturePlanStatus.UNKNOWN) {
         return WriteResult.<M>unknown("composite column or feature plan is UNKNOWN").withPersistenceIdentity(identity);
      }
      if (decision.status() == DecisionStatus.QUEUED || features.status() == FeaturePlanStatus.QUEUED) {
         return WriteResult.<M>queued("composite column or feature plan is QUEUED").withPersistenceIdentity(identity);
      }
      WriteResult<SemanticColumnCell> terrain = this.materializer.materializeCells(context, decision);
      if (terrain.status().noWrite()) return new WriteResult<>(terrain.status(), List.of(),
         terrain.diagnostics(), terrain.persistenceIdentity());

      List<Candidate> candidates = new ArrayList<>();
      List<M> mapped = new ArrayList<>();
      Map<Cell, M> original = new LinkedHashMap<>();
      Map<Cell, M> finalStates = new LinkedHashMap<>();
      Set<Cell> flowCells = new java.util.LinkedHashSet<>();
      PostProcessing postProcessing = null;
      Object postSnapshot = null;
      try {
         // Owner/material/budget checks precede all concrete target access and mapping.
         long cells = terrain.mappedWrites().size();
         for (FeaturePlacement placement : features.placements()) {
            String ownerError = FeaturePlacementSinkGuard.validatePlacement(context, placement, true);
            if (ownerError != null) return invalid(terrain, ownerError);
            if (placement.material() == SemanticMaterial.UNKNOWN || placement.operation() == PlacementOperation.MIRROR) {
               return invalid(terrain, "canonical composite requires known non-mirror feature material");
            }
            cells += placement.height();
            if (cells > CELL_BUDGET) return invalid(terrain, "composite column exceeds cell budget");
         }
         Objects.requireNonNull(target, "target");
         CanonicalColumnPosition base = this.model == null ? CanonicalColumnPosition.resolve(context)
            : CanonicalColumnPosition.resolve(context, this.model);
         for (SemanticColumnCell cell : terrain.mappedWrites()) {
            candidates.add(new Candidate(new Cell(base.x(), cell.y(), base.z()), cell.material(), null));
         }
         for (FeaturePlacement placement : features.placements()) {
            int x = Math.addExact(base.x(), placement.offsetX());
            int z = Math.addExact(base.z(), placement.offsetZ());
            // Y retains the existing FeaturePlacement contract: absolute generation Y.
            long endY = (long)placement.offsetY() + placement.height();
            if (placement.offsetY() < context.generation().minY()
               || endY > context.generation().maxYExclusive()) {
               return invalid(terrain, "feature is outside generation range");
            }
            for (int offset = 0; offset < placement.height(); offset++) {
               candidates.add(new Candidate(new Cell(x, Math.addExact(placement.offsetY(), offset), z),
                  placement.material(), placement));
            }
         }
         for (Candidate candidate : candidates) {
            Cell cell = candidate.cell();
            if (Math.floorDiv(cell.x(), 16) != target.chunkX() || Math.floorDiv(cell.z(), 16) != target.chunkZ()
               || cell.y() < target.minY() || cell.y() >= target.maxYExclusive()) {
               return invalid(terrain, "composite cell is outside target chunk/build bounds");
            }
         }
         if (features.placements().stream().anyMatch(placement -> placement.flowState().isPresent())) {
            postProcessing = target.postProcessing();
            if (postProcessing == null) return invalid(terrain, "target has no post-processing transaction support");
         }
         // Map the entire terrain+feature batch before reading or changing any block state.
         for (Candidate candidate : candidates) {
            M state = candidate.feature() == null
               ? mapper.map(candidate.material(), candidate.cell().y(), context)
               : mapper.mapFeature(candidate.feature(), candidate.cell().y(), context);
            mapped.add(Objects.requireNonNull(state, "mapped state"));
         }
         for (Candidate candidate : candidates) {
            if (!original.containsKey(candidate.cell())) {
               original.put(candidate.cell(), Objects.requireNonNull(target.read(candidate.cell()), "original state"));
            }
         }
         Set<Cell> terrainCells = new HashSet<>();
         Set<Cell> featureCells = new HashSet<>();
         for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            Cell cell = candidate.cell();
            FeaturePlacement feature = candidate.feature();
            if (feature == null) {
               finalStates.put(cell, mapped.get(i));
               terrainCells.add(cell);
               continue;
            }
            M effective = finalStates.containsKey(cell) ? finalStates.get(cell) : original.get(cell);
            boolean air = target.isAir(effective);
            if ((feature.operation() == PlacementOperation.REPLACE_AIR
               || feature.conflictPolicy() == PlacementConflictPolicy.PRESERVE_EXISTING) && !air) continue;
            if (feature.conflictPolicy() == PlacementConflictPolicy.REPLACE_TERRAIN && !air
               && (!terrainCells.contains(cell) || featureCells.contains(cell))) {
               return invalid(terrain, "REPLACE_TERRAIN conflicts with an existing non-terrain cell");
            }
            // Ordered owner-only SET/FILL placements use the last planned state.
            finalStates.put(cell, mapped.get(i));
            if (feature.flowState().isPresent()) flowCells.add(cell); else flowCells.remove(cell);
            featureCells.add(cell);
         }
         if (!flowCells.isEmpty()) postSnapshot = Objects.requireNonNull(postProcessing.snapshot(), "post-processing snapshot");
      } catch (RuntimeException error) {
         return invalid(terrain, "composite preparation failed: " + error.getMessage());
      }

      List<Cell> positions = new ArrayList<>(finalStates.keySet());
      List<M> values = new ArrayList<>(finalStates.values());
      int index = 0;
      try {
         for (; index < positions.size(); index++) {
            target.write(positions.get(index), values.get(index));
            if (!values.get(index).equals(target.read(positions.get(index)))) {
               throw new IllegalStateException("target did not retain composite state");
            }
         }
         for (Cell cell : flowCells) {
            int previous = postProcessing.occurrences(cell);
            postProcessing.mark(cell);
            if (postProcessing.occurrences(cell) != Math.addExact(previous, 1)) {
               throw new IllegalStateException("target did not append exactly one post-processing marker");
            }
         }
         target.markDirty();
      } catch (RuntimeException error) {
         RuntimeException restoreError = null;
         for (int i = Math.min(index, positions.size() - 1); i >= 0; i--) {
            Cell cell = positions.get(i);
            try {
               target.write(cell, original.get(cell));
               if (!original.get(cell).equals(target.read(cell))) {
                  throw new IllegalStateException("target did not retain original composite state");
               }
            } catch (RuntimeException failed) {
               if (restoreError == null) restoreError = failed;
               else if (failed != restoreError) restoreError.addSuppressed(failed);
            }
         }
         if (postSnapshot != null) {
            try { postProcessing.restore(postSnapshot); }
            catch (RuntimeException failed) {
               if (restoreError == null) restoreError = failed;
               else if (failed != restoreError) restoreError.addSuppressed(failed);
            }
         }
         if (restoreError != null) {
            IllegalStateException incomplete = new IllegalStateException("composite write failed; rollback incomplete", error);
            incomplete.addSuppressed(restoreError);
            throw incomplete;
         }
         return invalid(terrain, "composite write failed and terrain/features were restored: " + error.getMessage());
      }
      return new WriteResult<>(terrain.status(), values,
         List.of("terrain and feature batch prepared, written and verified together"), terrain.persistenceIdentity(), !flowCells.isEmpty());
   }

   private static <M> WriteResult<M> invalid(WriteResult<SemanticColumnCell> terrain, String diagnostic) {
      return new WriteResult<>(WriteStatus.NO_WRITE_INVALID, List.of(), List.of(diagnostic), terrain.persistenceIdentity());
   }
}
