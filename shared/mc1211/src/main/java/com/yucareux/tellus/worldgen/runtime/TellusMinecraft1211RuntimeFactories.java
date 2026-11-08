package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.CubeNetBiomeSource;
import com.yucareux.tellus.worldgen.CubeNetChunkGenerator;
import com.yucareux.tellus.worldgen.SphereFaceChunkGenerator;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Ordered 1.21.1 descriptors; declaration alone does not register lifecycle hooks. */
public final class TellusMinecraft1211RuntimeFactories {
   private TellusMinecraft1211RuntimeFactories() { }

   public static List<TellusGeneratorRuntimeDescriptor> descriptors() {
      return List.of(TellusEarthRuntimeFactories.descriptor(), cubeNet(), sphereFace());
   }

   public static List<TellusGeneratorRuntimeFactory.AdapterFactory> orderedAdapterFactories() {
      return descriptors().stream().map(TellusGeneratorRuntimeDescriptor::adapterFactory).toList();
   }

   public static Optional<TellusGeneratorRuntime> create(ChunkGenerator generator) {
      return TellusGeneratorRuntimeFactory.create(generator, orderedAdapterFactories()).runtime();
   }

   public static Optional<TellusGeneratorSettingsView> settings(ChunkGenerator generator) {
      for (TellusGeneratorRuntimeDescriptor descriptor : descriptors()) {
         Optional<TellusGeneratorSettingsView> result = descriptor.settings(generator);
         if (result.isPresent()) return result;
      }
      return Optional.empty();
   }

   private static TellusGeneratorRuntimeDescriptor cubeNet() {
      return new TellusGeneratorRuntimeDescriptor(
         "tellus:cube_net", TellusGeneratorSettingsView.Kind.CUBE_NET, "mc1211",
         generator -> generator instanceof CubeNetChunkGenerator cube
            ? Optional.of(new CubeNetGeneratorRuntimeAdapter(cube)) : Optional.empty(),
         generator -> generator instanceof CubeNetChunkGenerator cube
            ? Optional.of(new CubeNetGeneratorSettingsViewAdapter(cube)) : Optional.empty()
      );
   }

   private static TellusGeneratorRuntimeDescriptor sphereFace() {
      return new TellusGeneratorRuntimeDescriptor(
         "tellus:sphere_face", TellusGeneratorSettingsView.Kind.SPHERE_FACE, "mc1211",
         generator -> generator instanceof SphereFaceChunkGenerator sphere
            ? Optional.of(new SphereFaceGeneratorRuntimeAdapter(sphere)) : Optional.empty(),
         generator -> generator instanceof SphereFaceChunkGenerator sphere
            ? Optional.of(new SphereFaceGeneratorSettingsViewAdapter(sphere)) : Optional.empty()
      );
   }
}
