package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.TellusSodiumRenderBackend;
import com.yucareux.tellus.client.CubeNetSodiumSectionAccess;
import com.yucareux.tellus.client.CubeNetSodiumRendererStage;
import com.yucareux.tellus.client.CubeNetClientTransition;
import com.yucareux.tellus.TellusCommon;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native staged renderer support for Sodium 0.5.13 on Minecraft 1.20.1. */
@Mixin(SodiumWorldRenderer.class)
public abstract class CubeNetSodiumWorldRendererMixin implements TellusSodiumRenderBackend {
   /** Keep the handoff responsive; the promoted manager continues its halo build normally. */
   @Unique private static final int tellus$readyRadius = 1;
   /** Normal movement keeps the reverse result only through this distance from the commit pose. */
   @Unique private static final double tellus$cacheReleaseDistance = 64.0;
   @Shadow private ClientLevel world;
   @Shadow private int renderDistance;
   @Shadow private RenderSectionManager renderSectionManager;
   @Unique private CubeNetSodiumRendererStage tellus$pendingStage;
   @Unique private CubeNetSodiumRendererStage tellus$cachedStage;
   @Unique private int tellus$activeCenterChunkX;
   @Unique private int tellus$activeCenterChunkZ;
   @Unique private int tellus$activeRenderRadius;
   @Unique private int tellus$activeShadeQuarterTurns;
   @Unique private boolean tellus$activeCenterKnown;
   @Unique private double tellus$cacheAnchorX;
   @Unique private double tellus$cacheAnchorZ;
   @Unique private boolean tellus$cacheAnchorKnown;

   @Inject(method = {"initRenderer", "unloadWorld"}, at = @At("HEAD"))
   private void tellus$invalidateStagedRenderer(CallbackInfo callback) {
      // Sodium invokes these lifecycle methods inside managed render code.
      // Do not enter/exit that context again while the outer call owns it.
      CubeNetSodiumRendererStage staged = this.tellus$pendingStage;
      if (staged != null) {
         this.tellus$pendingStage = null;
         staged.consumed = true;
         staged.manager.destroy();
      }
      CubeNetSodiumRendererStage cached = this.tellus$cachedStage;
      if (cached != null) {
         this.tellus$cachedStage = null;
         cached.consumed = true;
         cached.manager.destroy();
      }
      this.tellus$activeCenterKnown = false;
      this.tellus$cacheAnchorKnown = false;
      CubeNetClientTransition.rendererInvalidated();
   }

