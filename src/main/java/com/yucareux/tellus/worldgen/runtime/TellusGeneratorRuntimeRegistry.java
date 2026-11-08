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

   private record Entry(ChunkGenerator generator, TellusGeneratorRuntime runtime) { }

   private final List<Entry> entries = new ArrayList<>();

   public RegistrationResult register(ChunkGenerator generator, TellusGeneratorRuntime runtime) {
      Objects.requireNonNull(generator, "generator");
      Objects.requireNonNull(runtime, "runtime");
      if (!runtime.owns(generator)) {
         return new RegistrationResult(RegistrationStatus.INVALID_OWNERSHIP, runtime,
            "runtime does not own the exact generator instance");
      }
      for (Entry entry : this.entries) {
         if (entry.generator() == generator) {
            return new RegistrationResult(RegistrationStatus.DUPLICATE_IDENTITY, runtime,
               "generator identity is already registered");
         }
      }
      this.entries.add(new Entry(generator, runtime));
      return new RegistrationResult(RegistrationStatus.REGISTERED, runtime, "registered in order");
   }

   /** Resolves by exact registered identity first; ownership probing is a fallback for diagnostics. */
   public Resolution resolve(ChunkGenerator generator) {
      Objects.requireNonNull(generator, "generator");
      List<Entry> identityMatches = this.entries.stream()
         .filter(entry -> entry.generator() == generator)
         .toList();
      if (identityMatches.size() > 1) {
         return new Resolution(ResolutionStatus.DUPLICATE, Optional.empty(), identityMatches.size(),
            "multiple registrations share the exact generator identity");
      }
      if (identityMatches.size() == 1) {
         Entry entry = identityMatches.get(0);
         if (!entry.runtime().owns(generator)) {
            return new Resolution(ResolutionStatus.NOT_FOUND, Optional.empty(), 0,
               "registered runtime no longer owns the exact generator");
         }
         return new Resolution(ResolutionStatus.FOUND, Optional.of(entry.runtime()), 1, "exact identity match");
      }

      List<Entry> ownershipMatches = this.entries.stream()
         .filter(entry -> entry.runtime().owns(generator))
         .toList();
      if (ownershipMatches.isEmpty()) {
         return new Resolution(ResolutionStatus.NOT_FOUND, Optional.empty(), 0,
            "no ordered adapter owns the exact generator identity");
      }
      if (ownershipMatches.size() > 1) {
         return new Resolution(ResolutionStatus.AMBIGUOUS, Optional.empty(), ownershipMatches.size(),
            "multiple ordered adapters claim the generator");
      }
      // A runtime that claims an unregistered instance is deliberately not
      // resolved: identity registration is the authority, not class matching.
      return new Resolution(ResolutionStatus.NOT_FOUND, Optional.empty(), 1,
         "adapter ownership claim has no exact identity registration");
   }

   public AttachResolution attach(ChunkGenerator generator) {
      Resolution resolution = resolve(generator);
      if (!resolution.found()) return new AttachResolution(resolution, Optional.empty());
      AttachResult result = resolution.runtime().orElseThrow().attach(generator);
      return new AttachResolution(resolution, Optional.of(result));
   }

   public Resolution detach(ChunkGenerator generator) {
      Resolution resolution = resolve(generator);
      if (resolution.found()) resolution.runtime().orElseThrow().detach();
      return resolution;
   }

   /** Detaches and removes the exact registered generator identity. */
   public Resolution unregister(ChunkGenerator generator) {
      Resolution resolution = resolve(generator);
      if (resolution.found()) {
         resolution.runtime().orElseThrow().detach();
         this.entries.removeIf(entry -> entry.generator() == generator);
      }
      return resolution;
   }

   /** Detaches in reverse registration order and removes all registrations. */
   public int unregisterAll() {
      int count = this.entries.size();
      for (int index = this.entries.size() - 1; index >= 0; index--) {
         this.entries.get(index).runtime().detach();
      }
      this.entries.clear();
      return count;
   }

   /** Detaches in reverse registration order; no external lifecycle is started. */
   public void detachAll() {
      for (int index = this.entries.size() - 1; index >= 0; index--) {
         this.entries.get(index).runtime().detach();
      }
   }

   /** Dispatches only to already attached adapters when explicitly requested. */
   public int tickAttached() {
      int ticked = 0;
      for (Entry entry : this.entries) {
         if (entry.runtime().attached()) {
            entry.runtime().tick();
            ticked++;
         }
      }
      return ticked;
   }

   public List<TellusGeneratorRuntime> orderedAdapters() {
      List<TellusGeneratorRuntime> result = new ArrayList<>(this.entries.size());
      for (Entry entry : this.entries) result.add(entry.runtime());
      return Collections.unmodifiableList(result);
   }

   public int size() {
      return this.entries.size();
   }
}
