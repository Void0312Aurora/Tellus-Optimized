package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.runtime.TellusGeneratorRuntime.AttachResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Ordered, identity-based runtime registry. It has no loader lifecycle hooks
 * and never creates a default adapter.
 *
 * <p>State transitions are serialized, but runtime callbacks run outside the
 * monitor. A per-generator admission prevents a callback from recursively
 * mutating the same generator. A callback may register a distinct successor;
 * operations remove only the exact entries admitted at their start.</p>
 */
public final class TellusGeneratorRuntimeRegistry {
   public enum RegistrationStatus {
      REGISTERED, DUPLICATE_IDENTITY, INVALID_OWNERSHIP, FACTORY_NOT_FOUND, FACTORY_AMBIGUOUS
   }
   public enum ResolutionStatus { FOUND, NOT_FOUND, DUPLICATE, AMBIGUOUS }

   public record RegistrationResult(
      RegistrationStatus status, TellusGeneratorRuntime runtime, String diagnostic
   ) {
      public RegistrationResult {
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(diagnostic, "diagnostic");
      }
      public boolean registered() { return status == RegistrationStatus.REGISTERED; }
   }

   /** Explicit rollback token for a registration attempt; construction has no implicit rollback policy. */
   public static final class RegistrationRollback implements AutoCloseable {
      private final TellusGeneratorRuntimeRegistry registry;
      private final TellusGeneratorRuntime runtime;
      private final RegistrationResult result;
      private final Entry registeredEntry;
      private boolean rolledBack;
      private boolean rollbackInProgress;

      private RegistrationRollback(TellusGeneratorRuntimeRegistry registry,
                                   TellusGeneratorRuntime runtime,
                                   RegistrationResult result, Entry registeredEntry) {
         this.registry = registry;
         this.runtime = runtime;
         this.result = result;
         this.registeredEntry = registeredEntry;
      }

      public RegistrationResult result() { return result; }
      public boolean registered() { return result.registered(); }

      /** Idempotently detaches and removes only this exact generator/runtime pair. */
      public boolean rollback() {
         synchronized (this) {
            if (rolledBack || !result.registered() || rollbackInProgress) return false;
            rollbackInProgress = true;
         }
         Mutation mutation = registry.beginRollback(registeredEntry);
         if (mutation == null) {
            boolean stale = registry.rollbackTokenStale(registeredEntry);
            synchronized (this) {
               if (stale) rolledBack = true;
               rollbackInProgress = false;
            }
            return false;
         }
         try {
            // No registry or token monitor is held while user runtime code runs.
            // RuntimeException/Error are rethrown unchanged and leave this
            // exact token live for a later retry.
            runtime.detach();
            synchronized (registry) {
               registry.rollbackTokens.remove(registeredEntry);
               registry.releaseAdmissionLocked(registeredEntry);
            }
            synchronized (this) {
               rolledBack = true;
               rollbackInProgress = false;
            }
            return true;
         } catch (RuntimeException | Error failure) {
            synchronized (registry) {
               registry.restoreLocked(mutation);
               registry.releaseAdmissionLocked(registeredEntry);
            }
            synchronized (this) { rollbackInProgress = false; }
            throw failure;
         }
      }

      @Override public void close() { rollback(); }
   }

   public record Resolution(
      ResolutionStatus status,
      Optional<TellusGeneratorRuntime> runtime,
      int candidates,
      String diagnostic
   ) {
      public Resolution {
         Objects.requireNonNull(status, "status");
         runtime = Objects.requireNonNull(runtime, "runtime");
         if (candidates < 0) throw new IllegalArgumentException("candidates");
         Objects.requireNonNull(diagnostic, "diagnostic");
         if (status == ResolutionStatus.FOUND && runtime.isEmpty()) {
            throw new IllegalArgumentException("FOUND requires runtime");
         }
         if (status != ResolutionStatus.FOUND && runtime.isPresent()) {
            throw new IllegalArgumentException("non-FOUND resolution cannot contain runtime");
         }
      }
      public boolean found() { return status == ResolutionStatus.FOUND; }
   }

