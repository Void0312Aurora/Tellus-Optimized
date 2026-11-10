package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.TellusColumnPlan;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.feature.FeaturePlan;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * Opt-in, same-actual-target chunk batch for participating native writers.
 * Planning callbacks must be read-only. One non-reentrant identity lease covers
 * preflight, planning, block/marker/dirty snapshot, writes and failure recovery.
 * This is not a disk, world-key-alias, biome/status/light/tick or source transaction.
 */
public final class TellusNativeChunkBatch {
   private static final long CELL_BUDGET = 1_048_576L;
   private final TellusColumnMaterializer materializer;
   private final SemanticMapper<BlockState> mapper;
   private final SphereWorldModel model;

   public TellusNativeChunkBatch(TellusColumnMaterializer materializer, SemanticMapper<BlockState> mapper) {
      this(materializer, mapper, null);
   }

   public TellusNativeChunkBatch(TellusColumnMaterializer materializer, SemanticMapper<BlockState> mapper,
                                SphereWorldModel model) {
      this.materializer = Objects.requireNonNull(materializer, "materializer");
      this.mapper = Objects.requireNonNull(mapper, "mapper");
      this.model = model;
   }

   /** Structural value only; target/profile/source checks belong to complete-batch preflight. */
   public record Request(WriteContext context, TellusGenerationContext generation,
                         TellusCoordinateContext coordinate, TellusSampleRequest request) {
      public Request {
         Objects.requireNonNull(context, "context");
         Objects.requireNonNull(generation, "generation");
         Objects.requireNonNull(coordinate, "coordinate");
         Objects.requireNonNull(request, "request");
      }
   }

   /** Deliberately not WriteResult: a chunk batch is not a flat canonical-column storage payload. */
   public record Result(WriteStatus status, List<WriteResult<BlockState>> columns, List<String> diagnostics) {
      public Result {
         Objects.requireNonNull(status, "status");
         columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (status != WriteStatus.WRITTEN && !columns.isEmpty()) {
            throw new IllegalArgumentException("unsuccessful batch must not expose rolled-back column results");
         }
         if (status == WriteStatus.WRITTEN && (columns.isEmpty()
            || columns.stream().anyMatch(column -> column.status() != WriteStatus.WRITTEN))) {
            throw new IllegalArgumentException("written batch requires only complete written columns");
         }
      }
   }

   public Result write(boolean enabled, ChunkAccess target, List<Request> requests,
                        Function<Request, TellusColumnPlan> planner) {
      return writeOwned(enabled, target, requests, planner, null);
   }

   /** Borrow the enclosing native stage's exact owner-thread lease; never close a borrowed token. */
   public Result writeOwned(boolean enabled, ChunkAccess target, List<Request> requests,
                            Function<Request, TellusColumnPlan> planner, TellusTargetWriteGuard.Lease owned) {
      if (!enabled) return stopped(WriteStatus.NO_WRITE_INVALID, "native chunk batch is disabled");
      if (requests == null || requests.isEmpty() || requests.size() > 256) {
         return stopped(WriteStatus.NO_WRITE_INVALID, "native chunk batch requires1..256 requests");
      }
      final List<Request> entries;
      try { entries = List.copyOf(requests); }
      catch (RuntimeException error) { return invalid("invalid batch requests", error); }
      if (!(target instanceof ProtoChunk proto) || planner == null) {
         return stopped(WriteStatus.NO_WRITE_INVALID, "chunk batch requires ProtoChunk and planner");
      }
      if (owned != null) {
         owned.requireOwned(target);
         return writeExclusive(proto, entries, planner, owned);
      }
      var acquired = TellusTargetWriteGuard.tryAcquire(target);
      if (acquired.isEmpty()) return stopped(WriteStatus.NO_WRITE_QUEUED, "native target already has a participating writer");
      try (var lease = acquired.orElseThrow()) {
         return writeExclusive(proto, entries, planner, lease);
      }
   }

