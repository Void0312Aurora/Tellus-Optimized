package com.yucareux.tellus.worldgen.runtime;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Explicit mc1201 registration helper; caller owns the registry and timing. */
public final class TellusMinecraft1201RuntimeRegistration {
   private TellusMinecraft1201RuntimeRegistration() { }

   public static TellusGeneratorRuntimeRegistry.RegistrationResult register(
      TellusGeneratorRuntimeRegistry registry, ChunkGenerator generator
   ) {
      return register(registry, generator,
         TellusMinecraft1201RuntimeFactories.orderedAdapterFactories());
   }

   public static TellusGeneratorRuntimeRegistry.RegistrationResult register(
      TellusGeneratorRuntimeRegistry registry,
      ChunkGenerator generator,
      List<? extends TellusGeneratorRuntimeFactory.AdapterFactory> orderedFactories
   ) {
      Objects.requireNonNull(registry, "registry");
      Objects.requireNonNull(generator, "generator");
      Objects.requireNonNull(orderedFactories, "orderedFactories");
      return TellusGeneratorRuntimeFactory.createAndRegister(registry, generator, orderedFactories);
   }
}