   public record AttachResolution(Resolution resolution, Optional<AttachResult> attachResult) {
      public AttachResolution {
         Objects.requireNonNull(resolution, "resolution");
         attachResult = Objects.requireNonNull(attachResult, "attachResult");
         if (!resolution.found() && attachResult.isPresent()) {
            throw new IllegalArgumentException("unresolved runtime cannot have attach result");
         }
      }
   }

   private record Entry(ChunkGenerator generator, TellusGeneratorRuntime runtime, long ordinal) { }
   private record Mutation(Entry entry, int originalIndex, boolean removed) { }

   private final List<Entry> entries = new ArrayList<>();
   private long nextOrdinal;
   /** Identity-keyed active mutation admission. */
   private final IdentityHashMap<ChunkGenerator, Entry> admissions = new IdentityHashMap<>();
   /** Entries with a live caller-owned rollback token. */
   private final IdentityHashMap<Entry, Boolean> rollbackTokens = new IdentityHashMap<>();

   public RegistrationResult register(ChunkGenerator generator, TellusGeneratorRuntime runtime) {
      Objects.requireNonNull(generator, "generator");
      Objects.requireNonNull(runtime, "runtime");
      // Ownership is user code and intentionally runs outside the monitor.
      if (!runtime.owns(generator)) {
         return new RegistrationResult(RegistrationStatus.INVALID_OWNERSHIP, runtime,
            "runtime does not own the exact generator instance");
      }
      synchronized (this) {
         if (admissions.containsKey(generator) || findByGeneratorLocked(generator) != null) {
            return new RegistrationResult(RegistrationStatus.DUPLICATE_IDENTITY, runtime,
               admissions.containsKey(generator)
                  ? "generator mutation is already in progress"
                  : "generator identity is already registered");
         }
         entries.add(newEntryLocked(generator, runtime));
         return new RegistrationResult(RegistrationStatus.REGISTERED, runtime, "registered in order");
      }
   }

   /** Registers exactly once and returns a caller-owned rollback token. */
   public RegistrationRollback registerWithRollback(ChunkGenerator generator, TellusGeneratorRuntime runtime) {
      Objects.requireNonNull(generator, "generator");
      Objects.requireNonNull(runtime, "runtime");
      RegistrationResult result;
      Entry registeredEntry = null;
      if (!runtime.owns(generator)) {
         result = new RegistrationResult(RegistrationStatus.INVALID_OWNERSHIP, runtime,
            "runtime does not own the exact generator instance");
      } else {
         synchronized (this) {
            if (admissions.containsKey(generator) || findByGeneratorLocked(generator) != null) {
               result = new RegistrationResult(RegistrationStatus.DUPLICATE_IDENTITY, runtime,
                  admissions.containsKey(generator)
                     ? "generator mutation is already in progress"
                     : "generator identity is already registered");
            } else {
               registeredEntry = newEntryLocked(generator, runtime);
               entries.add(registeredEntry);
               rollbackTokens.put(registeredEntry, Boolean.TRUE);
               result = new RegistrationResult(RegistrationStatus.REGISTERED, runtime, "registered in order");
            }
         }
      }
      return new RegistrationRollback(this, runtime, result, registeredEntry);
   }

   /** Resolves by exact registered identity first; ownership probing is a fallback for diagnostics. */
   public Resolution resolve(ChunkGenerator generator) {
      Objects.requireNonNull(generator, "generator");
      List<Entry> snapshot;
      synchronized (this) {
         if (admissions.containsKey(generator)) return notFound("generator mutation is already in progress");
         snapshot = List.copyOf(entries);
      }

      List<Entry> identityMatches = snapshot.stream()
         .filter(entry -> entry.generator() == generator).toList();
      if (identityMatches.size() > 1) {
         return new Resolution(ResolutionStatus.DUPLICATE, Optional.empty(), identityMatches.size(),
            "multiple registrations share the exact generator identity");
      }
      if (identityMatches.size() == 1) {
         Entry entry = identityMatches.get(0);
         if (!entry.runtime().owns(generator)) {
            return notFound("registered runtime no longer owns the exact generator");
         }
         synchronized (this) {
            if (!containsExactLocked(entry) || admissions.containsKey(generator)) {
               return notFound("registration changed during resolution");
            }
         }
         return new Resolution(ResolutionStatus.FOUND, Optional.of(entry.runtime()), 1,
            "exact identity match");
      }

      List<Entry> ownershipMatches = snapshot.stream()
         .filter(entry -> entry.runtime().owns(generator)).toList();
      synchronized (this) {
         ownershipMatches = ownershipMatches.stream().filter(this::containsExactLocked).toList();
      }
      if (ownershipMatches.isEmpty()) {
         return notFound("no ordered adapter owns the exact generator identity");
      }
      if (ownershipMatches.size() > 1) {
         return new Resolution(ResolutionStatus.AMBIGUOUS, Optional.empty(), ownershipMatches.size(),
            "multiple ordered adapters claim the generator");
      }
      return notFound("adapter ownership claim has no exact identity registration");
   }

