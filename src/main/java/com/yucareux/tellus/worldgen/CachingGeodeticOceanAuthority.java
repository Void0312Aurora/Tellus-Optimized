package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Bounded, geodetically quantized cache for an ocean authority.
 *
 * <p>Cube-Net samples nearby points repeatedly at the same source resolution.
 * Quantizing latitude/longitude to a resolution-sized cell avoids repeating the
 * same Overture query while keeping the source's non-blocking behavior. Complete
 * Overture answers live longer than unresolved answers; a transient UNKNOWN is
 * deliberately retried after a short TTL.</p>
 */
public final class CachingGeodeticOceanAuthority implements GeodeticOceanAuthority {
   public static final int DEFAULT_MAX_ENTRIES = 8192;
   public static final long DEFAULT_KNOWN_TTL_NANOS = TimeUnit.MINUTES.toNanos(10L);
   public static final long DEFAULT_UNKNOWN_TTL_NANOS = TimeUnit.SECONDS.toNanos(60L);

   private static final double METERS_PER_DEGREE = 111_319.49079327357;
   private static final double MIN_CELL_DEGREES = 1.0E-9;

   private final GeodeticOceanAuthority delegate;
   private final int maxEntries;
   private final long knownTtlNanos;
   private final long unknownTtlNanos;
   private final LongSupplier nanoClock;
   private final LinkedHashMap<CellKey, CacheEntry> entries;

   public CachingGeodeticOceanAuthority(GeodeticOceanAuthority delegate) {
      this(delegate, DEFAULT_MAX_ENTRIES, DEFAULT_KNOWN_TTL_NANOS, DEFAULT_UNKNOWN_TTL_NANOS);
   }

   public CachingGeodeticOceanAuthority(
      GeodeticOceanAuthority delegate,
      int maxEntries,
      long knownTtlNanos,
      long unknownTtlNanos
   ) {
      this(delegate, maxEntries, knownTtlNanos, unknownTtlNanos, System::nanoTime);
   }

   CachingGeodeticOceanAuthority(
      GeodeticOceanAuthority delegate,
      int maxEntries,
      long knownTtlNanos,
      long unknownTtlNanos,
      LongSupplier nanoClock
   ) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
      if (knownTtlNanos <= 0) throw new IllegalArgumentException("knownTtlNanos must be positive");
      if (unknownTtlNanos <= 0) throw new IllegalArgumentException("unknownTtlNanos must be positive");
      this.maxEntries = maxEntries;
      this.knownTtlNanos = knownTtlNanos;
      this.unknownTtlNanos = unknownTtlNanos;
      this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
      this.entries = new LinkedHashMap<>(Math.min(maxEntries, 256), 0.75f, true);
   }

   @Override
   public Classification classify(SphereWorldModel.GeoPoint point, double worldScale, double resolutionMeters) {
      if (point == null || !(worldScale > 0.0) || !Double.isFinite(worldScale)
         || !(resolutionMeters > 0.0) || !Double.isFinite(resolutionMeters)) {
         return Classification.UNKNOWN;
      }

      CellKey key = CellKey.from(point, worldScale, resolutionMeters);
      long now = this.nanoClock.getAsLong();
      CacheEntry cached;
      synchronized (this.entries) {
         cached = this.entries.get(key);
         if (cached != null) {
            if (!cached.expired(now)) return cached.classification;
            this.entries.remove(key);
         }
      }

      Classification classification;
      try {
         classification = Objects.requireNonNullElse(
            this.delegate.classify(point, worldScale, resolutionMeters), Classification.UNKNOWN
         );
      } catch (RuntimeException unavailable) {
         // A cache wrapper must preserve the source's non-blocking fallback.
         classification = Classification.UNKNOWN;
      }

      long completedAt = this.nanoClock.getAsLong();
      synchronized (this.entries) {
         // Another caller may have populated this cell while the source was queried.
         CacheEntry raced = this.entries.get(key);
         if (raced != null && !raced.expired(completedAt)) return raced.classification;
         this.entries.put(key, new CacheEntry(classification, completedAt, ttlNanos(classification)));
         while (this.entries.size() > this.maxEntries) {
            this.entries.remove(this.entries.keySet().iterator().next());
         }
         return classification;
      }
   }

   /** Number of live entries, exposed package-locally for deterministic tests. */
   int size() {
      synchronized (this.entries) {
         return this.entries.size();
      }
   }

   private long ttlNanos(Classification classification) {
      return classification == Classification.UNKNOWN ? this.unknownTtlNanos : this.knownTtlNanos;
   }

   private record CacheEntry(Classification classification, long createdNanos, long ttlNanos) {
      boolean expired(long nowNanos) {
         long age = nowNanos - this.createdNanos;
         return age < 0L || age >= this.ttlNanos;
      }
   }

   private record CellKey(long latitudeCell, long longitudeCell, long worldScaleBits, long resolutionBits) {
      static CellKey from(SphereWorldModel.GeoPoint point, double worldScale, double resolutionMeters) {
         double latitudeCellDegrees = Math.max(MIN_CELL_DEGREES, resolutionMeters / METERS_PER_DEGREE);
         double longitudeMetersPerDegree = METERS_PER_DEGREE
            * Math.max(0.01, Math.cos(Math.toRadians(point.latitude())));
         double longitudeCellDegrees = Math.max(MIN_CELL_DEGREES, resolutionMeters / longitudeMetersPerDegree);
         long latitudeCell = (long)Math.floor((point.latitude() + 90.0) / latitudeCellDegrees);
         long longitudeCell = (long)Math.floor((point.longitude() + 180.0) / longitudeCellDegrees);
         return new CellKey(
            latitudeCell,
            longitudeCell,
            Double.doubleToLongBits(worldScale),
            Double.doubleToLongBits(resolutionMeters)
         );
      }
   }
}
