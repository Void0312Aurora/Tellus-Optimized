package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Root-source Earth descriptor; no lifecycle or consumer registration. */
public final class TellusEarthRuntimeFactories {
   private TellusEarthRuntimeFactories() { }

   public static TellusGeneratorRuntimeDescriptor descriptor() {
      return new TellusGeneratorRuntimeDescriptor(
         "tellus:earth", TellusGeneratorSettingsView.Kind.EARTH, "root",
         generator -> generator instanceof EarthChunkGenerator earth
            ? Optional.of(new EarthGeneratorRuntimeAdapter(earth)) : Optional.empty(),
         generator -> generator instanceof EarthChunkGenerator earth
            ? Optional.of(new EarthGeneratorSettingsViewAdapter(earth)) : Optional.empty()
      );
   }

   public static List<TellusGeneratorRuntimeDescriptor> descriptors() {
      return List.of(descriptor());
   }

   public static List<TellusGeneratorRuntimeFactory.AdapterFactory> orderedAdapterFactories() {
      return descriptors().stream().map(TellusGeneratorRuntimeDescriptor::adapterFactory).toList();
   }

   public static Optional<TellusGeneratorRuntime> create(ChunkGenerator generator) {
      return descriptor().create(generator);
   }

   public static Optional<TellusGeneratorSettingsView> settings(ChunkGenerator generator) {
      return descriptor().settings(generator);
   }
}
