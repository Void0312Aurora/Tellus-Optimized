package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetSeamApproach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
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
   void maximumViewStagingNeverExceedsThePerTickChunkPacketBudget() {
      var delivery = new CubeNetRuntime.ClientChunkDelivery();
      assertEquals(1, delivery.beginAttempt(100, true, new net.minecraft.world.level.ChunkPos(0, 0), 33));
      assertTrue(delivery.consumeCenterPacket(100, CubeNetRuntime.MAX_CHUNK_PACKETS_PER_TICK));
      int expectedChunks = 67 * 67;
      int chunksSent = 0;
      int batches = 0;
      while (!delivery.finishedAttempt()) {
         int tick = 100 + batches;
         var batch = delivery.takeNext(tick, CubeNetRuntime.MAX_CHUNK_PACKETS_PER_TICK);
         assertTrue(batch.size() <= CubeNetRuntime.MAX_CHUNK_PACKETS_PER_TICK);
         assertTrue(delivery.packetsSentThisTick() <= CubeNetRuntime.MAX_CHUNK_PACKETS_PER_TICK);
         assertFalse(batch.isEmpty());
         chunksSent += batch.size();
         batches++;
      }
      assertEquals(expectedChunks, chunksSent);
      int budget = CubeNetRuntime.MAX_CHUNK_PACKETS_PER_TICK;
      assertEquals((expectedChunks + budget - 1) / budget, batches);
   }

   @Test
   void repeatedStagingCallsInOneTickShareTheSamePacketBudget() {
      var delivery = new CubeNetRuntime.ClientChunkDelivery();
      delivery.beginAttempt(100, true, new net.minecraft.world.level.ChunkPos(0, 0), 3);
      assertTrue(delivery.consumeCenterPacket(100, CubeNetRuntime.MAX_CHUNK_PACKETS_PER_TICK));
      assertEquals(7, delivery.takeNext(100, CubeNetRuntime.MAX_CHUNK_PACKETS_PER_TICK).size());
      assertTrue(delivery.takeNext(100, CubeNetRuntime.MAX_CHUNK_PACKETS_PER_TICK).isEmpty());
      assertEquals(8, delivery.packetsSentThisTick());
      assertEquals(8, delivery.takeNext(101, CubeNetRuntime.MAX_CHUNK_PACKETS_PER_TICK).size());
      assertEquals(8, delivery.packetsSentThisTick());
   }

   @Test
   void maximumViewChunkLoadsAreQueuedInBoundedBatches() {
      int chunkCount = 67 * 67;
      var chunks = new ArrayList<net.minecraft.world.level.ChunkPos>(chunkCount);
      for (int i = 0; i < chunkCount; i++) {
         chunks.add(new net.minecraft.world.level.ChunkPos(i, -i));
      }
      int loaded = 0;
      int batches = 0;
      while (loaded < chunks.size()) {
         var batch = CubeNetRuntime.chunkLoadBatch(chunks, loaded);
         assertFalse(batch.isEmpty());
         assertTrue(batch.size() <= 8);
         assertEquals(chunks.subList(loaded, loaded + batch.size()), batch);
         loaded += batch.size();
         batches++;
      }
      assertEquals(chunkCount, loaded);
      assertEquals((chunkCount + 7) / 8, batches);
   }

   @Test
   void runtimeMetricsSampleAtMostOncePerTickAndRetainTheWorstObservations() {
      var delivery = new CubeNetRuntime.ClientChunkDelivery();
      delivery.sampleRuntime(10, 50.0F);
      delivery.sampleRuntime(10, 100.0F);
      delivery.sampleRuntime(11, 80.0F);
      assertEquals(2, delivery.metricSamples());
      assertEquals(12.5, delivery.observedMinTps(), 1E-6);
      assertTrue(delivery.sampledPeakHeapBytes() > 0);
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
