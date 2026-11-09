package com.yucareux.tellus.worldgen.write;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable semantic writer result; mapped payloads remain generic. */
public record WriteResult<M>(
   WriteStatus status,
   List<M> mappedWrites,
   List<String> diagnostics,
   Optional<TellusPersistenceIdentity> persistenceIdentity,
   boolean transientPostProcessing
) {
   public WriteResult(WriteStatus status, List<M> mappedWrites, List<String> diagnostics,
                      Optional<TellusPersistenceIdentity> persistenceIdentity) {
      this(status, mappedWrites, diagnostics, persistenceIdentity, false);
   }
   public WriteResult {
      Objects.requireNonNull(status, "status");
      mappedWrites = List.copyOf(Objects.requireNonNull(mappedWrites, "mappedWrites"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      persistenceIdentity = Objects.requireNonNull(persistenceIdentity, "persistenceIdentity");
      if (status.noWrite() && !mappedWrites.isEmpty()) {
         throw new IllegalArgumentException("no-write result cannot contain mapped writes");
      }
   }

   public WriteResult(WriteStatus status, List<M> mappedWrites, List<String> diagnostics) {
      this(status, mappedWrites, diagnostics, Optional.empty());
   }

   public static <M> WriteResult<M> unknown(String diagnostic) {
      return noWrite(WriteStatus.NO_WRITE_UNKNOWN, diagnostic);
   }

   public static <M> WriteResult<M> queued(String diagnostic) {
      return noWrite(WriteStatus.NO_WRITE_QUEUED, diagnostic);
   }

   public static <M> WriteResult<M> partialRejected(String diagnostic) {
      return noWrite(WriteStatus.NO_WRITE_PARTIAL, diagnostic);
   }

   public static <M> WriteResult<M> invalid(String diagnostic) {
      return noWrite(WriteStatus.NO_WRITE_INVALID, diagnostic);
   }

   public WriteResult<M> withPersistenceIdentity(TellusPersistenceIdentity identity) {
      return new WriteResult<>(this.status, this.mappedWrites, this.diagnostics, Optional.of(identity), this.transientPostProcessing);
   }

   private static <M> WriteResult<M> noWrite(WriteStatus status, String diagnostic) {
      return new WriteResult<>(status, List.of(), List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
   }
}
