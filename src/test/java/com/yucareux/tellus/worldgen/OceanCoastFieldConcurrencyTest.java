package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.ForwardingLoadingCache;
import com.yucareux.tellus.world.data.osm.OsmWaterTile;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Cached empty vector tiles isolate cache lifecycle from network and terrain quality. */
@Timeout(15)
class OceanCoastFieldConcurrencyTest {
   @TempDir static Path gameDir;
   private static final java.util.Map<String, String> previous = new java.util.HashMap<>();

   @BeforeAll
   static void bootstrap() {
      for (String name : new String[]{"tellus.gameDir", "tellus.configDir", "tellus.overture.release"}) {
         previous.put(name, System.getProperty(name));
      }
      System.setProperty("tellus.gameDir", gameDir.toString());
      System.setProperty("tellus.configDir", gameDir.resolve("config").toString());
      System.setProperty("tellus.overture.release", "2026-06-17.0");
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @AfterAll
   static void restoreProperties() {
      previous.forEach((name, value) -> {
         if (value == null) System.clearProperty(name);
         else System.setProperty(name, value);
      });
   }

   @Test
   void concurrentRequestsBuildOneTileAndReleaseTheInFlightEntry() throws Exception {
      AtomicInteger lookups = new AtomicInteger();
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      OceanCoastField coast = coast(() -> {
         if (lookups.incrementAndGet() == 1) {
            entered.countDown();
            await(release);
         }
      }, 0);
      var workers = Executors.newFixedThreadPool(8);
      try {
         CountDownLatch ready = new CountDownLatch(8);
         CountDownLatch start = new CountDownLatch(1);
         var results = new ArrayList<java.util.concurrent.Future<OceanCoastSample>>();
         for (int i = 0; i < 8; i++) {
            results.add(workers.submit(() -> {
               ready.countDown();
               await(start);
               return coast.sample(32, 32);
            }));
         }
         assertTrue(ready.await(5, TimeUnit.SECONDS));
         start.countDown();
         assertTrue(entered.await(5, TimeUnit.SECONDS));
         release.countDown();
         for (var result : results) assertTrue(result.get(5, TimeUnit.SECONDS).complete());
         assertEquals(1, lookups.get());
         assertTrue(((java.util.Map<?, ?>)field(coast, "inFlight")).isEmpty());
      } finally {
         release.countDown();
         workers.shutdownNow();
      }
   }

   @Test
   void clearDuringBuildCannotRepopulateEitherCacheWithAnOldTile() throws Exception {
      AtomicInteger lookups = new AtomicInteger();
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      OceanCoastField coast = coast(() -> {
         if (lookups.incrementAndGet() == 1) {
            entered.countDown();
            await(release);
         }
      }, 0);
      var worker = Executors.newSingleThreadExecutor();
      try {
         var old = worker.submit(() -> coast.sample(32, 32));
         assertTrue(entered.await(5, TimeUnit.SECONDS));
         coast.clear();
         release.countDown();
         assertTrue(old.get(5, TimeUnit.SECONDS).complete());
         assertEquals(0, ((Cache<?, ?>)field(coast, "cache")).size());
         var recent = (AtomicReferenceArray<?>)field(coast, "recent");
         for (int i = 0; i < recent.length(); i++) assertNull(recent.get(i));
         assertTrue(coast.sample(32, 32).complete());
         assertEquals(2, lookups.get());
         coast.sample(33, 33);
         assertEquals(2, lookups.get());
      } finally {
         release.countDown();
         worker.shutdownNow();
      }
   }

   @Test
   void failedBuildDoesNotPreventRetry() throws Exception {
      AtomicInteger lookups = new AtomicInteger();
      OceanCoastField coast = coast(() -> {
         if (lookups.incrementAndGet() == 1) throw new IllegalStateException("fixture failure");
      }, 0);
      assertThrows(IllegalStateException.class, () -> coast.sample(32, 32));
      assertTrue(((java.util.Map<?, ?>)field(coast, "inFlight")).isEmpty());
      assertTrue(coast.sample(32, 32).complete());
      assertEquals(2, lookups.get());
   }

   @Test
   void coarseWarmupCoversNegativeCoordinatesAndReusesTheSampledTiles() throws Exception {
      AtomicInteger lookups = new AtomicInteger();
      OceanCoastField coast = coast(lookups::incrementAndGet, 3);
      coast.warmArea(-1, -1, 1, 1);
      assertEquals(4, lookups.get());
      coast.sample(-1, -1);
      coast.sample(0, 0);
      coast.sample(1, 1);
      assertEquals(4, lookups.get());
   }

   private static OceanCoastField coast(Runnable lookup, int cellShift) throws Exception {
      TellusOsmWaterSource source = new TellusOsmWaterSource();
      set(source, "initialized", true);
      set(source, "available", true);
      set(source, "minZoom", 0);
      set(source, "maxZoom", 0);
      var delegate = CacheBuilder.newBuilder().build(new CacheLoader<Object, OsmWaterTile>() {
         @Override public OsmWaterTile load(Object key) { return OsmWaterTile.empty(); }
      });
      set(source, "cache", new ForwardingLoadingCache.SimpleForwardingLoadingCache<Object, OsmWaterTile>(delegate) {
         @Override public OsmWaterTile getIfPresent(Object key) {
            lookup.run();
            return OsmWaterTile.empty();
         }
      });
      return new OceanCoastField(source, 1.0, 16, (x, z) -> new OceanCoastField.BathymetrySample(true, 16), cellShift);
   }

   private static Object field(Object target, String name) throws Exception {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
   }

   private static void set(Object target, String name, Object value) throws Exception {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
   }

   private static void await(CountDownLatch latch) {
      try {
         if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture timed out");
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         throw new AssertionError(error);
      }
   }
}
