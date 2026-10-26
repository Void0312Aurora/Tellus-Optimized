package com.yucareux.tellus;

import com.yucareux.tellus.client.screen.EarthTeleportScreen;
import com.yucareux.tellus.client.hud.ManagedTerrainDownloadOverlay;
import com.yucareux.tellus.client.SphereClientState;
import com.yucareux.tellus.client.CubeNetClientTransition;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainClientState;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainViewDistance;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.ManagedTerrainViewPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.network.SphereFramePayload;
import com.yucareux.tellus.network.CubeNetTransitionPreparePayload;
import com.yucareux.tellus.network.CubeNetTransitionCancelPayload;
import com.yucareux.tellus.network.CubeNetTransitionCommitPayload;
import com.yucareux.tellus.platform.TellusClientPlatform;
import com.yucareux.tellus.world.realtime.SnowGrid;
import com.yucareux.tellus.world.realtime.TemperatureGrid;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.util.Objects;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class TellusClient implements ClientModInitializer {
   private static final KeyMapping OPEN_MAP_KEY = new KeyMapping(
      "key.tellus.open_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.category.tellus.controls"
   );
   private int managedTerrainViewUpdateTicks;

   @Override
   public void onInitializeClient() {
      TellusClientPlatform.configureGeoTeleport(
         () -> ClientPlayNetworking.canSend(GeoTpTeleportPayload.TYPE),
         (latitude, longitude) -> ClientPlayNetworking.send(new GeoTpTeleportPayload(latitude, longitude))
      );
      KeyBindingHelper.registerKeyBinding(OPEN_MAP_KEY);
      HudRenderCallback.EVENT.register((graphics, tickDelta) -> ManagedTerrainDownloadOverlay.render(graphics));
      ClientPlayNetworking.registerGlobalReceiver(Objects.requireNonNull(GeoTpOpenMapPayload.TYPE, "GeoTpOpenMapPayload.TYPE"), (payload, player, responseSender) -> {
         Minecraft minecraft = Minecraft.getInstance();
         minecraft.execute(() -> {
            Screen parent = minecraft.screen;
            minecraft.setScreen(new EarthTeleportScreen(parent, payload.latitude(), payload.longitude()));
         });
      });
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(TellusWeatherPayload.TYPE, "TellusWeatherPayload.TYPE"),
         (payload, player, responseSender) -> Minecraft.getInstance()
            .execute(
               () -> {
                  SnowGrid grid = payload.historicalSnowEnabled() && payload.spacingBlocks() > 0
                     ? new SnowGrid(payload.centerX(), payload.centerZ(), payload.spacingBlocks(), payload.snowIndex())
                     : SnowGrid.empty();
                  TemperatureGrid temperatureGrid = payload.spacingBlocks() > 0
                     ? new TemperatureGrid(
                        payload.centerX(),
                        payload.centerZ(),
                        payload.spacingBlocks(),
                        payload.temperatureC(),
                        System.currentTimeMillis() - Math.max(0L, payload.temperatureAgeMs())
                     )
                     : TemperatureGrid.empty();
                  TellusRealtimeState.updateWeatherState(
                     payload.weatherEnabled(), payload.precipitationMode(), payload.historicalSnowEnabled(), grid, temperatureGrid
                  );
               }
            )
      );
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(ManagedTerrainStatusPayload.TYPE, "ManagedTerrainStatusPayload.TYPE"),
         (payload, player, responseSender) -> Minecraft.getInstance().execute(() -> ManagedTerrainClientState.update(payload.status()))
      );
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(SphereFramePayload.TYPE, "SphereFramePayload.TYPE"),
         (payload, player, responseSender) -> Minecraft.getInstance().execute(() -> {
            if (!payload.spherical()) {
               SphereClientState.update(SphereClientState.Snapshot.empty(payload.revision()));
               return;
            }
            SphereClientState.update(new SphereClientState.Snapshot(
               true, payload.planet(), payload.mappingId(), payload.face(), payload.x(), payload.y(), payload.z(),
               payload.worldScale(), payload.yaw(), payload.revision()
            ));
         })
      );
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(CubeNetTransitionPreparePayload.TYPE, "CubeNetTransitionPreparePayload.TYPE"),
         (payload, player, responseSender) -> Minecraft.getInstance().execute(
            () -> CubeNetClientTransition.prepare(payload))
      );
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(CubeNetTransitionCommitPayload.TYPE, "CubeNetTransitionCommitPayload.TYPE"),
         (payload, player, responseSender) -> Minecraft.getInstance().execute(
            () -> CubeNetClientTransition.commit(payload))
      );
      WorldRenderEvents.AFTER_SETUP.register(context -> CubeNetClientTransition.renderedFrame(Minecraft.getInstance()));
      ClientPlayNetworking.registerGlobalReceiver(CubeNetTransitionCancelPayload.TYPE,
         (payload, player, responseSender) -> Minecraft.getInstance().execute(
            () -> CubeNetClientTransition.cancel(payload.transitionId())));
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         CubeNetClientTransition.tick(client);
         if (client.player != null) {
            SphereClientState.updateLocalPose(client.player.getX(), client.player.getY(), client.player.getZ(), client.player.getYRot());
         }
         while (OPEN_MAP_KEY.consumeClick()) {
            if (client.screen == null && client.player != null && client.getConnection() != null) {
               client.getConnection().sendCommand("tellus map");
            }
         }

         if (client.player != null && ++this.managedTerrainViewUpdateTicks >= 40) {
            this.managedTerrainViewUpdateTicks = 0;
            if (ClientPlayNetworking.canSend(ManagedTerrainViewPayload.TYPE)) {
               ClientPlayNetworking.send(new ManagedTerrainViewPayload(ManagedTerrainViewDistance.detect()));
            }
         }
      });
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
         TellusRealtimeState.reset();
         ManagedTerrainClientState.reset();
         SphereClientState.reset();
         CubeNetClientTransition.reset();
         CubeNetClientTransition.resetShadeOrientation();
         this.managedTerrainViewUpdateTicks = 0;
      });
   }
}
