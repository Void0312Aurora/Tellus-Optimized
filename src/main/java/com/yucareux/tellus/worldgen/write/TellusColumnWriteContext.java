package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.TellusColumnDecisionEngine;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable target context for semantic column materialization.
 *
 * <p>The context carries ownership and the canonical source for an apron
 * mirror, but no engine storage object. An apron target is valid only when it
 * explicitly names a canonical Cube-Net source coordinate.</p>
 */
public record TellusColumnWriteContext(
   TellusCoordinateContext targetCoordinate,
   Optional<TellusCoordinateContext> canonicalSourceCoordinate,
   TellusGenerationContext generation,
   String writerId
) {
   public TellusColumnWriteContext {
      Objects.requireNonNull(targetCoordinate, "targetCoordinate");
      canonicalSourceCoordinate = Objects.requireNonNull(canonicalSourceCoordinate, "canonicalSourceCoordinate");
      Objects.requireNonNull(generation, "generation");
      writerId = requireText(writerId, "writerId");
      GeneratorOwner targetOwner = targetCoordinate.owner().kind();
      if (targetOwner == GeneratorOwner.CUBE_NET_APRON) {
         TellusCoordinateContext source = canonicalSourceCoordinate.orElseThrow(
            () -> new IllegalArgumentException("Cube-Net apron writes require a canonical source"));
         if (source.owner().kind() != GeneratorOwner.CUBE_NET_CANONICAL) {
            throw new IllegalArgumentException("apron canonical source must be CUBE_NET_CANONICAL");
         }
      } else if (canonicalSourceCoordinate.isPresent()) {
         throw new IllegalArgumentException("non-apron writes cannot carry an apron source");
      }
   }

   public static TellusColumnWriteContext canonical(
      TellusCoordinateContext coordinate, TellusGenerationContext generation, String writerId
   ) {
      return new TellusColumnWriteContext(coordinate, Optional.empty(), generation, writerId);
   }

   public static TellusColumnWriteContext apronMirror(
      TellusCoordinateContext apronCoordinate,
      TellusCoordinateContext canonicalCoordinate,
      TellusGenerationContext generation,
      String writerId
   ) {
      return new TellusColumnWriteContext(
         apronCoordinate, Optional.of(canonicalCoordinate), generation, writerId);
   }

   public boolean apronMirror() {
      return this.targetCoordinate.owner().kind() == GeneratorOwner.CUBE_NET_APRON;
   }

   public GeneratorOwner targetOwner() {
      return this.targetCoordinate.owner().kind();
   }

   /**
    * Decisions for an apron must be produced from the canonical coordinate;
    * the target coordinate is only the derived mirror destination.
    */
   public boolean acceptsDecision(TellusCoordinateContext decisionCoordinate) {
      Objects.requireNonNull(decisionCoordinate, "decisionCoordinate");
      return this.apronMirror()
         ? this.canonicalSourceCoordinate.orElseThrow().equals(decisionCoordinate)
         : this.targetCoordinate.equals(decisionCoordinate);
   }

   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }
}
