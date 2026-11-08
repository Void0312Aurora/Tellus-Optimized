package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.CubeNetBiomeSource;
import com.yucareux.tellus.worldgen.CubeNetChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Phase 1 typed Cube-Net runtime shell; seam manager takeover remains deferred. */
public final class CubeNetGeneratorRuntimeAdapter implements TellusGeneratorRuntime {
   private final CubeNetChunkGenerator generator;
   private final CubeNetGeneratorSettingsViewAdapter settings;
   private boolean attached;

   public CubeNetGeneratorRuntimeAdapter(CubeNetChunkGenerator generator) {
      this.generator = java.util.Objects.requireNonNull(generator, "generator");
      this.settings = new CubeNetGeneratorSettingsViewAdapter(generator);
   }

   public CubeNetGeneratorRuntimeAdapter(
      CubeNetChunkGenerator generator, CubeNetBiomeSource.Settings sourceSettings
   ) {
      this.generator = java.util.Objects.requireNonNull(generator, "generator");
      this.settings = new CubeNetGeneratorSettingsViewAdapter(generator, sourceSettings);
   }

   public CubeNetChunkGenerator generator() {
      return this.generator;
   }

   @Override
   public TellusGeneratorSettingsView settings() {
      return this.settings;
   }

   @Override
   public boolean owns(ChunkGenerator candidate) {
      return candidate == this.generator;
   }

   @Override
   public AttachResult attach(ChunkGenerator candidate) {
      if (!this.owns(java.util.Objects.requireNonNull(candidate, "generator"))) {
         return AttachResult.NOT_OWNED;
      }
      if (this.attached) {
         return AttachResult.ALREADY_ATTACHED;
      }
      this.attached = true;
      return AttachResult.ATTACHED;
   }

   @Override
   public void detach() {
      this.attached = false;
   }

   @Override
   public boolean attached() {
      return this.attached;
   }
}
