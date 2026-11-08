package com.yucareux.tellus.worldgen.feature;

import java.util.List;
import java.util.Objects;

/** Generic semantic feature sink result; no Minecraft storage types. */
public record FeaturePlacementResult<M>(
   FeaturePlacementStatus status,
   List<M> mappedWrites,
   List<String> diagnostics
) {
   public FeaturePlacementResult {
      Objects.requireNonNull(status, "status");
      mappedWrites = List.copyOf(Objects.requireNonNull(mappedWrites, "mappedWrites"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      if (status.noWrite() && !mappedWrites.isEmpty()) {
         throw new IllegalArgumentException("no-write placement result cannot contain mapped writes");
      }
   }

   public static <M> FeaturePlacementResult<M> noWrite(
      FeaturePlacementStatus status, String diagnostic
   ) {
      if (status == FeaturePlacementStatus.WRITTEN) throw new IllegalArgumentException("status");
      return new FeaturePlacementResult<>(status, List.of(), List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
   }
}
