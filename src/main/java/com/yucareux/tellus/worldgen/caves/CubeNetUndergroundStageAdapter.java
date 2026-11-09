package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.write.TellusTargetWriteGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit replay of a completed detached underground plan. No field, source,
 * registry, terrain sampler or generator is retained or invoked by this adapter.
 * Target implementations own native material mapping and exact metadata snapshots.
 * The identity lease excludes participating writers of one actual object only;
 * direct engine writes and different object aliases remain external boundaries.
 */
public final class CubeNetUndergroundStageAdapter {
   public CubeNetUndergroundStageAdapter() { }

   /** Preflights every cell before mutation, then publishes cells and marker occurrences in order. */
   public ReplayResult replay(UndergroundStagePlan plan, Target target) {
      Objects.requireNonNull(plan, "plan");
      if (plan.request().resolution().kind() == com.yucareux.tellus.worldgen.sample.SamplingResolution.Kind.NON_BLOCKING)
          return ReplayResult.queued("nonblocking underground replay remains target cold");
       if (plan.status() == DecisionStatus.QUEUED) return ReplayResult.queued("underground plan queued");
      if (plan.status() != DecisionStatus.AVAILABLE) return ReplayResult.unknown("underground plan unavailable");
      Objects.requireNonNull(target, "target");

      try {
         UndergroundStagePlanner.validateRequest(plan.request());
      } catch (RuntimeException failure) {
         return ReplayResult.invalid("underground preflight failed: " + failure.getClass().getName());
      }

      Object identity = Objects.requireNonNull(target.identity(), "target identity");
      var acquired = TellusTargetWriteGuard.tryAcquire(identity);
      if (acquired.isEmpty()) return ReplayResult.queued("underground target already leased");
      try (var lease = acquired.orElseThrow()) {
         lease.requireOwned(identity);
         List<Previous> previous = new ArrayList<>(plan.deltas().size());
         PostProcessing metadata;
         Object metadataSnapshot;
         boolean dirty;
         try {
            target.preflight();
            var request = plan.request();
            if (target.chunkX() != request.chunkX() || target.chunkZ() != request.chunkZ()
               || target.minY() != request.generation().minY()
               || target.maxYExclusive() != request.generation().maxYExclusive()) {
               return ReplayResult.invalid("underground target chunk or bounds differ");
            }
            metadata = Objects.requireNonNull(target.postProcessing(), "postProcessing");
            for (var delta : plan.deltas()) {
               Object current = Objects.requireNonNull(target.read(delta.x(), delta.y(), delta.z()), "old cell");
               if (!target.replaceable(current, delta.material())) {
                  return ReplayResult.invalid("underground target contains a non-replaceable cell");
               }
               int occurrences = delta.markPostProcessing()
                  ? metadata.occurrences(delta.x(), delta.y(), delta.z()) : 0;
               if (occurrences < 0 || occurrences == Integer.MAX_VALUE) {
                  return ReplayResult.invalid("underground marker count outside supported range");
               }
               previous.add(new Previous(delta, current, occurrences));
            }
            metadataSnapshot = Objects.requireNonNull(metadata.snapshot(), "metadata snapshot");
            dirty = target.dirty();
         } catch (RuntimeException failure) {
            return ReplayResult.invalid("underground preflight failed: " + failure.getClass().getName());
         }

         try {
            for (Previous cell : previous) {
               lease.requireOwned(identity);
               var delta = cell.delta();
               target.write(delta.x(), delta.y(), delta.z(), delta.material());
               if (!target.matches(target.read(delta.x(), delta.y(), delta.z()), delta.material())) {
                  throw new IllegalStateException("underground cell publication readback differs");
               }
               if (delta.markPostProcessing()) {
                  metadata.mark(delta.x(), delta.y(), delta.z());
                  if (metadata.occurrences(delta.x(), delta.y(), delta.z()) != cell.occurrences() + 1) {
                     throw new IllegalStateException("underground marker publication readback differs");
                  }
               }
            }
            if (!previous.isEmpty()) target.setDirty(true);
            if (target.dirty() != (!previous.isEmpty() || dirty)) {
               throw new IllegalStateException("underground dirty publication readback differs");
            }
            return new ReplayResult(ReplayStatus.WRITTEN, previous.size(), List.of());
         } catch (RuntimeException | Error failure) {
            recover(target, metadata, previous, metadataSnapshot, dirty, failure);
            if (failure instanceof Error error) throw error;
            return ReplayResult.invalid("underground publication restored: " + failure.getClass().getName());
         }
      }
   }

