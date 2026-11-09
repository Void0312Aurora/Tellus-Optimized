package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.TellusColumnDecisionEngine;
import com.yucareux.tellus.worldgen.decision.TellusColumnPipeline;
import com.yucareux.tellus.worldgen.decision.TellusColumnPlan;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvider;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Opt-in 1.20.1 adapter: shared plan-only pipeline -> semantic materializer ->
 * canonical column writer. No registration is performed here.
 */
public final class TellusMinecraft1201CanonicalPipeline {
   private final boolean enabled;
   private final TellusColumnPipeline columnPipeline;
   private final TellusColumnMaterializer materializer;
   private final SemanticMapper<BlockState> mapper;

   public TellusMinecraft1201CanonicalPipeline(
      boolean enabled,
      TellusSampleProvider sampleProvider,
      TellusColumnDecisionEngine decisionEngine,
      TellusFeaturePlanner featurePlanner,
      TellusColumnMaterializer materializer,
      SemanticMapper<BlockState> mapper
   ) {
      this(enabled, TellusColumnPipeline.compose(sampleProvider, decisionEngine, featurePlanner),
         materializer, mapper);
   }

   /** Explicit opt-in factory; the legacy constructor remains classifier-free. */
   public static TellusMinecraft1201CanonicalPipeline withEarthBiomeClassifier(
      boolean enabled,
      TellusSampleProvider sampleProvider,
      java.util.function.Function<com.yucareux.tellus.worldgen.sample.TellusSample, String> koppenResolver,
      TellusFeaturePlanner featurePlanner,
      TellusColumnMaterializer materializer,
      SemanticMapper<BlockState> mapper
   ) {
      return new TellusMinecraft1201CanonicalPipeline(enabled,
         TellusColumnPipeline.compose(sampleProvider,
            TellusColumnDecisionEngine.withEarthBiomeClassifier(), featurePlanner, koppenResolver),
         materializer, mapper);
   }
       /** Explicit opt-in factory using only TellusSample.climate when available. */
    public static TellusMinecraft1201CanonicalPipeline withEarthBiomeClassifier(
       boolean enabled,
       TellusSampleProvider sampleProvider,
       TellusFeaturePlanner featurePlanner,
       TellusColumnMaterializer materializer,
       SemanticMapper<BlockState> mapper
    ) {
       return new TellusMinecraft1201CanonicalPipeline(enabled,
          TellusColumnPipeline.composeWithSampleClimate(sampleProvider,
             TellusColumnDecisionEngine.withEarthBiomeClassifier(), featurePlanner),
          materializer, mapper);
    }

    /** Explicit sample-climate factory with an injected Earth resolver. */
    public static TellusMinecraft1201CanonicalPipeline withEarthBiomeClassifier(
       boolean enabled,
       TellusSampleProvider sampleProvider,
       com.yucareux.tellus.worldgen.decision.TellusEarthBiomeDecisionKernel.Resolver earthResolver,
       TellusFeaturePlanner featurePlanner,
       TellusColumnMaterializer materializer,
       SemanticMapper<BlockState> mapper
    ) {
       return new TellusMinecraft1201CanonicalPipeline(enabled,
          TellusColumnPipeline.composeWithSampleClimate(sampleProvider,
             new TellusColumnDecisionEngine(new com.yucareux.tellus.worldgen.decision.TellusEarthBiomeDecisionKernel(earthResolver)),
             featurePlanner),
          materializer, mapper);
    }

/** Explicit test/integration factory with an injected registry-free Earth resolver. */
   public static TellusMinecraft1201CanonicalPipeline withEarthBiomeClassifier(
      boolean enabled,
      TellusSampleProvider sampleProvider,
      java.util.function.Function<com.yucareux.tellus.worldgen.sample.TellusSample, String> koppenResolver,
      com.yucareux.tellus.worldgen.decision.TellusEarthBiomeDecisionKernel.Resolver earthResolver,
      TellusFeaturePlanner featurePlanner,
      TellusColumnMaterializer materializer,
      SemanticMapper<BlockState> mapper
   ) {
      return new TellusMinecraft1201CanonicalPipeline(enabled,
         TellusColumnPipeline.compose(sampleProvider,
            new TellusColumnDecisionEngine(new com.yucareux.tellus.worldgen.decision.TellusEarthBiomeDecisionKernel(earthResolver)),
            featurePlanner, koppenResolver),
         materializer, mapper);
   }
   public TellusMinecraft1201CanonicalPipeline(
      boolean enabled,
      TellusColumnPipeline columnPipeline,
      TellusColumnMaterializer materializer,
      SemanticMapper<BlockState> mapper
   ) {
      this.enabled = enabled;
      this.columnPipeline = Objects.requireNonNull(columnPipeline, "columnPipeline");
      this.materializer = Objects.requireNonNull(materializer, "materializer");
      this.mapper = Objects.requireNonNull(mapper, "mapper");
   }