   public AttachResolution attach(ChunkGenerator generator) {
      Resolution resolution = resolve(generator);
      if (!resolution.found()) return new AttachResolution(resolution, Optional.empty());
      Entry entry = exactEntry(generator, resolution.runtime().orElseThrow());
      Mutation mutation = beginMutation(entry, false);
      if (mutation == null) {
         return new AttachResolution(notFound("generator mutation is already in progress"), Optional.empty());
      }
      try {
         AttachResult result = entry.runtime().attach(generator);
         return new AttachResolution(resolution, Optional.of(result));
      } finally {
         releaseAdmission(entry);
      }
   }

   public Resolution detach(ChunkGenerator generator) {
      return detachExact(generator, false);
   }

   /** Detaches and removes the exact registered generator identity. */
   public Resolution unregister(ChunkGenerator generator) {
      return detachExact(generator, true);
   }

   /** Detaches in reverse registration order and removes all registrations admitted at method entry. */
   public int unregisterAll() {
      return batchDetach(true);
   }

   /** Detaches in reverse registration order; no external lifecycle is started. */
   public void detachAll() {
      batchDetach(false);
   }

   /** Dispatches only to already attached adapters when explicitly requested. */
   public int tickAttached() {
      List<Entry> snapshot;
      synchronized (this) { snapshot = List.copyOf(entries); }
      int ticked = 0;
      for (Entry entry : snapshot) {
         Mutation mutation = beginMutation(entry, false);
         if (mutation == null) continue;
         try {
            if (entry.runtime().attached()) {
               entry.runtime().tick();
               ticked++;
            }
         } finally {
            releaseAdmission(entry);
         }
      }
      return ticked;
   }

   public List<TellusGeneratorRuntime> orderedAdapters() {
      synchronized (this) {
         List<TellusGeneratorRuntime> result = new ArrayList<>(this.entries.size());
         for (Entry entry : this.entries) result.add(entry.runtime());
         return Collections.unmodifiableList(result);
      }
   }

   public int size() {
      synchronized (this) { return this.entries.size(); }
   }

   private Resolution detachExact(ChunkGenerator generator, boolean remove) {
      Objects.requireNonNull(generator, "generator");
      Resolution resolution = resolve(generator);
      if (!resolution.found()) return resolution;
      Entry entry = exactEntry(generator, resolution.runtime().orElseThrow());
      Mutation mutation = beginMutation(entry, true);
      if (mutation == null) return notFound("generator mutation is already in progress");
      try {
         entry.runtime().detach();
         synchronized (this) {
            if (!remove) restoreLocked(mutation);
            else rollbackTokens.remove(entry);
            releaseAdmissionLocked(entry);
         }
         return resolution;
      } catch (RuntimeException | Error failure) {
         synchronized (this) {
            restoreLocked(mutation);
            releaseAdmissionLocked(entry);
         }
         throw failure;
      }
   }

   private int batchDetach(boolean remove) {
      List<Mutation> admitted = admitSnapshot();
      try {
         for (int index = admitted.size() - 1; index >= 0; index--) {
            admitted.get(index).entry().runtime().detach();
         }
         synchronized (this) {
            for (Mutation mutation : admitted) {
               if (!remove) restoreLocked(mutation);
               else rollbackTokens.remove(mutation.entry());
               releaseAdmissionLocked(mutation.entry());
            }
         }
         return admitted.size();
      } catch (RuntimeException | Error failure) {
         synchronized (this) {
            // Restore every exact entry so a failed batch remains retryable.
            for (Mutation mutation : admitted) {
               restoreLocked(mutation);
               releaseAdmissionLocked(mutation.entry());
            }
         }
         throw failure;
      }
   }

