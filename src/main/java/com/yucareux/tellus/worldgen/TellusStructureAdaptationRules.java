package com.yucareux.tellus.worldgen;

/**
 * Minecraft-free copy of Earth's post-start underground and monument rules.
 *
 * <p>Bounds and sampler arguments use the caller's placement coordinates. Only
 * {@code earthCenterX/Z} use the converted Earth projection: they determine the
 * random depth, never the sampling footprint. World/profile/owner selection,
 * water-column normalization and application to pieces belong to the caller.
 * Cube callers pass {@code usesTerrainShell=false}.</p>
 */
public final class TellusStructureAdaptationRules {
   private static final Decision KEEP = new Decision(Outcome.KEEP, 0);
   private static final Decision INVALIDATE = new Decision(Outcome.INVALIDATE, 0);

   private TellusStructureAdaptationRules() {
   }

   public enum UndergroundKind {
      STRONGHOLD, NORMAL_MINESHAFT, MESA_MINESHAFT, ANCIENT_CITY, TRIAL_CHAMBERS
   }

   public enum Outcome {
      KEEP, MOVE, INVALIDATE
   }

   /** Inclusive block bounds, with Minecraft BoundingBox's upper-middle center. */
   public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
      public int centerX() {
         return this.minX + (this.maxX - this.minX + 1) / 2;
      }

      public int centerY() {
         return this.minY + this.ySpan() / 2;
      }

      public int centerZ() {
         return this.minZ + (this.maxZ - this.minZ + 1) / 2;
      }

