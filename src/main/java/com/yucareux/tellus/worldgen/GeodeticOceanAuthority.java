package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Objects;

/**
 * Non-blocking geodetic authority for classifying an individual spherical
 * sample as Overture ocean, mapped land, or unresolved source coverage.
 */
@FunctionalInterface
public interface GeodeticOceanAuthority {
   Classification classify(SphereWorldModel.GeoPoint point, double worldScale, double resolutionMeters);

   enum Classification {
      OCEAN,
      LAND,
      UNKNOWN
   }

   /**
    * Adapts the shared Overture water cache. A pending or failed tile is
    * deliberately UNKNOWN: terrain sampling then uses its existing
    * land-mask/DEM decision instead of waiting for source I/O.
    */
   static GeodeticOceanAuthority fromOverture(TellusOsmWaterSource source) {
      Objects.requireNonNull(source, "source");
      return (point, worldScale, resolutionMeters) -> {
         if (!(worldScale > 0.0) || !Double.isFinite(worldScale)
            || !(resolutionMeters > 0.0) || !Double.isFinite(resolutionMeters)) {
            return Classification.UNKNOWN;
         }

         try {
            // Include the sampled cell in the query envelope, while keeping
            // the request geodetic and bounded at distant LOD resolutions.
            double radiusMeters = Math.max(1.0, resolutionMeters * 0.5);
            GeodeticBounds bounds = GeodeticBounds.around(point, radiusMeters);
            TellusOsmWaterSource.WaterQueryResult query = source.waterForGeoAreaWithStatus(
               bounds, worldScale, OsmQueryMode.NON_BLOCKING);
            if (!query.complete()) {
               return Classification.UNKNOWN;
            }
            for (OsmWaterFeature feature : query.features()) {
               if (feature.oceanHint() && !feature.lineGeometry() && !feature.pointGeometry()
                  && feature.containsLonLat(point.longitude(), point.latitude())) {
                  return Classification.OCEAN;
               }
            }
            // Complete Overture coverage with no containing ocean polygon is
            // authoritative land, including mapped inland-water features.
            return Classification.LAND;
         } catch (RuntimeException unavailable) {
            // A source/cache failure must not turn the terrain hot path into a
            // hard failure. The caller's existing fallback remains authoritative.
            return Classification.UNKNOWN;
         }
      };
   }
}