   private Entry exactEntry(ChunkGenerator generator, TellusGeneratorRuntime runtime) {
      synchronized (this) {
         for (Entry entry : entries) {
            if (entry.generator() == generator && entry.runtime() == runtime) return entry;
         }
         return null;
      }
   }

   private Entry findByGeneratorLocked(ChunkGenerator generator) {
      for (Entry entry : entries) if (entry.generator() == generator) return entry;
      return null;
   }

   private boolean containsExactLocked(Entry expected) {
      for (Entry entry : entries) if (entry == expected) return true;
      return false;
   }

   private boolean removeExactLocked(Entry expected) {
      for (int index = 0; index < entries.size(); index++) {
         if (entries.get(index) == expected) {
            entries.remove(index);
            return true;
         }
      }
      return false;
   }

   private Mutation beginMutation(Entry entry, boolean remove) {
      if (entry == null) return null;
      synchronized (this) {
         if (admissions.containsKey(entry.generator())) return null;
         int index = exactIndexLocked(entry);
         if (index < 0) return null;
         if (remove) entries.remove(index);
         admissions.put(entry.generator(), entry);
         return new Mutation(entry, index, remove);
      }
   }

   private Mutation beginRollback(Entry entry) {
      if (entry == null) return null;
      synchronized (this) {
         if (!rollbackTokens.containsKey(entry) || admissions.containsKey(entry.generator())) return null;
         int index = exactIndexLocked(entry);
         if (index < 0) return null; // stale token: never call its runtime.
         entries.remove(index);
         admissions.put(entry.generator(), entry);
         return new Mutation(entry, index, true);
      }
   }

   private List<Mutation> admitSnapshot() {
      synchronized (this) {
         List<Entry> snapshot = List.copyOf(entries);
         List<Mutation> result = new ArrayList<>();
         for (int originalIndex = 0; originalIndex < snapshot.size(); originalIndex++) {
            Entry entry = snapshot.get(originalIndex);
            if (!admissions.containsKey(entry.generator())) {
               int currentIndex = exactIndexLocked(entry);
               if (currentIndex >= 0) {
                  entries.remove(currentIndex);
                  admissions.put(entry.generator(), entry);
                  result.add(new Mutation(entry, originalIndex, true));
               }
            }
         }
         return result;
      }
   }

   private int exactIndexLocked(Entry expected) {
      for (int index = 0; index < entries.size(); index++) {
         if (entries.get(index) == expected) return index;
      }
      return -1;
   }

   private boolean rollbackTokenStale(Entry entry) {
      synchronized (this) {
         return entry == null || !rollbackTokens.containsKey(entry)
            || (!admissions.containsKey(entry.generator()) && !containsExactLocked(entry));
      }
   }

   private void releaseAdmission(Entry entry) {
      synchronized (this) { releaseAdmissionLocked(entry); }
   }

   private void releaseAdmissionLocked(Entry entry) {
      if (entry != null && admissions.get(entry.generator()) == entry) {
         admissions.remove(entry.generator());
      }
   }

   private Entry newEntryLocked(ChunkGenerator generator, TellusGeneratorRuntime runtime) {
      long ordinal = nextOrdinal;
      nextOrdinal = Math.incrementExact(nextOrdinal);
      return new Entry(generator, runtime, ordinal);
   }

   private void restoreLocked(Mutation mutation) {
      if (mutation == null || !mutation.removed() || containsExactLocked(mutation.entry())) return;
      int index = 0;
      while (index < entries.size() && entries.get(index).ordinal() < mutation.entry().ordinal()) index++;
      entries.add(index, mutation.entry());
   }

   private static Resolution notFound(String diagnostic) {
      return new Resolution(ResolutionStatus.NOT_FOUND, Optional.empty(), 0, diagnostic);
   }
}
