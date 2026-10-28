package com.yucareux.tellus.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.network.CubeNetTransitionPreparePayload;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Optional bridge to the Tellus DH fork's render-only seam envelope. */
public final class DistantHorizonsSeamClient {
   private static final String BRIDGE =
      "com.seibel.distanthorizons.core.api.external.methods.render.DhApiSeamTransition";
   private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
   private static volatile Access access;

   private DistantHorizonsSeamClient() { }

   /**
    * Starts the DH gate. Returning false means the optional fork bridge is not
    * active, so the existing native transition remains fail-open for stock DH.
    */
   public static boolean begin(CubeNetTransitionPreparePayload payload) {
      Access api = api();
      if (api == null) return false;
      IDhApiLevelWrapper level = currentLevel();
      if (level == null) return false;
      try {
         boolean started = (boolean)api.begin.invoke(null, level, payload.transitionId(),
            payload.centerChunkX() * 16 + 8, payload.centerChunkZ() * 16 + 8);
         if (started) {
            ACTIVE.add(payload.transitionId());
            TellusCommon.LOGGER.info("Cube-net DH render envelope preparing {} target=({}, {})",
               payload.transitionId(), payload.centerChunkX(), payload.centerChunkZ());
         }
         return started;
      } catch (ReflectiveOperationException | RuntimeException error) {
         TellusCommon.LOGGER.warn("Cube-net DH render envelope bridge failed to start {}",
            payload.transitionId(), error);
         return false;
      }
   }

   public static boolean ready(UUID transitionId) {
      if (!ACTIVE.contains(transitionId)) return true;
      IDhApiLevelWrapper level = currentLevel();
      if (level == null) return false;
      Access api = api();
      if (api == null) return false;
      try {
         return (boolean)api.ready.invoke(null, level, transitionId);
      } catch (ReflectiveOperationException | RuntimeException error) {
         TellusCommon.LOGGER.warn("Cube-net DH render envelope bridge failed to query {}",
            transitionId, error);
         return false;
      }
   }

   public static void end(UUID transitionId, boolean committed) {
      if (!ACTIVE.remove(transitionId)) return;
      Access api = api();
      IDhApiLevelWrapper level = currentLevel();
      if (api == null || level == null) return;
      try {
         api.end.invoke(null, level, transitionId, committed);
         TellusCommon.LOGGER.info("Cube-net DH render envelope {} {}",
            committed ? "committed" : "cancelled", transitionId);
      } catch (ReflectiveOperationException | RuntimeException error) {
         TellusCommon.LOGGER.warn("Cube-net DH render envelope bridge failed to end {}",
            transitionId, error);
      }
   }

   private static Access api() {
      Access current = access;
      if (current != null) return current == Access.UNAVAILABLE ? null : current;
      try {
         Class<?> type = Class.forName(BRIDGE);
         current = new Access(type.getMethod("begin", IDhApiLevelWrapper.class, UUID.class, int.class, int.class),
            type.getMethod("ready", IDhApiLevelWrapper.class, UUID.class),
            type.getMethod("end", IDhApiLevelWrapper.class, UUID.class, boolean.class));
      } catch (ReflectiveOperationException | LinkageError error) {
         access = Access.UNAVAILABLE;
         return null;
      }
      access = current;
      return current;
   }

   private static IDhApiLevelWrapper currentLevel() {
      Minecraft client = Minecraft.getInstance();
      if (client.level == null || DhApi.Delayed.worldProxy == null) return null;
      String dimension = client.level.dimension().location().toString();
      try {
         for (IDhApiLevelWrapper level : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
            if (level != null && dimension.equals(level.getDimensionName())) return level;
         }
      } catch (RuntimeException ignored) {
         // DH may be between client-level unload and reload on this tick.
      }
      return null;
   }

   private record Access(Method begin, Method ready, Method end) {
      private static final Access UNAVAILABLE = new Access(null, null, null);
   }
}
