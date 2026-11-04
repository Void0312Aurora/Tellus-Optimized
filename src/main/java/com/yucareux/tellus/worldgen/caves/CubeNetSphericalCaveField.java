package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Objects;

/**
 * A deterministic 3D field evaluated in the physical spherical shell. The
 * field is deliberately independent of Minecraft's version-specific noise
 * object classes: the same seed and ECEF-like coordinates produce the same
 * result on every supported loader.
 */
public final class CubeNetSphericalCaveField {
   private static final double CAVE_THRESHOLD = 0.52;
   private static final double ORE_THRESHOLD = 0.74;
   private static final double GEOLOGY_THRESHOLD = 0.78;

   private final long worldSeed;

   public CubeNetSphericalCaveField(long worldSeed) { this.worldSeed = worldSeed; }

   public Column column(SphereWorldModel.GeoPoint point, double worldScale) {
      Coordinates c = coordinates(point, 0, worldScale);
      double radius = Math.sqrt(c.x*c.x + c.y*c.y + c.z*c.z);
      return new Column(c.x/radius, c.y/radius, c.z/radius, radius);
   }

   public double caveSignal(Position position) {
      return valueNoise(position.x/96.0, position.y/96.0, position.z/96.0, this.worldSeed ^ 0x4f1bbcdcaa6f4d13L) * .52
         + valueNoise(position.x/36.0, position.y/36.0, position.z/36.0, this.worldSeed ^ 0x9e3779b97f4a7c15L) * .33
         + valueNoise(position.x/14.0, position.y/14.0, position.z/14.0, this.worldSeed ^ 0xd1b54a32d192ed03L) * .15;
   }

   public Cavity cavity(Position position, int yAboveSea, int depth, int y, int minY) {
      if (caveSignal(position) <= CAVE_THRESHOLD) return Cavity.SOLID;
      if (y <= minY + 8) return Cavity.LAVA;
      if (depth < 12 || yAboveSea > 0) return Cavity.AIR;
      double flooded = valueNoise(position.x/48.0, position.y/48.0, position.z/48.0, this.worldSeed ^ 0x632be59bd9b4e019L);
      return flooded > 0.3 ? Cavity.WATER : flooded > 0.18 ? Cavity.SOLID : Cavity.AIR;
   }

   public Feature rockFeature(Position position, int depth, boolean ores, boolean geology) {
      if ((!ores && !geology) || depth < 3) return Feature.NONE;
      double vein = valueNoise(position.x/20.0, position.y/20.0, position.z/20.0, this.worldSeed ^ 0x632be59bd9b4e019L);
      double detail = valueNoise(position.x/7.0, position.y/7.0, position.z/7.0, this.worldSeed ^ 0x8cb92baa5f2d4a91L);
      if (ores && depth >= 10 && depth <= 220 && vein*.72 + detail*.28 > ORE_THRESHOLD)
         return (((long)Math.floor(Math.abs(position.x) + Math.abs(position.y) + Math.abs(position.z))) & 1L) == 0
            ? Feature.COPPER_ORE : Feature.DEEPSLATE_IRON_ORE;
      double geologyNoise = valueNoise(position.x/42.0, position.y/42.0, position.z/42.0, this.worldSeed ^ 0x2f6e2b1aa56d3f17L);
      if (geology && geologyNoise > GEOLOGY_THRESHOLD)
         return (mix(this.worldSeed ^ 0x51ed2705L, Math.round(position.x), Math.round(position.y), Math.round(position.z)) & 1L) == 0
            ? Feature.GRANITE : Feature.TUFF;
      return Feature.NONE;
   }

   public static Feature sample(SphereWorldModel.GeoPoint point, int depthBlocks,
      double worldScale, long worldSeed) {
      Objects.requireNonNull(point, "point");
      if (depthBlocks < 0) throw new IllegalArgumentException("depthBlocks");
      Coordinates c = coordinates(point, depthBlocks, worldScale);
      double broad = valueNoise(c.x / 96.0, c.y / 96.0, c.z / 96.0, worldSeed ^ 0x4f1bbcdcaa6f4d13L);
      double medium = valueNoise(c.x / 36.0, c.y / 36.0, c.z / 36.0, worldSeed ^ 0x9e3779b97f4a7c15L);
      double detail = valueNoise(c.x / 14.0, c.y / 14.0, c.z / 14.0, worldSeed ^ 0xd1b54a32d192ed03L);
      if (broad * 0.52 + medium * 0.33 + detail * 0.15 > CAVE_THRESHOLD) return Feature.CAVE;
      if (depthBlocks >= 10 && depthBlocks <= 220) {
         double vein = valueNoise(c.x / 20.0, c.y / 20.0, c.z / 20.0, worldSeed ^ 0x632be59bd9b4e019L);
         double detailVein = valueNoise(c.x / 7.0, c.y / 7.0, c.z / 7.0, worldSeed ^ 0x8cb92baa5f2d4a91L);
         if (vein * 0.72 + detailVein * 0.28 > ORE_THRESHOLD) {
            return (mix(worldSeed, Math.round(c.x), Math.round(c.y), Math.round(c.z)) & 1L) == 0L
               ? Feature.COPPER_ORE : Feature.DEEPSLATE_IRON_ORE;
         }
      }
      if (depthBlocks >= 3) {
         double geology = valueNoise(c.x / 42.0, c.y / 42.0, c.z / 42.0, worldSeed ^ 0x2f6e2b1aa56d3f17L);
         if (geology > GEOLOGY_THRESHOLD) {
            return (mix(worldSeed ^ 0x51ed2705L, Math.round(c.x), Math.round(c.y), Math.round(c.z)) & 1L) == 0L
               ? Feature.GRANITE : Feature.TUFF;
         }
      }
      return Feature.NONE;
   }

