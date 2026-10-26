package com.yucareux.tellus.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yucareux.tellus.network.CubeNetBoundaryHintPayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Client-only visual cue; server admission remains authoritative. */
public final class CubeNetBoundaryRenderer {
   private static final ResourceLocation FORCEFIELD = new ResourceLocation("minecraft", "textures/misc/forcefield.png");
   private static final double HALF_LENGTH = 48.0;
   private static final double HEIGHT_BELOW = 48.0;
   private static final double HEIGHT_ABOVE = 64.0;
   private static BoundarySegment cached;
   private static long expiresAtTick;

   private CubeNetBoundaryRenderer() { }

   public static void tick(Minecraft client) {
      if (client.level == null || client.player == null
         || (cached != null && (client.level.dimension() != cached.dimension()
            || client.level.getGameTime() >= expiresAtTick
            || client.player.position().distanceToSqr(new Vec3(cached.x(), client.player.getY(), cached.z())) > 128.0 * 128.0))) {
         reset();
      }
   }

   public static void show(Minecraft client, CubeNetBoundaryHintPayload payload) {
      if (client.level == null || client.player == null) return;
      double length = Math.hypot(payload.tangentX(), payload.tangentZ());
      cached = new BoundarySegment(client.level.dimension(), payload.x(), payload.z(),
         payload.tangentX() / length, payload.tangentZ() / length);
      expiresAtTick = client.level.getGameTime() + payload.durationTicks();
   }

   public static void render(WorldRenderContext context) {
      BoundarySegment segment = cached;
      if (segment == null || context.world() == null || context.camera() == null
         || context.world().dimension() != segment.dimension()) return;
      Vec3 camera = context.camera().getPosition();
      double distance = Math.hypot(segment.x() - camera.x, segment.z() - camera.z);
      float opacity = (float)(0.65 * Math.max(0.0, 1.0 - distance / 96.0));
      if (opacity <= 0.0F) return;
      Matrix4f pose = context.matrixStack().last().pose();
      double leftX = segment.x() - segment.tangentX() * HALF_LENGTH - camera.x;
      double leftZ = segment.z() - segment.tangentZ() * HALF_LENGTH - camera.z;
      double rightX = segment.x() + segment.tangentX() * HALF_LENGTH - camera.x;
      double rightZ = segment.z() + segment.tangentZ() * HALF_LENGTH - camera.z;
      double bottom = Math.max(context.world().getMinBuildHeight() - camera.y, -HEIGHT_BELOW);
      double top = Math.min(context.world().getMaxBuildHeight() - camera.y, HEIGHT_ABOVE);
      float scroll = (Util.getMillis() % 3000L) / 3000.0F;
      float u0 = scroll, u1 = scroll + (float)(HALF_LENGTH * 2.0 / 4.0);
      float v0 = (float)((camera.y + bottom) / 4.0) + scroll;
      float v1 = (float)((camera.y + top) / 4.0) + scroll;

      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.enableDepthTest();
      RenderSystem.depthMask(false);
      RenderSystem.disableCull();
      RenderSystem.setShader(GameRenderer::getPositionTexShader);
      RenderSystem.setShaderTexture(0, FORCEFIELD);
      RenderSystem.setShaderColor(0.25F, 0.65F, 1.0F, opacity);
      try {
         BufferBuilder buffer = Tesselator.getInstance().getBuilder();
         buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
         buffer.vertex(pose, (float)leftX, (float)bottom, (float)leftZ).uv(u0, v0).endVertex();
         buffer.vertex(pose, (float)rightX, (float)bottom, (float)rightZ).uv(u1, v0).endVertex();
         buffer.vertex(pose, (float)rightX, (float)top, (float)rightZ).uv(u1, v1).endVertex();
         buffer.vertex(pose, (float)leftX, (float)top, (float)leftZ).uv(u0, v1).endVertex();
         BufferUploader.drawWithShader(buffer.end());
      } finally {
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         RenderSystem.enableCull();
         RenderSystem.depthMask(true);
         RenderSystem.disableBlend();
      }
   }

   public static void reset() {
      cached = null;
      expiresAtTick = 0L;
   }

   private record BoundarySegment(ResourceKey<Level> dimension, double x, double z,
      double tangentX, double tangentZ) { }
}