      public int ySpan() {
         return this.maxY - this.minY + 1;
      }
   }

   /** KEEP includes absent/invalid input starts; INVALIDATE is a newly rejected start. */
   public record Decision(Outcome outcome, int offsetY) {
   }

   /** inOceanColumn means hasWater && isOcean && waterSurface > terrainSurface. */
   public record CenterColumn(int terrainSurface, boolean inOceanColumn) {
   }

   @FunctionalInterface
   public interface CenterColumnSampler {
      CenterColumn sample(int x, int z);
   }

   @FunctionalInterface
   public interface SurfaceHeightSampler {
      int sample(int x, int z);
   }

   /** Returns positive normalized ocean depth, or zero for a dry/non-ocean column. */
   @FunctionalInterface
   public interface WaterDepthSampler {
      int sample(int x, int z);
   }

   /**
    * Preserves Earth's lazy sampling order. Invalid starts and non-shell mesa
    * mineshafts sample nothing. Strongholds first sample their center column,
    * then sample the footprint only if retargeting is needed. Cities/chambers
    * always sample center then footprint before testing their top-cover rule.
    * Mineshafts use the surface sampler for both center and footprint.
    * Both world Y limits are inclusive.
    */
   public static Decision underground(
      UndergroundKind kind, boolean validStart, boolean usesTerrainShell, Bounds box,
      int earthCenterX, int earthCenterZ, long worldSeed, int worldMinY, int worldMaxY,
      CenterColumnSampler centerColumns, SurfaceHeightSampler surfaces
   ) {
      if (!validStart || (kind == UndergroundKind.MESA_MINESHAFT && !usesTerrainShell)) {
         return KEEP;
      }

      int terrainSurface;
      if (kind == UndergroundKind.NORMAL_MINESHAFT || kind == UndergroundKind.MESA_MINESHAFT) {
         terrainSurface = surfaces.sample(box.centerX(), box.centerZ());
      } else {
         CenterColumn column = centerColumns.sample(box.centerX(), box.centerZ());
         terrainSurface = column.terrainSurface();
         if (kind == UndergroundKind.STRONGHOLD
            && !(usesTerrainShell || column.inOceanColumn() || box.centerY() > terrainSurface - 20)) {
            return KEEP;
         }
      }

      int lowestSurface = lowestSurface(box, surfaces);
      if ((kind == UndergroundKind.ANCIENT_CITY || kind == UndergroundKind.TRIAL_CHAMBERS)
         && !usesTerrainShell && box.maxY() <= lowestSurface - 8) {
         return KEEP;
      }

      int topCover = switch (kind) {
         case NORMAL_MINESHAFT -> 14;
         case MESA_MINESHAFT -> 4;
         default -> 8;
      };
      // VanillaStructurePlacement uses max(worldMinY + 4, minimumStructureY).
      // The protective capsule is one bedrock plus four stone blocks thick.
      int minOffset = Math.max(worldMinY + 4, worldMinY + 5) - box.minY();
      int maxOffset = Math.min(worldMaxY - topCover, lowestSurface - topCover) - box.maxY();
      if (minOffset > maxOffset) {
         return INVALIDATE;
      }

      int depth = targetDepth(kind, earthCenterX, earthCenterZ, worldSeed);
      return shifted(clamp(terrainSurface - depth - box.centerY(), minOffset, maxOffset));
   }

   /** Full Earth viability test followed by its sea-level and world-bound offset. */
   public static Decision monument(
      boolean validStart, boolean enableWater, Bounds box, int seaLevel,
      int worldMinY, int worldMaxY, WaterDepthSampler depths
   ) {
      if (!validStart) {
         return KEEP;
      }
      if (enableWater && !monumentViable(box, depths)) {
         return INVALIDATE;
      }
      int targetTopY = seaLevel - 2;
      int desiredMinY = targetTopY - box.ySpan() + 1;
      int offset = clamp(desiredMinY - box.minY(), worldMinY + 1 - box.minY(), worldMaxY - box.maxY());
      return box.maxY() + offset > targetTopY ? INVALIDATE : shifted(offset);
   }

   private static int lowestSurface(Bounds box, SurfaceHeightSampler surfaces) {
      int lowest = Integer.MAX_VALUE;
      for (int z = box.minZ(); ; z = Math.min(z + 8, box.maxZ())) {
         for (int x = box.minX(); ; x = Math.min(x + 8, box.maxX())) {
            lowest = Math.min(lowest, surfaces.sample(x, z));
            if (x == box.maxX()) {
               break;
            }
         }
         if (z == box.maxZ()) {
            return lowest;
         }
      }
   }

   private static boolean monumentViable(Bounds box, WaterDepthSampler depths) {
      int minX = box.minX() - 8;
      int maxX = box.maxX() + 8;
      int minZ = box.minZ() - 8;
      int maxZ = box.maxZ() + 8;
      int requiredCoreDepth = Math.max(19, box.ySpan() - 4);
      int total = 0;
      int ocean = 0;
      int deepOcean = 0;
      int core = 0;
      for (int z = minZ; ; z = Math.min(z + 8, maxZ)) {
         for (int x = minX; ; x = Math.min(x + 8, maxX)) {
            int depth = depths.sample(x, z);
            total++;
            if (depth > 0) {
               ocean++;
            }
            if (depth >= 12) {
               deepOcean++;
            }
            if (x >= box.minX() + 8 && x <= box.maxX() - 8
               && z >= box.minZ() + 8 && z <= box.maxZ() - 8) {
               core++;
               if (depth <= 0 || depth < requiredCoreDepth) {
                  return false;
               }
            }
            if (x == maxX) {
               break;
            }
         }
         if (z == maxZ) {
            return core > 0 && (double)ocean / total >= 0.85 && (double)deepOcean / total >= 0.7;
         }
      }
   }

   private static int targetDepth(UndergroundKind kind, int earthX, int earthZ, long worldSeed) {
      int seedY;
      long salt;
      int base;
      int range;
      switch (kind) {
         case STRONGHOLD -> {
            seedY = 19; salt = 8927292519556160640L; base = 42; range = 17;
         }
         case NORMAL_MINESHAFT -> {
            seedY = 14; salt = 2115183054018638465L; base = 30; range = 21;
         }
         case MESA_MINESHAFT -> {
            seedY = 14; salt = 2115183054018638465L; base = 8; range = 25;
         }
         case ANCIENT_CITY -> {
            seedY = 23; salt = 7099176517079344131L; base = 92; range = 29;
         }
         case TRIAL_CHAMBERS -> {
            seedY = 17; salt = 4235900233171027605L; base = 72; range = 25;
         }
         default -> throw new AssertionError(kind);
      }
      // Deliberately keep the int overflow in x * 3129871 and the signed shift.
      long seed = earthX * 3129871 ^ earthZ * 116129781L ^ seedY;
      seed = seed * seed * 42317861L + seed * 11L;
      return base + seededRandomInt((seed >> 16) ^ worldSeed ^ salt, range);
   }

   /** Earth's allocation-free java.util.Random(seed).nextInt(bound). */
   private static int seededRandomInt(long seed, int bound) {
      long state = (seed ^ 25214903917L) & ((1L << 48) - 1);
      int bits;
      int value;
      do {
         state = (state * 25214903917L + 11L) & ((1L << 48) - 1);
         bits = (int)(state >>> 17);
         value = bits % bound;
      } while (bits - value + (bound - 1) < 0);
      return value;
   }

   // Preserve Mth.clamp's branch order even for an inverted monument interval.
   private static int clamp(int value, int min, int max) {
      return value < min ? min : Math.min(value, max);
   }

   private static Decision shifted(int offset) {
      return offset == 0 ? KEEP : new Decision(Outcome.MOVE, offset);
   }
}
