package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetSeamApproach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class CubeNetSeamApproachTest {
   @Test
   void aReadyApproachSurvivesAPlayerPauseBeyondTheLoadingBudget() {
      CompletableFuture<Void> clientReady = new CompletableFuture<>();
      assertFalse(CubeNetRuntime.preparationTimedOut(clientReady, 1200));
      clientReady.complete(null);
      assertFalse(CubeNetRuntime.preparationTimedOut(clientReady, 1201));
      assertFalse(CubeNetRuntime.preparationTimedOut(clientReady, 2400));
   }

   @Test
   void anUnfinishedApproachStillExpires() {
      assertTrue(CubeNetRuntime.preparationTimedOut(new CompletableFuture<>(), 1201));
   }

   @Test
   void failedOrCancelledPreparationCannotBecomeAnUnlimitedReadyChart() {
      CompletableFuture<Void> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("renderer failed"));
      assertTrue(CubeNetRuntime.preparationTimedOut(failed, 1201));
      CompletableFuture<Void> cancelled = new CompletableFuture<>();
      cancelled.cancel(false);
      assertTrue(CubeNetRuntime.preparationTimedOut(cancelled, 1201));
   }

   @Test
   void chunkDeliveryRetriesOnceAfterEightSecondsUntilTheClientConfirmsReceipt() {
      var delivery = new CubeNetRuntime.ClientChunkDelivery();
      assertTrue(delivery.isDue(100, true));
      assertEquals(1, delivery.markSent(100, true));
      assertFalse(delivery.isDue(259, true));
      assertTrue(delivery.isDue(260, true));
      assertEquals(2, delivery.markSent(260, true));
      assertFalse(delivery.isDue(420, true));

      var acknowledged = new CubeNetRuntime.ClientChunkDelivery();
      assertFalse(acknowledged.markClientReceived(), "a receipt cannot precede a server batch");
      assertEquals(1, acknowledged.markSent(100, true));
      assertTrue(acknowledged.markClientReceived());
      assertFalse(acknowledged.isDue(260, true));
   }

   @Test
   void olderClientsWithoutChunkReceiptKeepTheSingleSendPath() {
      var delivery = new CubeNetRuntime.ClientChunkDelivery();
      assertEquals(1, delivery.markSent(100, false));
      assertFalse(delivery.isDue(1000, false));
   }

   @Test
   void everyCutApproachPredictsTheSameChunkBeforeAndAfterThePhysicalEdge() {
      var layout = CubeNetLayout.standard(10240);
      var resolver = new CubeNetApronResolver(layout, 128);
      int cuts = 0;
      for (var face : CubeNetLayout.Face.values()) for (var side : CubeNetLayout.Side.values()) {
         if (layout.arePlanarNeighbors(face, side)) continue;
         cuts++;
         var seam = layout.seam(face, side);
         double du = side == CubeNetLayout.Side.WEST ? -1 : side == CubeNetLayout.Side.EAST ? 1 : 0;
         double dv = side == CubeNetLayout.Side.NORTH ? -1 : side == CubeNetLayout.Side.SOUTH ? 1 : 0;
         double[] direction = layout.planarVector(face, du, dv);
         float yaw = (float)Math.toDegrees(Math.atan2(-direction[0], direction[1]));
         var landing = layout.toPlanar(layout.mapAcross(face, side, 5120.5, 24.5));
         for (double inward : new double[]{128.5, 1.5, -1.5, -24.5}) {
            double u = side == CubeNetLayout.Side.WEST ? inward
               : side == CubeNetLayout.Side.EAST ? layout.faceSize() - inward : 5120.5;
            double v = side == CubeNetLayout.Side.NORTH ? inward
               : side == CubeNetLayout.Side.SOUTH ? layout.faceSize() - inward : 5120.5;
            var source = layout.toPlanarUnbounded(new CubeNetLayout.FacePoint(face, u, v));
            var found = CubeNetSeamApproach.find(layout, resolver, source.x(), source.z(), yaw, 192, 24).orElseThrow();
            assertEquals(seam, found.seam());
            assertEquals((int)Math.floor(landing.x() / 16), found.chunkX());
            assertEquals((int)Math.floor(landing.z() / 16), found.chunkZ());
            assertTrue(CubeNetSeamApproach.find(layout, resolver, source.x(), source.z(), yaw + 180, 192, 24).isEmpty());
         }
      }
      assertEquals(14, cuts);
   }
}
