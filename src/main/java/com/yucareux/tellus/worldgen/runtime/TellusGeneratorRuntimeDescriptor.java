package com.yucareux.tellus.worldgen.runtime;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Immutable descriptor for one ordered, typed runtime adapter family. */
public record TellusGeneratorRuntimeDescriptor(
   String id,
   TellusGeneratorSettingsView.Kind kind,
   String sourceLayer,
   TellusGeneratorRuntimeFactory.AdapterFactory adapterFactory,
   SettingsFactory settingsFactory
) {
   @FunctionalInterface
   public interface SettingsFactory {
      Optional<TellusGeneratorSettingsView> create(ChunkGenerator generator);
   }

   public TellusGeneratorRuntimeDescriptor {
      id = requireText(id, "id");
      Objects.requireNonNull(kind, "kind");
      sourceLayer = requireText(sourceLayer, "sourceLayer");
      Objects.requireNonNull(adapterFactory, "adapterFactory");
      Objects.requireNonNull(settingsFactory, "settingsFactory");
   }

   public Optional<TellusGeneratorRuntime> create(ChunkGenerator generator) {
      return Objects.requireNonNull(this.adapterFactory.create(
         Objects.requireNonNull(generator, "generator")), "adapter result");
   }

   public Optional<TellusGeneratorSettingsView> settings(ChunkGenerator generator) {
      return Objects.requireNonNull(this.settingsFactory.create(
         Objects.requireNonNull(generator, "generator")), "settings result");
   }

   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }
}
