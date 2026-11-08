package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Phase 1 Earth runtime shell. It only establishes ownership and settings
 * access; TellusCommon remains the lifecycle/manager authority for now.
 */
public final class EarthGeneratorRuntimeAdapter implements TellusGeneratorRuntime {
   private final EarthChunkGenerator generator;
   private final EarthGeneratorSettingsViewAdapter settings;
   private boolean attached;

   public EarthGeneratorRuntimeAdapter(EarthChunkGenerator generator) {
      this.generator = java.util.Objects.requireNonNull(generator, "generator");
      this.settings = new EarthGeneratorSettingsViewAdapter(generator);
   }

   public EarthChunkGenerator generator() {
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
