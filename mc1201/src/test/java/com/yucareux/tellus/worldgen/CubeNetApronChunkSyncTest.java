package com.yucareux.tellus.worldgen;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CubeNetApronChunkSyncTest {
   @Test
   void retainsOverlayUntilBothViewAndOwnerAreAvailable() {
      ChunkPos view = new ChunkPos(640, 320);
      Map<ChunkPos, Integer> pending = new HashMap<>(Map.of(view, 4));
      List<ChunkPos> expired = new ArrayList<>();

      // CHUNK_LOAD has fired but getChunkNow still has no view. The next
      // attempt sees the view, while its distant edited owner is not ready.
      CubeNetApronChunkSync.retryPendingRefreshes(pending, pos -> false, expired::add);
      assertEquals(3, pending.get(view));
      CubeNetApronChunkSync.retryPendingRefreshes(pending, pos -> false, expired::add);
      assertEquals(2, pending.get(view));

      List<ChunkPos> applied = new ArrayList<>();
      CubeNetApronChunkSync.retryPendingRefreshes(pending, pos -> {
         applied.add(pos);
         return true;
      }, expired::add);
      assertEquals(List.of(view), applied);
      assertTrue(pending.isEmpty());
      assertTrue(expired.isEmpty());
   }

   @Test
   void keepsRefreshesQueuedByChunkLoadDuringAnOverlay() {
      ChunkPos first = new ChunkPos(640, 320), neighbor = new ChunkPos(641, 320);
      Map<ChunkPos, Integer> pending = new HashMap<>(Map.of(first, 2));
      CubeNetApronChunkSync.retryPendingRefreshes(pending, pos -> {
         pending.put(first, 200);
         pending.put(neighbor, 200);
         return false;
      }, pos -> fail("newly queued refresh must retain its own budget"));
      assertEquals(Map.of(first, 200, neighbor, 200), pending);
   }

   @Test
   void reportsAnUnavailableOverlayOnceItsRetryBudgetExpires() {
      ChunkPos view = new ChunkPos(640, 320);
      Map<ChunkPos, Integer> pending = new HashMap<>(Map.of(view, 1));
      List<ChunkPos> expired = new ArrayList<>();
      CubeNetApronChunkSync.retryPendingRefreshes(pending, pos -> false, expired::add);
      assertEquals(List.of(view), expired);
      assertEquals(Map.of(view, 0), pending, "a failed view must keep packet staging blocked");
      CubeNetApronChunkSync.retryPendingRefreshes(pending, pos -> fail("failed view cannot silently retry"), expired::add);
      assertEquals(List.of(view), expired, "timeout should be reported once");
   }
}
