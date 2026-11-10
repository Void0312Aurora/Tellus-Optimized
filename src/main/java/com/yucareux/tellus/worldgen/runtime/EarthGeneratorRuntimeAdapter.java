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
   private net.minecraft.server.level.ServerLevel nativeLevel;
   private long nativeTickCallbacks, nativeUnloadCallbacks;

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
       this.nativeLevel = null;
   }

   @Override
   public boolean attached() {
      return this.attached;
   }

   @Override public void bindNativeLevel(net.minecraft.server.level.ServerLevel level) {
      java.util.Objects.requireNonNull(level, "level");
      if (level.getChunkSource().getGenerator() != generator || nativeLevel != null && nativeLevel != level)
         throw new IllegalArgumentException("native Earth runtime level ownership differs");
      nativeLevel = level;
   }
   @Override public boolean managesDeferredChunkDetails() { return attached && nativeLevel != null; }
   @Override public void tick() {
      if (managesDeferredChunkDetails()) {
         generator.processDeferredChunkDetailTick(nativeLevel);
         nativeTickCallbacks++;
      }
   }
   @Override public boolean onNativeChunkUnload(net.minecraft.world.level.ChunkPos position) {
      if (!managesDeferredChunkDetails()) return false;
      generator.discardPreparedChunkState(position);
      nativeUnloadCallbacks++;
      return true;
   }
   public long nativeTickCallbacks() { return nativeTickCallbacks; }
   public long nativeUnloadCallbacks() { return nativeUnloadCallbacks; }
}
