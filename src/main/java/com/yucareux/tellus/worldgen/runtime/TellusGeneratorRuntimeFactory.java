package com.yucareux.tellus.worldgen.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Factory boundary for ordered runtime adapters; it performs no lifecycle work. */
public final class TellusGeneratorRuntimeFactory {
   @FunctionalInterface
   public interface AdapterFactory {
      Optional<TellusGeneratorRuntime> create(ChunkGenerator generator);
   }

   public enum Status { CREATED, NOT_FOUND, AMBIGUOUS, INVALID_OWNERSHIP }

   public record Result(
      Status status,
      Optional<TellusGeneratorRuntime> runtime,
      int candidates,
      String diagnostic
   ) {
      public Result {
         Objects.requireNonNull(status, "status");
         runtime = Objects.requireNonNull(runtime, "runtime");
         if (candidates < 0) throw new IllegalArgumentException("candidates");
         Objects.requireNonNull(diagnostic, "diagnostic");
         if (status == Status.CREATED && runtime.isEmpty()) {
            throw new IllegalArgumentException("CREATED requires runtime");
         }
         if (status != Status.CREATED && runtime.isPresent()) {
            throw new IllegalArgumentException("unresolved factory result cannot contain runtime");
         }
      }
   }

   private TellusGeneratorRuntimeFactory() { }

   public static TellusGeneratorRuntimeRegistry registry() {
      return new TellusGeneratorRuntimeRegistry();
   }

   /**
    * Evaluates factories in declaration order. Exactly one identity-owning
    * adapter is required; class-only or duplicate claims remain unresolved.
    */
   public static Result create(
      ChunkGenerator generator,
      List<? extends AdapterFactory> factories
   ) {
      Objects.requireNonNull(generator, "generator");
      Objects.requireNonNull(factories, "factories");
      List<TellusGeneratorRuntime> candidates = new ArrayList<>();
      for (AdapterFactory factory : factories) {
         Objects.requireNonNull(factory, "factory");
         Optional<TellusGeneratorRuntime> candidate = Objects.requireNonNull(
            factory.create(generator), "factory result");
         if (candidate.isPresent()) {
            TellusGeneratorRuntime runtime = candidate.get();
            if (runtime.owns(generator)) candidates.add(runtime);
            else return new Result(Status.INVALID_OWNERSHIP, Optional.empty(), candidates.size() + 1,
               "factory returned a runtime that does not own the exact generator");
         }
      }
      if (candidates.isEmpty()) {
         return new Result(Status.NOT_FOUND, Optional.empty(), 0, "no factory matched");
      }
      if (candidates.size() > 1) {
         return new Result(Status.AMBIGUOUS, Optional.empty(), candidates.size(),
            "more than one ordered factory owns the exact generator");
      }
      return new Result(Status.CREATED, Optional.of(candidates.get(0)), 1, "one identity-owned adapter created");
   }

   public static TellusGeneratorRuntimeRegistry.RegistrationResult createAndRegister(
      TellusGeneratorRuntimeRegistry registry,
      ChunkGenerator generator,
      List<? extends AdapterFactory> factories
   ) {
      Objects.requireNonNull(registry, "registry");
      Result result = create(generator, factories);
      if (result.status() != Status.CREATED) {
         TellusGeneratorRuntimeRegistry.RegistrationStatus registrationStatus = switch (result.status()) {
            case NOT_FOUND -> TellusGeneratorRuntimeRegistry.RegistrationStatus.FACTORY_NOT_FOUND;
            case AMBIGUOUS -> TellusGeneratorRuntimeRegistry.RegistrationStatus.FACTORY_AMBIGUOUS;
            case INVALID_OWNERSHIP -> TellusGeneratorRuntimeRegistry.RegistrationStatus.INVALID_OWNERSHIP;
            case CREATED -> throw new IllegalStateException("created result handled above");
         };
         return new TellusGeneratorRuntimeRegistry.RegistrationResult(
            registrationStatus, null, result.diagnostic());
      }
      return registry.register(generator, result.runtime().orElseThrow());
   }
}