   public static double caveSignal(SphereWorldModel.GeoPoint point, int depthBlocks,
      double worldScale, long worldSeed) {
      Objects.requireNonNull(point, "point");
      Coordinates c = coordinates(point, depthBlocks, worldScale);
      return valueNoise(c.x / 96.0, c.y / 96.0, c.z / 96.0, worldSeed ^ 0x4f1bbcdcaa6f4d13L) * 0.52
         + valueNoise(c.x / 36.0, c.y / 36.0, c.z / 36.0, worldSeed ^ 0x9e3779b97f4a7c15L) * 0.33
         + valueNoise(c.x / 14.0, c.y / 14.0, c.z / 14.0, worldSeed ^ 0xd1b54a32d192ed03L) * 0.15;
   }

   private static Coordinates coordinates(SphereWorldModel.GeoPoint point, int depthBlocks, double worldScale) {
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) throw new IllegalArgumentException("worldScale");
      double lat = Math.toRadians(point.latitude()), lon = Math.toRadians(point.longitude());
      double cosLat = Math.cos(lat), radius = SphereWorldModel.EARTH_RADIUS_METERS / worldScale - depthBlocks;
      return new Coordinates(cosLat * Math.cos(lon) * radius, Math.sin(lat) * radius,
         cosLat * Math.sin(lon) * radius);
   }

   private static double valueNoise(double x, double y, double z, long seed) {
      long x0 = (long)Math.floor(x), y0 = (long)Math.floor(y), z0 = (long)Math.floor(z);
      double tx = fade(x - x0), ty = fade(y - y0), tz = fade(z - z0);
      double c000 = unit(mix(seed, x0, y0, z0)), c100 = unit(mix(seed, x0+1, y0, z0));
      double c010 = unit(mix(seed, x0, y0+1, z0)), c110 = unit(mix(seed, x0+1, y0+1, z0));
      double c001 = unit(mix(seed, x0, y0, z0+1)), c101 = unit(mix(seed, x0+1, y0, z0+1));
      double c011 = unit(mix(seed, x0, y0+1, z0+1)), c111 = unit(mix(seed, x0+1, y0+1, z0+1));
      return lerp(lerp(lerp(c000,c100,tx),lerp(c010,c110,tx),ty),
         lerp(lerp(c001,c101,tx),lerp(c011,c111,tx),ty),tz);
   }

   private static double fade(double v) { return v*v*v*(v*(v*6.0-15.0)+10.0); }
   private static double lerp(double a, double b, double t) { return a + t*(b-a); }
   private static double unit(long v) { return ((v >>> 11) * 1.1102230246251565E-16) * 2.0 - 1.0; }
   static long mix(long seed, long x, long y, long z) {
      long v = seed ^ x*0x9e3779b97f4a7c15L ^ y*0xc2b2ae3d27d4eb4fL ^ z*0x165667b19e3779f9L;
      v ^= v >>> 30; v *= 0xbf58476d1ce4e5b9L; v ^= v >>> 27; v *= 0x94d049bb133111ebL;
      return v ^ v >>> 31;
   }

   private record Coordinates(double x, double y, double z) { }
   public record Column(double x, double y, double z, double radius) {
      public Position atHeight(int yAboveSea) {
         double r = this.radius + yAboveSea;
         return new Position(this.x*r, this.y*r, this.z*r);
      }
   }
   public record Position(double x, double y, double z) { }
   public enum Cavity { SOLID, AIR, WATER, LAVA }
   public enum Feature { NONE, CAVE, COPPER_ORE, DEEPSLATE_IRON_ORE, GRANITE, TUFF }
}