   public boolean enabled() {
      return this.enabled;
   }


   public TellusColumnPlan plan(
      TellusGenerationContext generation,
      TellusCoordinateContext coordinate,
      TellusSampleRequest request
   ) {
      return Objects.requireNonNull(this.columnPipeline.plan(generation, coordinate, request), "columnPlan");
   }

   public WriteResult<BlockState> write(
      ChunkAccess target,
      WriteContext context,
      TellusGenerationContext generation,
      TellusCoordinateContext coordinate,
      TellusSampleRequest request
   ) {
      if (!this.enabled) return WriteResult.invalid("mc1201 canonical pipeline is disabled");
      if (target == null) return writeExclusive(target, context, generation, coordinate, request, null);
      var acquired = TellusTargetWriteGuard.tryAcquire(target);
      if (acquired.isEmpty()) return WriteResult.queued("target chunk already has a participating writer");
      try (var lease = acquired.orElseThrow()) {
         return writeExclusive(target, context, generation, coordinate, request, lease);
      }
   }

   public TellusNativeChunkBatch.Result writeChunk(ChunkAccess target, java.util.List<TellusNativeChunkBatch.Request> requests) {
      return new TellusNativeChunkBatch(this.materializer, this.mapper).write(this.enabled, target, requests,
         request -> this.plan(request.generation(), request.coordinate(), request.request()));
   }

   private WriteResult<BlockState> writeExclusive(
      ChunkAccess target,
      WriteContext context,
      TellusGenerationContext generation,
      TellusCoordinateContext coordinate,
      TellusSampleRequest request,
      TellusTargetWriteGuard.Lease lease
   ) {
      if (!this.enabled) {
         return WriteResult.invalid("mc1201 canonical pipeline is disabled");
      }
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(generation, "generation");
      Objects.requireNonNull(coordinate, "coordinate");
      Objects.requireNonNull(request, "request");
      if (!context.generation().equals(generation)) {
         return WriteResult.invalid("pipeline generation does not match write context");
      }
      if (context.apronMirror() || context.targetOwner() == GeneratorOwner.CUBE_NET_APRON) {
         return WriteResult.invalid("canonical pipeline does not write apron targets");
      }
      if (!context.targetCoordinate().equals(coordinate)
         || !request.coordinate().equals(coordinate)) {
         return WriteResult.invalid("pipeline coordinate does not match write context");
      }

      TellusColumnPlan plan = Objects.requireNonNull(
         this.columnPipeline.plan(generation, coordinate, request), "columnPlan");
      if (plan.status() == TellusColumnPlan.Status.UNKNOWN) {
         return WriteResult.unknown(diagnostic(plan, "column plan is UNKNOWN; target remains untouched"));
      }
      if (plan.status() == TellusColumnPlan.Status.QUEUED) {
         return WriteResult.queued(diagnostic(plan, "column plan is QUEUED; target remains untouched"));
      }

      ColumnDecision decision = plan.decision().orElseThrow(
         () -> new IllegalStateException("known column plan has no decision"));
      var features = plan.featurePlan().orElseGet(() ->
         com.yucareux.tellus.worldgen.feature.FeaturePlan.empty("no feature plan supplied"));
      var writer = new TellusMinecraft1201CompositeColumnWriter(this.materializer);
      return lease == null ? writer.write(target, context, decision, features, this.mapper)
         : writer.writeOwned(target, context, decision, features, this.mapper, lease);
   }

   private static String diagnostic(TellusColumnPlan plan, String fallback) {
      return plan.diagnostics().isEmpty() ? fallback : plan.diagnostics().get(0);
   }
}