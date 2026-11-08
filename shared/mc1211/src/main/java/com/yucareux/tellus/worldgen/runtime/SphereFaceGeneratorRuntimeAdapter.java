package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.SphereFaceChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** 1.21.1 typed Sphere-Face runtime shell; grouped face routing remains deferred. */
public final class SphereFaceGeneratorRuntimeAdapter implements TellusGeneratorRuntime {
   private static final String SOURCE_LAYER = "mc1211";
   private final SphereFaceChunkGenerator generator;
   private final SphereFaceGeneratorSettingsViewAdapter settings;
   private boolean attached;

   public SphereFaceGeneratorRuntimeAdapter(SphereFaceChunkGenerator generator) {
      this.generator = java.util.Objects.requireNonNull(generator, "generator");
      this.settings = new SphereFaceGeneratorSettingsViewAdapter(generator);
   }

   public String sourceLayer() { return SOURCE_LAYER; }
   public SphereFaceChunkGenerator generator() { return this.generator; }
   @Override public TellusGeneratorSettingsView settings() { return this.settings; }
   @Override public boolean owns(ChunkGenerator candidate) {
      return candidate == this.generator;
   }
   @Override public AttachResult attach(ChunkGenerator candidate) {
      if (!this.owns(java.util.Objects.requireNonNull(candidate, "generator"))) return AttachResult.NOT_OWNED;
      if (this.attached) return AttachResult.ALREADY_ATTACHED;
      this.attached = true;
      return AttachResult.ATTACHED;
   }
   @Override public void detach() { this.attached = false; }
   @Override public boolean attached() { return this.attached; }
}