   @Override
   public Object tellus$createStagedRenderer(int centerChunkX, int centerChunkZ, int radius,
      int shadingQuarterTurns) {
      if (this.world == null || this.renderSectionManager == null) {
         throw new IllegalStateException("Sodium world renderer is not initialized");
      }
      if (radius < 1 || radius > this.renderDistance) {
         throw new IllegalArgumentException("invalid staged Sodium radius: " + radius);
      }
      if (this.tellus$pendingStage != null) {
         throw new IllegalStateException("Sodium already owns a staged renderer");
      }
      if (!this.tellus$activeCenterKnown && Minecraft.getInstance().player != null) {
         this.tellus$activeCenterChunkX = Minecraft.getInstance().player.chunkPosition().x;
         this.tellus$activeCenterChunkZ = Minecraft.getInstance().player.chunkPosition().z;
         this.tellus$activeRenderRadius = this.renderDistance;
         this.tellus$activeShadeQuarterTurns = 0;
         this.tellus$activeCenterKnown = true;
      }
      CubeNetSodiumRendererStage cached = this.tellus$cachedStage;
      if (cached != null && cached.matches(centerChunkX, centerChunkZ, radius, shadingQuarterTurns)) {
         this.tellus$cachedStage = null;
         this.tellus$cacheAnchorKnown = false;
         this.tellus$pendingStage = cached;
         TellusCommon.LOGGER.info("Cube-net Sodium renderer reusing reverse cache center=({}, {}) shade={}",
            cached.centerChunkX, cached.centerChunkZ, cached.shadingQuarterTurns);
         return cached;
      }
      // There is only one neighbor slot. A stale reverse result is released
      // before the slot is used for the next seam target.
      this.tellus$discardCachedRenderer();
      RenderSectionManager staged = null;
      RenderDevice.enterManagedCode();
      try {
         try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            staged = new RenderSectionManager(this.world, this.renderDistance, commandList);
         }
         for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
            for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
               staged.onChunkAdded(x, z);
            }
         }
         ((CubeNetSodiumSectionAccess)(Object)staged).tellus$queueInitialBuilds(centerChunkX, centerChunkZ);
         int diameter = radius * 2 + 1;
         int sectionCount = diameter * diameter * this.world.getSectionsCount();
         if (stagedSectionCount(staged) != sectionCount) {
            throw new IllegalStateException("Sodium staged view does not cover the destination");
         }
         this.tellus$pendingStage = new CubeNetSodiumRendererStage(staged, sectionCount,
            centerChunkX, centerChunkZ, radius, shadingQuarterTurns, tellus$readyRadius);
         return this.tellus$pendingStage;
      } catch (RuntimeException error) {
         if (staged != null) staged.destroy();
         throw error;
      } finally {
         RenderDevice.exitManagedCode();
      }
   }

   @Override
   public void tellus$tickStagedRenderer(Object stagedRenderer) {
      CubeNetSodiumRendererStage staged = requireStage(stagedRenderer);
      RenderDevice.enterManagedCode();
      try {
         staged.manager.updateChunks(false);
         staged.manager.uploadChunks();
         ((CubeNetSodiumSectionAccess)(Object)staged.manager).tellus$queueInitialBuilds(
            staged.centerChunkX, staged.centerChunkZ);
      } finally {
         RenderDevice.exitManagedCode();
      }
   }

   @Override
   public boolean tellus$stagedRendererReady(Object stagedRenderer) {
      CubeNetSodiumRendererStage staged = requireStage(stagedRenderer);
      return staged.manager.getTotalSections() == staged.expectedSections
         && ((CubeNetSodiumSectionAccess)(Object)staged.manager).tellus$coreSectionsBuilt(
            staged.centerChunkX, staged.centerChunkZ, staged.readyRadius);
   }

   @Override
   public int tellus$stagedRendererSectionCount(Object stagedRenderer) {
      return requireStage(stagedRenderer).expectedSections;
   }

   @Override
   public void tellus$commitStagedRenderer(Object stagedRenderer, int centerChunkX, int centerChunkZ,
      int renderRadius) {
      CubeNetSodiumRendererStage staged = requireStage(stagedRenderer);
      if (!this.tellus$stagedRendererReady(stagedRenderer)) {
         throw new IllegalStateException("Sodium staged core sections are not built and uploaded");
      }
      if (renderRadius < 1 || renderRadius > this.renderDistance) {
         throw new IllegalArgumentException("invalid promoted Sodium radius: " + renderRadius);
      }
      RenderDevice.enterManagedCode();
      try {
         // Packet promotion creates the ordinary cache's LevelChunks. Native
         // meshes can be retained, but their sign/chest references must now
         // point at those chunks' live objects rather than the remote snapshot.
         int entities = ((CubeNetSodiumSectionAccess)(Object)staged.manager).tellus$rebindBlockEntities(this.world);
         RenderSectionManager previous = this.renderSectionManager;
         if (this.tellus$cachedStage != null) {
            this.tellus$cachedStage.consumed = true;
            this.tellus$cachedStage.manager.destroy();
            this.tellus$cachedStage = null;
         }
         if (previous != null && this.tellus$activeCenterKnown) {
            this.tellus$cachedStage = new CubeNetSodiumRendererStage(previous,
               stagedSectionCount(previous), this.tellus$activeCenterChunkX, this.tellus$activeCenterChunkZ,
               this.tellus$activeRenderRadius, this.tellus$activeShadeQuarterTurns, tellus$readyRadius);
            Minecraft playerClient = Minecraft.getInstance();
            if (playerClient.player != null) {
               this.tellus$cacheAnchorX = playerClient.player.getX();
               this.tellus$cacheAnchorZ = playerClient.player.getZ();
               this.tellus$cacheAnchorKnown = true;
            }
         } else if (previous != null) {
            previous.destroy();
         }
         this.renderSectionManager = staged.manager;
         this.tellus$activeCenterChunkX = staged.centerChunkX;
         this.tellus$activeCenterChunkZ = staged.centerChunkZ;
         this.tellus$activeRenderRadius = renderRadius;
         this.tellus$activeShadeQuarterTurns = staged.shadingQuarterTurns;
         this.tellus$activeCenterKnown = true;
         staged.manager.markGraphDirty();
         staged.consumed = true;
         this.tellus$pendingStage = null;
         // The destination packets are already in ClientChunkCache here. Keep
         // the explicit replay because ChunkTracker events may have been
         // consumed by the renderer that was just destroyed. For a complete
         // staged view this is a no-op for sections that are already present.
         for (int z = centerChunkZ - renderRadius; z <= centerChunkZ + renderRadius; z++) {
            for (int x = centerChunkX - renderRadius; x <= centerChunkX + renderRadius; x++) {
               staged.manager.onChunkAdded(x, z);
            }
         }
         ((CubeNetSodiumSectionAccess)(Object)staged.manager).tellus$queueInitialBuilds(
            staged.centerChunkX, staged.centerChunkZ);
         staged.manager.markGraphDirty();
         TellusCommon.LOGGER.info("Cube-net Sodium renderer promoted sections={} readyRadius={} liveBlockEntities={}",
            ((CubeNetSodiumSectionAccess)(Object)staged.manager).tellus$sections().size(),
            staged.readyRadius, entities);
      } finally {
         RenderDevice.exitManagedCode();
      }
   }

   @Override
   public void tellus$discardStagedRenderer(Object stagedRenderer) {
      if (stagedRenderer instanceof CubeNetSodiumRendererStage prior && prior.consumed) return;
      CubeNetSodiumRendererStage staged = requireStage(stagedRenderer);
      RenderDevice.enterManagedCode();
      try {
         staged.manager.destroy();
         staged.consumed = true;
         this.tellus$pendingStage = null;
      } finally {
         RenderDevice.exitManagedCode();
      }
   }

   @Override
   public void tellus$updateCachedRendererPosition(double x, double z) {
      if (this.tellus$cachedStage == null || !this.tellus$cacheAnchorKnown) return;
      double dx = x - this.tellus$cacheAnchorX;
      double dz = z - this.tellus$cacheAnchorZ;
      if (dx * dx + dz * dz <= tellus$cacheReleaseDistance * tellus$cacheReleaseDistance) return;
      this.tellus$discardCachedRenderer();
      TellusCommon.LOGGER.info("Cube-net Sodium reverse renderer cache released distance={}",
         Math.sqrt(dx * dx + dz * dz));
   }

   @Override
   public void tellus$discardCachedRenderer() {
      CubeNetSodiumRendererStage cached = this.tellus$cachedStage;
      if (cached == null) return;
      RenderDevice.enterManagedCode();
      try {
         cached.consumed = true;
         cached.manager.destroy();
         this.tellus$cachedStage = null;
         this.tellus$cacheAnchorKnown = false;
      } finally {
         RenderDevice.exitManagedCode();
      }
   }

   private static int stagedSectionCount(RenderSectionManager manager) {
      return ((CubeNetSodiumSectionAccess)(Object)manager).tellus$sections().size();
   }

   private static CubeNetSodiumRendererStage requireStage(Object stagedRenderer) {
      if (!(stagedRenderer instanceof CubeNetSodiumRendererStage staged) || staged.consumed) {
         throw new IllegalStateException("Sodium staged renderer is no longer active");
      }
      return staged;
   }

}
