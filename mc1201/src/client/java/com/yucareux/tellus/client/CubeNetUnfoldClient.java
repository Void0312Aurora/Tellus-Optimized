package com.yucareux.tellus.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.network.CubeNetLayoutPayload;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetUnfoldTracker;
import com.yucareux.tellus.worldgen.global.CubeNetUnfolding;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;

/**
 * Publishes the cube-net development the Tellus DH fork draws the far view
 * through. Without the fork the bridge stays inactive and DH renders the
 * planar net as before.
 */
public final class CubeNetUnfoldClient {
   private static final String BRIDGE =
      "com.seibel.distanthorizons.core.api.external.methods.render.DhApiSeamTransition";
   private static final boolean ENABLED = !Boolean.getBoolean("tellus.cubeNetUnfold.disable");

   private static volatile CubeNetUnfoldTracker tracker;
   private static Method publish;
   private static boolean bridgeMissing;
   /** DH level the last update reached; a different wrapper means DH reloaded and needs a full republish. */
   private static IDhApiLevelWrapper publishedLevel;

   private CubeNetUnfoldClient() { }

   /** Server-authoritative layout for the current level. */
   public static void configure(CubeNetLayoutPayload payload) {
      CubeNetUnfoldTracker previous = tracker;
      if (!payload.cubeNet() || !ENABLED) {
         tracker = null;
         if (previous != null) disable();
         return;
      }
      if (previous != null && previous.unfolding().layout().faceSize() == payload.faceSize()) return;
      tracker = new CubeNetUnfoldTracker(new CubeNetUnfolding(
         CubeNetLayout.standard(payload.faceSize()), payload.apronBlocks()));
      publishedLevel = null;
      TellusCommon.LOGGER.info("Cube-net far-view unfold configured faceSize={} apron={}",
         payload.faceSize(), payload.apronBlocks());
   }

   /** Ordinary per-tick update; publishes only when the face or chart changed. */
   public static void tick(Minecraft client) {
      if (tracker == null || client.player == null) return;
      update(client, client.player.getX(), client.player.getZ());
   }

   /**
    * Called by the seam commit on the main thread right after the player pose
    * is moved and before the next frame is rendered.
    */
   public static void cameraMoved(Minecraft client) {
      if (tracker == null || client.player == null) return;
      update(client, client.player.getX(), client.player.getZ());
   }

   /** True once DH accepted a development for the current level. */
   public static boolean active() {
      return tracker != null && publishedLevel != null;
   }

   public static void reset() {
      CubeNetUnfoldTracker current = tracker;
      tracker = null;
      if (current != null) disable();
      publishedLevel = null;
   }

   private static void update(Minecraft client, double x, double z) {
      CubeNetUnfoldTracker current = tracker;
      IDhApiLevelWrapper level = DistantHorizonsSeamClient.currentLevel();
      if (current == null || level == null) return;
      if (level != publishedLevel) current.reset();
      long observedNanos = System.nanoTime();
      CubeNetUnfoldTracker.Update update = current.observe(x, z);
      if (update == null) return;
      long encodedNanos = System.nanoTime();
      if (send(level, update.sheets(), update.chartChange())) {
         publishedLevel = level;
         TellusCommon.LOGGER.info("Cube-net far-view unfold published face={} chart={} jump={} gameTime={} observeUs={} sendUs={}",
            update.frame().physical(), update.frame().chart(),
            update.chartChange() == null ? "none" : java.util.Arrays.toString(update.chartChange()),
            client.level == null ? -1L : client.level.getGameTime(),
            (encodedNanos - observedNanos) / 1_000L,
            (System.nanoTime() - encodedNanos) / 1_000L);
      } else {
         // DH has no rendering level yet; retry on the next tick.
         current.reset();
      }
   }

   private static void disable() {
      IDhApiLevelWrapper level = DistantHorizonsSeamClient.currentLevel();
      if (level != null) send(level, null, null);
   }

   private static boolean send(IDhApiLevelWrapper level, int[] sheets, int[] chartChange) {
      Method method = bridge();
      if (method == null) return false;
      try {
         return (boolean)method.invoke(null, level, sheets, chartChange);
      } catch (ReflectiveOperationException | RuntimeException error) {
         TellusCommon.LOGGER.warn("Cube-net far-view unfold bridge failed", error);
         return false;
      }
   }

   private static Method bridge() {
      if (publish != null || bridgeMissing) return publish;
      if (DhApi.Delayed.worldProxy == null) return null;
      try {
         publish = Class.forName(BRIDGE).getMethod("publishUnfold", IDhApiLevelWrapper.class, int[].class, int[].class);
      } catch (ReflectiveOperationException | LinkageError error) {
         bridgeMissing = true;
         TellusCommon.LOGGER.info("Distant Horizons has no cube-net unfold bridge; far view stays planar");
      }
      return publish;
   }
}
