package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure semantic write request. It names ownership and the canonical source for
 * an apron mirror; it deliberately contains no Minecraft storage type.
 */
public record WriteContext(
   TellusCoordinateContext targetCoordinate,
   Optional<TellusCoordinateContext> canonicalSourceCoordinate,
   TellusGenerationContext generation,
   String writerId,
   PartialWritePolicy partialWritePolicy
) {
   public WriteContext {
      Objects.requireNonNull(targetCoordinate, "targetCoordinate");
      canonicalSourceCoordinate = Objects.requireNonNull(canonicalSourceCoordinate, "canonicalSourceCoordinate");
      Objects.requireNonNull(generation, "generation");
      writerId = requireText(writerId, "writerId");
      Objects.requireNonNull(partialWritePolicy, "partialWritePolicy");
      GeneratorOwner owner = targetCoordinate.owner().kind();
      if (owner == GeneratorOwner.CUBE_NET_APRON) {
         TellusCoordinateContext source = canonicalSourceCoordinate.orElseThrow(
            () -> new IllegalArgumentException("Cube-Net apron requires canonical source"));
         if (source.owner().kind() != GeneratorOwner.CUBE_NET_CANONICAL) {
            throw new IllegalArgumentException("apron source must be canonical Cube-Net owner");
         }
      } else if (canonicalSourceCoordinate.isPresent()) {
         throw new IllegalArgumentException("canonical source is only valid for apron mirrors");
      }
   }

   public WriteContext(
      TellusCoordinateContext targetCoordinate,
      Optional<TellusCoordinateContext> canonicalSourceCoordinate,
      TellusGenerationContext generation,
      String writerId
   ) {
      this(targetCoordinate, canonicalSourceCoordinate, generation, writerId,
         PartialWritePolicy.KNOWN_LAYERS_ONLY);
   }

   public static WriteContext canonical(
      TellusCoordinateContext coordinate, TellusGenerationContext generation, String writerId
   ) {
      return canonical(coordinate, generation, writerId, PartialWritePolicy.KNOWN_LAYERS_ONLY);
   }

   public static WriteContext canonical(
      TellusCoordinateContext coordinate, TellusGenerationContext generation,
      String writerId, PartialWritePolicy partialWritePolicy
   ) {
      return new WriteContext(coordinate, Optional.empty(), generation, writerId, partialWritePolicy);
   }

   public static WriteContext apronMirror(
      TellusCoordinateContext apron,
      TellusCoordinateContext canonical,
      TellusGenerationContext generation,
      String writerId
   ) {
      return apronMirror(apron, canonical, generation, writerId, PartialWritePolicy.KNOWN_LAYERS_ONLY);
   }

   public static WriteContext apronMirror(
      TellusCoordinateContext apron,
      TellusCoordinateContext canonical,
      TellusGenerationContext generation,
      String writerId,
      PartialWritePolicy partialWritePolicy
   ) {
      return new WriteContext(apron, Optional.of(canonical), generation, writerId, partialWritePolicy);
   }

   public CanonicalPersistenceKey canonicalPersistenceKey() {
      TellusCoordinateContext canonical = this.apronMirror()
         ? this.canonicalSourceCoordinate.orElseThrow()
         : this.targetCoordinate;
      return CanonicalPersistenceKey.from(this.generation.geometry(), canonical);
   }

   public Optional<SeamProjectionIdentity> seamProjectionIdentity() {
      if (!this.apronMirror()) return Optional.empty();
      CubeNetApronOwnerView apron = (CubeNetApronOwnerView)this.targetCoordinate.owner();
      var projection = apron.chunkProjection();
      return Optional.of(SeamProjectionIdentity.of(
         "cube-net-apron", apron.proxyPoint().x(), apron.proxyPoint().z(),
         projection.targetFace().index(), projection.targetChunkX(), projection.targetChunkZ()
      ));
   }

   public boolean apronMirror() {
      return this.targetCoordinate.owner().kind() == GeneratorOwner.CUBE_NET_APRON;
   }

   public GeneratorOwner targetOwner() {
      return this.targetCoordinate.owner().kind();
   }

   /** Decisions for an apron mirror must come from its canonical owner. */
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
