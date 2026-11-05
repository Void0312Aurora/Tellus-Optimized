package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CachingGeodeticOceanAuthorityTest {
   private static final double WORLD_SCALE = 1.0;
   private static final double RESOLUTION_METERS = 1_000.0;

   @Test
   void repeatedSamplesInOneResolutionCellHitFakeSourceOnce() {
      AtomicInteger calls = new AtomicInteger();
      GeodeticOceanAuthority fakeSource = (point, scale, resolution) -> {
         calls.incrementAndGet();
         return GeodeticOceanAuthority.Classification.OCEAN;
      };
      CachingGeodeticOceanAuthority cached = new CachingGeodeticOceanAuthority(
         fakeSource, 8, TimeUnit.MINUTES.toNanos(10), TimeUnit.SECONDS.toNanos(60)
      );

      cached.classify(point(12.0, 34.0), WORLD_SCALE, RESOLUTION_METERS);
      cached.classify(point(12.0001, 34.0001), WORLD_SCALE, RESOLUTION_METERS);

      assertEquals(1, calls.get());
      assertEquals(1, cached.size());
   }

   @Test
   void leastRecentlyUsedEntriesAreEvictedAtCapacity() {
      AtomicInteger calls = new AtomicInteger();
      GeodeticOceanAuthority fakeSource = (point, scale, resolution) -> {
         calls.incrementAndGet();
         return GeodeticOceanAuthority.Classification.LAND;
      };
      CachingGeodeticOceanAuthority cached = new CachingGeodeticOceanAuthority(
         fakeSource, 2, TimeUnit.MINUTES.toNanos(10), TimeUnit.SECONDS.toNanos(60)
      );

      cached.classify(point(0.0, 0.0), WORLD_SCALE, RESOLUTION_METERS);
      cached.classify(point(0.0, 2.0), WORLD_SCALE, RESOLUTION_METERS);
      cached.classify(point(0.0, 4.0), WORLD_SCALE, RESOLUTION_METERS);
      cached.classify(point(0.0, 0.0), WORLD_SCALE, RESOLUTION_METERS);

      assertEquals(4, calls.get());
      assertEquals(2, cached.size());
   }

   @Test
   void unknownEntriesExpireAndAreQueriedAgain() {
      AtomicInteger calls = new AtomicInteger();
      AtomicLong now = new AtomicLong();
      GeodeticOceanAuthority fakeSource = (point, scale, resolution) -> {
         calls.incrementAndGet();
         return GeodeticOceanAuthority.Classification.UNKNOWN;
      };
      CachingGeodeticOceanAuthority cached = new CachingGeodeticOceanAuthority(
         fakeSource,
         8,
         TimeUnit.MINUTES.toNanos(10),
         TimeUnit.SECONDS.toNanos(60),
         now::get
      );

      SphereWorldModel.GeoPoint point = point(12.0, 34.0);
      cached.classify(point, WORLD_SCALE, RESOLUTION_METERS);
      now.addAndGet(TimeUnit.SECONDS.toNanos(59));
      cached.classify(point, WORLD_SCALE, RESOLUTION_METERS);
      now.addAndGet(TimeUnit.SECONDS.toNanos(2));
      cached.classify(point, WORLD_SCALE, RESOLUTION_METERS);

      assertEquals(2, calls.get());
   }

   private static SphereWorldModel.GeoPoint point(double latitude, double longitude) {
      return new SphereWorldModel.GeoPoint(latitude, longitude, 0.0);
   }
}
