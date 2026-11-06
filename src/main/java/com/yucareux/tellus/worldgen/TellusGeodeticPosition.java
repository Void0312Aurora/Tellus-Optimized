package com.yucareux.tellus.worldgen;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Geodetic and vertical coordinates exposed by a Tellus world generator.
 *
 * <p>The one-coordinate latitude/longitude methods are retained for the
 * equirectangular Earth projection. Generators whose planar projection needs
 * both coordinates should override the two-coordinate methods; realtime
 * sampling uses those methods.</p>
 */
public interface TellusGeodeticPosition {
   /** Returns latitude for a planar block Z when X is not needed. */
   double latitudeFromBlock(double blockZ);

   /** Returns longitude for a planar block X when Z is not needed. */
   double longitudeFromBlock(double blockX);

   /** Returns latitude for a complete planar block coordinate. */
   default double latitudeFromBlock(double blockX, double blockZ) {
      return this.latitudeFromBlock(blockZ);
   }

   /** Returns longitude for a complete planar block coordinate. */
   default double longitudeFromBlock(double blockX, double blockZ) {
      return this.longitudeFromBlock(blockX);
   }

   /** Real-world metres represented by one horizontal block at this scale. */
   double worldScale();

   /** Configured vertical terrain offset, before any runtime sea-level policy. */
   int heightOffset();

   /** Effective sea level in generator block coordinates. */
   int seaLevel();

   /**
    * Resolves a geodetic coordinate to a walkable surface block. Implementations
    * return empty when the projection is outside their canonical world or when
    * terrain data is unavailable.
    */
   Optional<BlockPos> surfacePosition(LevelHeightAccessor heightAccessor, double latitude, double longitude);

   /**
    * Whether a planar block belongs to an inland lake or pond suitable for freezing.
    * Generators without inland-water ownership return false by default.
    */
   default boolean isLakeWater(double blockX, double blockZ) {
      return false;
   }

   /** Applies the realtime/historical snow state to a loaded chunk. */
   void applyRealtimeSnowCover(WorldGenLevel level, ChunkAccess chunk);
}
