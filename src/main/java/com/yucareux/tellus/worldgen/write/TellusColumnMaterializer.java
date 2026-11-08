package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import java.util.Objects;

/**
 * Minimal pure materializer guard. It establishes the terminal status contract
 * before a concrete surface/strata expansion is added.
 */
public final class TellusColumnMaterializer {
   public WriteResult<SemanticMaterial> materialize(WriteContext context, ColumnDecision decision) {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(decision, "decision");
      TellusPersistenceIdentity identity;
      try {
         identity = TellusPersistenceIdentity.from(context.generation().geometry(), context.targetCoordinate());
      } catch (IllegalArgumentException error) {
         return WriteResult.invalid("persistence identity unavailable: " + error.getMessage());
      }
      if (!context.acceptsDecision(decision.coordinate())) {
         return WriteResult.<SemanticMaterial>invalid("decision coordinate is not the canonical write source")
            .withPersistenceIdentity(identity);
      }
      if (decision.status() == DecisionStatus.UNKNOWN) {
         return WriteResult.<SemanticMaterial>unknown("column decision is UNKNOWN; no semantic writes permitted")
            .withPersistenceIdentity(identity);
      }
      if (decision.status() == DecisionStatus.QUEUED) {
         return WriteResult.<SemanticMaterial>queued("column decision is QUEUED; no semantic writes permitted")
            .withPersistenceIdentity(identity);
      }
      if (decision.status() == DecisionStatus.PARTIAL
         && !context.partialWritePolicy().allowsKnownLayers()) {
         return WriteResult.<SemanticMaterial>partialRejected("PARTIAL policy requires a complete decision")
            .withPersistenceIdentity(identity);
      }
      // Expansion of surface/strata layers is intentionally deferred. A known
      // decision currently establishes a valid write boundary but emits no
      // target values until a mapper-specific materializer is selected.
      return new WriteResult<>(
         decision.status() == DecisionStatus.PARTIAL ? WriteStatus.PARTIAL : WriteStatus.WRITTEN,
         java.util.List.of(),
         java.util.List.of("semantic material expansion deferred"),
         java.util.Optional.of(identity)
      );
   }
}
