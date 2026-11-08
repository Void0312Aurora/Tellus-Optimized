package com.yucareux.tellus.worldgen.runtime;

import java.util.List;
import java.util.Objects;

/** Pure evidence gate for enabling the runtime rollout; it has no hooks or side effects. */
public final class TellusRuntimeRolloutGate {
   public enum Status { ENABLED, DISABLED }

   public record ParityEvidence(boolean fresh, boolean passed, String evidenceId) {
      public ParityEvidence {
         evidenceId = requireText(evidenceId, "evidenceId");
      }

      public boolean freshPass() {
         return this.fresh && this.passed;
      }

      private static String requireText(String value, String name) {
         Objects.requireNonNull(value, name);
         if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
         return value;
      }
   }

   public record Request(
      boolean explicitFlag,
      ParityEvidence columns,
      ParityEvidence biome,
      boolean storageReady
   ) {
      public Request {
         Objects.requireNonNull(columns, "columns");
         Objects.requireNonNull(biome, "biome");
      }
   }

   public record Result(Status status, List<String> diagnostics) {
      public Result {
         Objects.requireNonNull(status, "status");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (status == Status.ENABLED && !diagnostics.isEmpty()) {
            throw new IllegalArgumentException("enabled rollout cannot contain rejection diagnostics");
         }
         if (status == Status.DISABLED && diagnostics.isEmpty()) {
            throw new IllegalArgumentException("disabled rollout requires diagnostics");
         }
      }

      public boolean enabled() {
         return this.status == Status.ENABLED;
      }
   }

   private TellusRuntimeRolloutGate() { }

   /** Evaluates only explicit caller evidence; no default is enabled. */
   public static Result evaluate(Request request) {
      Objects.requireNonNull(request, "request");
      java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
      if (!request.explicitFlag()) reasons.add("explicit rollout flag is false");
      if (!request.columns().freshPass()) reasons.add("fresh columns parity pass is required");
      if (!request.biome().freshPass()) reasons.add("fresh biome parity pass is required");
      if (!request.storageReady()) reasons.add("storage readiness is required");
      return reasons.isEmpty()
         ? new Result(Status.ENABLED, List.of())
         : new Result(Status.DISABLED, reasons);
   }
}