   private Result writeExclusive(ProtoChunk target, List<Request> entries,
                                  Function<Request, TellusColumnPlan> planner, TellusTargetWriteGuard.Lease lease) {
         ProtoChunk proto = target;
         lease.requireOwned(target);
         final int minY;
         final int maxY;
         final int baseX;
         final int baseZ;
         final int cellCount;
         final List<TellusColumnPlan> plans = new ArrayList<>(entries.size());
         try {
            minY = target.getMinBuildHeight();
            maxY = target.getMaxBuildHeight();
            long height = (long)maxY - minY;
            long cells = height * 256L;
            if (height <= 0 || cells > CELL_BUDGET || cells > Integer.MAX_VALUE) {
               return stopped(WriteStatus.NO_WRITE_INVALID, "chunk batch exceeds whole-chunk snapshot cell budget");
            }
            cellCount = Math.toIntExact(cells);
            baseX = target.getPos().getMinBlockX();
            baseZ = target.getPos().getMinBlockZ();
            TellusGenerationContext generation = entries.get(0).generation();
            Set<CanonicalColumnPosition> positions = new HashSet<>();
            boolean nonBlocking = false;
            // No source callback until every request passes structural/ownership/profile checks.
            for (Request entry : entries) {
               if (!entry.context().generation().equals(entry.generation())
                  || !entry.context().targetCoordinate().equals(entry.coordinate())
                  || !entry.request().coordinate().equals(entry.coordinate())
                  || !entry.request().geometry().equals(entry.generation().geometry())
                  || entry.request().cacheKey().worldSeed() != entry.generation().worldSeed()
                  || !generation.equals(entry.generation()) || entry.context().apronMirror()
                  || entry.generation().minY() != minY || entry.generation().maxYExclusive() != maxY) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "batch request identity/profile/ownership differs");
               }
               CanonicalColumnPosition position = model == null
                  ? CanonicalColumnPosition.resolve(entry.context()) : CanonicalColumnPosition.resolve(entry.context(), model);
               if (Math.floorDiv(position.x(),16) != target.getPos().x
                  || Math.floorDiv(position.z(),16) != target.getPos().z || !positions.add(position)) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "batch target does not uniquely own each physical column");
               }
               nonBlocking |= entry.request().nonBlocking();
            }
            if (nonBlocking) return stopped(WriteStatus.NO_WRITE_QUEUED, "nonblocking batch remains plan/source cold");
            for (Request entry : entries) {
               lease.requireOwned(target);
               TellusColumnPlan plan = Objects.requireNonNull(planner.apply(entry), "column plan");
               if (!plan.sample().request().equals(entry.request())) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "batch plan sample request differs from entry");
               }
               if (plan.status() == TellusColumnPlan.Status.QUEUED || plan.sample().status() == SampleStatus.QUEUED) {
                  return stopped(WriteStatus.NO_WRITE_QUEUED, "batch contains a queued plan");
               }
               if (plan.status() == TellusColumnPlan.Status.UNKNOWN || plan.sample().status() == SampleStatus.UNKNOWN) {
                  return stopped(WriteStatus.NO_WRITE_UNKNOWN, "batch contains an unknown plan");
               }
               if (plan.status() == TellusColumnPlan.Status.PARTIAL || !plan.sample().complete()) {
                  return stopped(WriteStatus.NO_WRITE_PARTIAL, "batch requires complete plans and samples");
               }
               if (plan.status() != TellusColumnPlan.Status.AVAILABLE || plan.decision().isEmpty()) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "batch requires available column decisions");
               }
               plans.add(plan);
            }
         } catch (RuntimeException error) {
            return invalid("batch preflight/planning failed before writes", error);
         }

         TellusNativePostProcessing markers = new TellusNativePostProcessing(proto);
         final BlockState[] original = new BlockState[cellCount];
         final Object markerSnapshot;
         try {
            lease.requireOwned(target);
            for (int index=0;index<original.length;index++) {
               original[index] = Objects.requireNonNull(target.getBlockState(position(index,minY,baseX,baseZ)), "original block state");
            }
            markerSnapshot = Objects.requireNonNull(markers.snapshot(), "marker snapshot");
         } catch (RuntimeException error) {
            // Capturing a partial read-only snapshot does not justify any restorative writes.
            return invalid("whole-chunk snapshot failed before writes", error);
         }

         TellusCompositeColumnWriter.Target<BlockState> storage = new TellusCompositeColumnWriter.Target<>() {
            public int chunkX() { return target.getPos().x; }
            public int chunkZ() { return target.getPos().z; }
            public int minY() { return minY; }
            public int maxYExclusive() { return maxY; }
            public BlockState read(TellusCompositeColumnWriter.Cell cell) { return target.getBlockState(block(cell)); }
            public void write(TellusCompositeColumnWriter.Cell cell, BlockState state) { target.setBlockState(block(cell),state,false); }
            public boolean isAir(BlockState state) { return state.isAir(); }
            public void markDirty() { target.setUnsaved(true); }
            public TellusCompositeColumnWriter.PostProcessing postProcessing() { return new TellusNativePostProcessing(proto); }
         };
         TellusCompositeColumnWriter<BlockState> writer = new TellusCompositeColumnWriter<>(materializer,model);
         List<WriteResult<BlockState>> results = new ArrayList<>(entries.size());
         WriteStatus failedStatus = null;
         Throwable failedCause = null;
         String failedDiagnostic = null;
         try {
            for (int row=0;row<entries.size();row++) {
               lease.requireOwned(target);
               Request entry = entries.get(row);
               TellusColumnPlan plan = plans.get(row);
               WriteResult<BlockState> result = writer.write(storage,entry.context(),plan.decision().orElseThrow(),
                  plan.featurePlan().orElseGet(() -> FeaturePlan.empty("no explicit batch feature plan")),mapper);
               if (result.status() != WriteStatus.WRITTEN) {
                  failedStatus = result.status() == WriteStatus.PARTIAL ? WriteStatus.NO_WRITE_PARTIAL : result.status();
                  failedDiagnostic = "column " + row + ": " + result.diagnostics();
                  failedCause = new IllegalStateException("column batch write stopped: " + result.diagnostics());
                  break;
               }
               results.add(result);
            }
         } catch (RuntimeException | Error error) {
            failedStatus = WriteStatus.NO_WRITE_INVALID;
            failedCause = error;
            failedDiagnostic = "write exception: " + error.getClass().getSimpleName();
         }
         if (failedCause != null) {
            restore(target,markers,original,markerSnapshot,minY,baseX,baseZ,failedCause);
            if (failedCause instanceof Error error) throw error;
            return stopped(failedStatus,"entire chunk batch restored after " + failedDiagnostic);
         }
          return new Result(WriteStatus.WRITTEN,results,List.of("participating chunk lease held through all plans, writes and marker effects"));
   }

   private static void restore(ChunkAccess target, TellusNativePostProcessing markers, BlockState[] original,
                               Object markerSnapshot, int minY, int baseX, int baseZ, Throwable cause) {
      Throwable failures = null;
      for (int index=original.length-1;index>=0;index--) {
         try {
            BlockPos position = position(index,minY,baseX,baseZ);
            if (!original[index].equals(target.getBlockState(position))) target.setBlockState(position,original[index],false);
            if (!original[index].equals(target.getBlockState(position))) throw new IllegalStateException("batch block rollback readback differs");
         } catch (RuntimeException | Error failed) { failures = aggregate(failures,failed); }
      }
      try { markers.restore(markerSnapshot); }
      catch (RuntimeException | Error failed) { failures = aggregate(failures,failed); }
      if (failures != null) {
         IllegalStateException incomplete = new IllegalStateException("chunk batch rollback incomplete",cause);
         incomplete.addSuppressed(failures);
         throw incomplete;
      }
   }

   private static Throwable aggregate(Throwable current, Throwable next) {
      if (current == null) return next;
      if (current != next && java.util.Arrays.stream(current.getSuppressed()).noneMatch(value -> value == next)) current.addSuppressed(next);
      return current;
   }
   private static BlockPos position(int index, int minY, int baseX, int baseZ) {
      return new BlockPos(baseX+(index&15),minY+(index>>>8),baseZ+((index>>>4)&15));
   }
   private static BlockPos block(TellusCompositeColumnWriter.Cell cell) { return new BlockPos(cell.x(),cell.y(),cell.z()); }
   private static Result invalid(String diagnostic, RuntimeException error) {
      return stopped(WriteStatus.NO_WRITE_INVALID, diagnostic + ": " + error.getClass().getSimpleName());
   }
   private static Result stopped(WriteStatus status, String diagnostic) { return new Result(status,List.of(),List.of(diagnostic)); }
}
