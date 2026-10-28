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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native staged renderer support for Sodium 0.5.13 on Minecraft 1.20.1. */
@Mixin(SodiumWorldRenderer.class)
public abstract class CubeNetSodiumWorldRendererMixin implements TellusSodiumRenderBackend {
   @Shadow private ClientLevel world;
   @Shadow private int renderDistance;
   @Shadow private RenderSectionManager renderSectionManager;
   @Unique private CubeNetSodiumRendererStage tellus$pendingStage;

   @Inject(method = {"initRenderer", "unloadWorld"}, at = @At("HEAD"))
   private void tellus$invalidateStagedRenderer(CallbackInfo callback) {
      // Sodium invokes these lifecycle methods inside managed render code.
      // Do not enter/exit that context again while the outer call owns it.
      CubeNetSodiumRendererStage staged = this.tellus$pendingStage;
      if (staged == null) return;
      this.tellus$pendingStage = null;
      staged.consumed = true;
      staged.manager.destroy();
      CubeNetClientTransition.rendererInvalidated();
   }

   @Override
   public Object tellus$createStagedRenderer(int centerChunkX, int centerChunkZ) {
      if (this.world == null || this.renderSectionManager == null) {
         throw new IllegalStateException("Sodium world renderer is not initialized");
      }
      if (this.tellus$pendingStage != null) {
         throw new IllegalStateException("Sodium already owns a staged renderer");
      }
      RenderSectionManager staged = null;
      RenderDevice.enterManagedCode();
      try {
         try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            staged = new RenderSectionManager(this.world, this.renderDistance, commandList);
         }
         int radius = this.renderDistance;
         for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
            for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
               staged.onChunkAdded(x, z);
            }
         }
         ((CubeNetSodiumSectionAccess)(Object)staged).tellus$queueInitialBuilds();
         int diameter = radius * 2 + 1;
         int sectionCount = diameter * diameter * this.world.getSectionsCount();
         if (stagedSectionCount(staged) != sectionCount) {
            throw new IllegalStateException("Sodium staged view does not cover the destination");
         }
         this.tellus$pendingStage = new CubeNetSodiumRendererStage(staged, sectionCount);
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
         ((CubeNetSodiumSectionAccess)(Object)staged.manager).tellus$queueInitialBuilds();
      } finally {
         RenderDevice.exitManagedCode();
      }
   }

   @Override
   public boolean tellus$stagedRendererReady(Object stagedRenderer) {
      CubeNetSodiumRendererStage staged = requireStage(stagedRenderer);
      return staged.manager.getTotalSections() == staged.expectedSections
         && ((CubeNetSodiumSectionAccess)(Object)staged.manager).tellus$allSectionsBuilt()
         && staged.manager.getBuilder().isBuildQueueEmpty();
   }

   @Override
   public int tellus$stagedRendererSectionCount(Object stagedRenderer) {
      return requireStage(stagedRenderer).expectedSections;
   }

   @Override
   public void tellus$commitStagedRenderer(Object stagedRenderer) {
      CubeNetSodiumRendererStage staged = requireStage(stagedRenderer);
      if (!this.tellus$stagedRendererReady(stagedRenderer)) {
         throw new IllegalStateException("Sodium staged sections are not fully built and uploaded");
      }
      RenderDevice.enterManagedCode();
      try {
         // Packet promotion creates the ordinary cache's LevelChunks. Native
         // meshes can be retained, but their sign/chest references must now
         // point at those chunks' live objects rather than the remote snapshot.
         int entities = ((CubeNetSodiumSectionAccess)(Object)staged.manager).tellus$rebindBlockEntities(this.world);
         RenderSectionManager previous = this.renderSectionManager;
         this.renderSectionManager = staged.manager;
         staged.manager.markGraphDirty();
         staged.consumed = true;
         this.tellus$pendingStage = null;
         if (previous != null) previous.destroy();
         TellusCommon.LOGGER.info("Cube-net Sodium renderer promoted sections={} liveBlockEntities={}",
            staged.expectedSections, entities);
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
