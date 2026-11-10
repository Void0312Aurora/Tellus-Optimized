package com.yucareux.tellus.world.data.geology;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Objects;

/**
 * Geology lookup whose identity is a geodetic point rather than a Minecraft
 * projection. GLiM is local and deterministic; Macrostrat is an optional,
 * finer map overlay and may be unavailable without changing the fallback.
 */
public final class SphericalGeologySource {
   private final TellusLithologySource lithology;
   private final MacrostratGeologySource macrostrat;
   private final boolean useMacrostrat;

   public SphericalGeologySource(
      TellusLithologySource lithology,
      MacrostratGeologySource macrostrat,
      boolean useMacrostrat
   ) {
      this.lithology = Objects.requireNonNull(lithology, "lithology");
      this.macrostrat = Objects.requireNonNull(macrostrat, "macrostrat");
      this.useMacrostrat = useMacrostrat;
   }

   public static SphericalGeologySource shared() {
      return SharedHolder.INSTANCE;
   }

   /** Match Earth's default; retain a spherical-only rollback without overriding the global disable. */
   static boolean configuredMacrostratEnabled() {
      return Boolean.parseBoolean(System.getProperty("tellus.macrostrat.enabled", "true"))
         && Boolean.parseBoolean(System.getProperty("tellus.sphericalGeology.macrostrat", "true"));
   }

   public Sample sample(SphereWorldModel.GeoPoint point, double worldScale, double resolutionMeters) {
      Objects.requireNonNull(point, "point");
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)
         || !(resolutionMeters > 0.0) || !Double.isFinite(resolutionMeters)) {
         return Sample.empty();
      }
      MapUnit unit = this.useMacrostrat
         ? this.macrostrat.sampleUnitAtLonLat(point.longitude(), point.latitude(), worldScale, resolutionMeters)
         : null;
      Lithology rock = this.lithology.sampleAtLonLat(point.longitude(), point.latitude());
      if (rock == Lithology.UNKNOWN) rock = Lithology.UNDEFINED;
      return new Sample(rock, unit);
   }

   public record Sample(Lithology lithology, MapUnit mapUnit) {
      public Sample {
         Objects.requireNonNull(lithology, "lithology");
      }

      static Sample empty() {
         return new Sample(Lithology.UNKNOWN, null);
      }

      public boolean available() {
         return this.mapUnit != null || (this.lithology != Lithology.UNKNOWN && this.lithology != Lithology.UNDEFINED);
      }
   }

   private static final class SharedHolder {
      private static final SphericalGeologySource INSTANCE = new SphericalGeologySource(
         TellusLithologySource.shared(),
         MacrostratGeologySource.shared(),
         configuredMacrostratEnabled()
      );

      private SharedHolder() {
      }
   }
}