   /** Always attempts all cell and metadata recovery, even if an earlier recovery callback fails. */
   private static void recover(Target target, PostProcessing metadata, List<Previous> previous,
                               Object metadataSnapshot, boolean dirty, Throwable original) {
      Throwable firstFailure = null;
      for (int i = previous.size() - 1; i >= 0; i--) {
         Previous cell = previous.get(i);
         var delta = cell.delta();
         try {
            target.writePrevious(delta.x(), delta.y(), delta.z(), cell.value());
         } catch (RuntimeException | Error failure) {
            if (firstFailure == null) firstFailure = failure;
         }
      }
      try {
         metadata.restore(metadataSnapshot);
      } catch (RuntimeException | Error failure) {
         if (firstFailure == null) firstFailure = failure;
      }
      try {
         target.setDirty(dirty);
      } catch (RuntimeException | Error failure) {
         if (firstFailure == null) firstFailure = failure;
      }
      for (Previous cell : previous) {
         var delta = cell.delta();
         try {
            if (!Objects.equals(cell.value(), target.read(delta.x(), delta.y(), delta.z()))) {
               throw new IllegalStateException("underground recovered cell readback differs");
            }
         } catch (RuntimeException | Error failure) {
            if (firstFailure == null) firstFailure = failure;
         }
      }
      try {
         if (!metadata.matchesSnapshot(metadataSnapshot)) {
            throw new IllegalStateException("underground recovered markers readback differs");
         }
      } catch (RuntimeException | Error failure) {
         if (firstFailure == null) firstFailure = failure;
      }
      try {
         if (target.dirty() != dirty) throw new IllegalStateException("underground recovered dirty readback differs");
      } catch (RuntimeException | Error failure) {
         if (firstFailure == null) firstFailure = failure;
      }
      if (firstFailure != null) {
         IllegalStateException incomplete = new IllegalStateException(
            "underground stage recovery incomplete after " + original.getClass().getName(), original);
         if (firstFailure != original) incomplete.addSuppressed(firstFailure);
         throw incomplete;
      }
   }

   private record Previous(UndergroundStagePlan.Delta delta, Object value, int occurrences) { }

   public enum ReplayStatus { WRITTEN, UNKNOWN, QUEUED, INVALID }

   public record ReplayResult(ReplayStatus status, int appliedDeltas, List<String> diagnostics) {
      public ReplayResult {
         Objects.requireNonNull(status, "status");
         if (appliedDeltas < 0 || status != ReplayStatus.WRITTEN && appliedDeltas != 0) {
            throw new IllegalArgumentException("only WRITTEN can expose applied underground deltas");
         }
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      }
      private static ReplayResult invalid(String diagnostic) {
         return new ReplayResult(ReplayStatus.INVALID, 0, List.of(diagnostic));
      }
      private static ReplayResult unknown(String diagnostic) {
         return new ReplayResult(ReplayStatus.UNKNOWN, 0, List.of(diagnostic));
      }
      private static ReplayResult queued(String diagnostic) {
         return new ReplayResult(ReplayStatus.QUEUED, 0, List.of(diagnostic));
      }
   }

   /**
    * A target must map materials without source I/O, preserve exact old values in
    * writePrevious, and supply a stable actual-object identity. replaceable and
    * read are observational callbacks; no callback may mutate another target.
    */
   public interface Target {
      Object identity();
      default void preflight() { }
      int chunkX();
      int chunkZ();
      int minY();
      int maxYExclusive();
      Object read(int x, int y, int z);
      boolean replaceable(Object current, UndergroundStagePlan.UndergroundMaterial material);
      void write(int x, int y, int z, UndergroundStagePlan.UndergroundMaterial material);
      void writePrevious(int x, int y, int z, Object previous);
      PostProcessing postProcessing();
      boolean dirty();
      void setDirty(boolean dirty);
      default boolean matches(Object current, UndergroundStagePlan.UndergroundMaterial material) {
         return material.equals(current);
      }
   }

   /** Snapshots retain the complete ordered duplicate-sensitive marker state. */
   public interface PostProcessing {
      Object snapshot();
      int occurrences(int x, int y, int z);
      void mark(int x, int y, int z);
      void restore(Object snapshot);
      default boolean matchesSnapshot(Object snapshot) {
         return Objects.deepEquals(snapshot, snapshot());
      }
   }
}
