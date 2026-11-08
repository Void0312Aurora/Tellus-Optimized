package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy.Decision;
import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy.Material;
import com.yucareux.tellus.worldgen.MountainSurfaceRules.MountainForm;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static com.yucareux.tellus.worldgen.CubeNetSurfacePolicy.Material.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Freezes the native Cube cover rules read before the shared-kernel migration. */
class CubeNetSurfaceKernelMigrationTest {
   // All ESA cover codes, including no-data and water, plus an unrecognized code.
   private static final int[] COVERS = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 100, 123456};
   private static final byte[] CLIMATES = {0, 1, 2, 3, 4, 5, Byte.MIN_VALUE};
   private static final int METADATA_SAMPLES_PER_COLUMN = 3;
   private static final Method COVER_DRIVEN_PALETTE = coverDrivenPaletteMethod();
   private static final Metadata[] METADATA = {
      new Metadata(0, Double.NaN, MountainForm.NONE),
      new Metadata(0, -0.0, MountainForm.RIDGE),
      new Metadata(1, 0.0, MountainForm.CLIFF_FACE),
      new Metadata(2, Math.nextDown(35.0), MountainForm.STEEP_ROCKY_SLOPE),
      new Metadata(3, 35.0, MountainForm.TALUS_SLOPE),
      new Metadata(4, 40.0, MountainForm.VALLEY_WALL),
      new Metadata(5, Math.nextUp(40.0), MountainForm.SNOWFIELD),
      new Metadata(8, 65.0, MountainForm.GLACIER_BASIN),
      new Metadata(12, 90.0, MountainForm.DRAINAGE_CHUTE),
      new Metadata(-1, Double.POSITIVE_INFINITY, MountainForm.ALPINE_MEADOW)
   };

   @Test
   void everyRealRollMatchesTheFrozenNativeCubeRules() throws ReflectiveOperationException {
      Coordinate[] coordinates = coordinatesForEveryRoll();
      int alpine = MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA;
      int[] zones = {alpine - 1, alpine, alpine + 1};
      long columns = 0;
      long mismatches = 0;
      String firstMismatch = null;
      for (int cover : COVERS) {
         for (byte climate : CLIMATES) {
            for (int zone : zones) {
               for (int roll = 0; roll < coordinates.length; roll++) {
                  Coordinate coordinate = coordinates[roll];
                  // Rotate metadata independently of the cover/climate/height decisions.
                  // Each matrix row visits every form and slope sample over the 100 rolls.
                  for (int sample = 0; sample < METADATA_SAMPLES_PER_COLUMN; sample++) {
                     Metadata metadata = METADATA[(roll + sample) % METADATA.length];
                     Decision expected = oldCoverRules(cover, climate, zone, roll, metadata);
                     Decision actual = invokeCover(cover, climate, zone, coordinate, metadata);
                     columns++;
                     // Record equality checks all six fields, including NaN and signed zero.
                     if (!expected.equals(actual)) {
                        mismatches++;
                        if (firstMismatch == null) {
                           firstMismatch = "cover=" + cover + ", climate=" + climate
                              + ", zone=" + zone + ", roll=" + roll + ", coordinate=" + coordinate
                              + ", metadata=" + metadata + ", expected=" + expected + ", actual=" + actual;
                        }
                     }
                  }
               }
            }
         }
      }
      System.out.printf("CUBE_COVER_KERNEL_PARITY columns=%d mismatches=%d%n", columns, mismatches);
      assertEquals(81_900L, columns, "The full bounded matrix must run");
      assertTrue(columns < 100_000L, "Keep direct cover invocations bounded");
      assertEquals(0L, mismatches, firstMismatch == null ? "All Cube cover decisions match" : firstMismatch);
   }

   @Test
   void missingSlopeEarthEquivalentFallbackKeepsCubeMaterialAndDepthWithoutSourceIo() {
      Coordinate[] coordinates = coordinatesForEveryRoll();
      var surface = new SphereChunkSampler.SurfaceData(0.0, MountainSurfaceRules.ESA_GRASSLAND);
      assertTrue(Double.isNaN(surface.neighbourhood().gradient().slopeDegrees()));
      Metadata metadata = new Metadata(0, Double.NaN, MountainForm.NONE);
      // Paired literal boundaries exercise the callback's Cube-specific palette materialization.
      int[][] cases = {
         {10, 5, 64}, {10, 5, 65},
         {30, 2, 64}, {30, 2, 65},
         {50, 0, 44}, {50, 0, 45},
         {60, 2, 54}, {60, 2, 55},
         {90, 0, 39}, {90, 0, 40},
         {95, 0, 0}, {123456, Byte.MIN_VALUE, 99}
      };
      for (int[] fixture : cases) {
         int cover = fixture[0];
         byte climate = (byte)fixture[1];
         int roll = fixture[2];
         Coordinate coordinate = coordinates[roll];
         // Missing slope short-circuits both Overture and deepslate checks. At height zero
         // these covers have no mountain/snow override; NONE/DEFAULT skips other palettes.
         // A deliberately null point also fails before source access if Overture is attempted.
         Decision actual = CubeNetSurfacePolicy.resolveEarthEquivalent(
            null, surface, 1.0, 7L, cover, climate, 0, 0, 0,
            CubeNetSurfacePolicy.Shoreline.NONE, CubeNetSurfacePolicy.BiomeSurface.DEFAULT,
            coordinate.x(), coordinate.z());
         assertEquals(oldCoverRules(cover, climate, 0, roll, metadata), actual,
            "cover=" + cover + ", climate=" + climate + ", roll=" + roll);
      }
   }

   private static Method coverDrivenPaletteMethod() {
      try {
         Method method = CubeNetSurfacePolicy.class.getDeclaredMethod("coverDrivenPalette",
            int.class, byte.class, int.class, int.class, int.class,
            int.class, double.class, MountainForm.class);
         method.setAccessible(true);
         return method;
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("The private deterministic Cube cover seam must remain testable", exception);
      }
   }

   private static Decision invokeCover(int cover, byte climate, int zone, Coordinate coordinate,
      Metadata metadata) throws ReflectiveOperationException {
      return (Decision)COVER_DRIVEN_PALETTE.invoke(null, cover, climate, zone,
         coordinate.x(), coordinate.z(), metadata.slopeDiff(), metadata.slopeDegrees(), metadata.form());
   }

   private static Coordinate[] coordinatesForEveryRoll() {
      Coordinate[] coordinates = new Coordinate[100];
      int selected = 0;
      // Bounded spread-out Earth coordinates sample the real clustered roll, without mocking it.
      for (int candidate = 0; candidate < 16_384 && selected < coordinates.length; candidate++) {
         int x = (candidate % 128 - 64) * 137;
         int z = (candidate / 128 - 64) * 149;
         int roll = TellusSurfaceVariantRoll.percent(x, z, 23L);
         assertTrue(roll >= 0 && roll < 100, "Real percent roll is in [0,99]");
         if (coordinates[roll] == null) {
            coordinates[roll] = new Coordinate(x, z);
            selected++;
         }
      }
      assertEquals(100, selected, "Bounded coordinate enumeration must select every real percent roll");
      for (int roll = 0; roll < coordinates.length; roll++) {
         Coordinate coordinate = coordinates[roll];
         assertEquals(roll, TellusSurfaceVariantRoll.percent(coordinate.x(), coordinate.z(), 23L),
            "Selected coordinate must produce the intended roll");
      }
      return coordinates;
   }

   /**
    * Literal pre-migration coverDrivenPalette rules, captured on 2026-11-08.
    * Numeric ESA codes, alpine cutoff 200, thresholds, native Cube materials and depths
    * are deliberately frozen here. No shared decision kernel or production palette helper
    * participates in the oracle; the caller supplies its independently enumerated real roll.
    */
   private static Decision oldCoverRules(int coverClass, byte climateGroup, int zoneHeight,
      int roll, Metadata metadata) {
      return switch (coverClass) {
         case 10 -> climateGroup != 4 && climateGroup != 5
            ? climateGroup == 2
               ? (roll < 70 ? oldDecision(COARSE_DIRT, DIRT, 4, metadata)
                  : oldDecision(GRASS, DIRT, 4, metadata))
               : climateGroup == 1
                  ? (roll < 35 ? oldDecision(ROOTED_DIRT, DIRT, 4, metadata)
                     : oldDecision(GRASS, DIRT, 4, metadata))
                  : (roll < 20 ? oldDecision(ROOTED_DIRT, DIRT, 4, metadata)
                     : oldDecision(GRASS, DIRT, 4, metadata))
            : (roll < 65 ? oldDecision(PODZOL, DIRT, 4, metadata)
               : oldDecision(GRASS, DIRT, 4, metadata));
         case 20 -> zoneHeight >= 200 && roll < 70
            ? oldDecision(GRAVEL, STONE, 4, metadata)
            : climateGroup == 2
               ? oldDecision(COARSE_DIRT, DIRT, 4, metadata)
               : climateGroup != 4 && climateGroup != 5
                  ? (roll < 40 ? oldDecision(COARSE_DIRT, DIRT, 4, metadata)
                     : oldDecision(GRASS, DIRT, 4, metadata))
                  : oldDecision(PODZOL, DIRT, 4, metadata);
         case 30 -> zoneHeight >= 200 && roll < 35
            ? oldDecision(GRAVEL, STONE, 4, metadata)
            : climateGroup == 2
               ? (roll < 65 ? oldDecision(COARSE_DIRT, DIRT, 4, metadata)
                  : oldDecision(GRASS, DIRT, 4, metadata))
               : climateGroup != 4 && climateGroup != 5
                  ? oldDecision(GRASS, DIRT, 4, metadata)
                  : (roll < 40 ? oldDecision(PODZOL, DIRT, 4, metadata)
                     : oldDecision(GRASS, DIRT, 4, metadata));
         case 40 -> climateGroup == 2
            ? oldDecision(COARSE_DIRT, DIRT, 4, metadata)
            : oldDecision(roll < 65 ? ROOTED_DIRT : GRASS, DIRT, 4, metadata);
         case 50 -> roll < 45
            ? oldDecision(GRAVEL, STONE, 4, metadata)
            : oldDecision(STONE, STONE, 12, metadata);
         case 60 -> zoneHeight >= 200
            ? oldDecision(STONE, STONE, 12, metadata)
            : climateGroup == 2
               ? (roll < 55 ? oldDecision(SAND, SANDSTONE, 5, metadata)
                  : oldDecision(RED_SAND, TERRACOTTA, 4, metadata))
               : climateGroup != 4 && climateGroup != 5
                  ? oldDecision(GRAVEL, STONE, 4, metadata)
                  : (roll < 70 ? oldDecision(GRAVEL, STONE, 4, metadata)
                     : oldDecision(STONE, STONE, 12, metadata));
         case 70 -> oldDecision(SNOW, SNOW, 4, metadata);
         case 90 -> roll < 40
            ? oldDecision(GRASS, DIRT, 4, metadata)
            : oldDecision(MUD, PACKED_MUD, 4, metadata);
         case 95 -> oldDecision(MUD, DIRT, 4, metadata);
         case 100 -> zoneHeight >= 200 && roll < 60
            ? oldDecision(STONE, STONE, 12, metadata)
            : (roll < 35 ? oldDecision(MOSS, DIRT, 4, metadata)
               : oldDecision(GRASS, DIRT, 4, metadata));
         default -> climateGroup != 4 && climateGroup != 5
            ? oldDecision(GRASS, DIRT, 4, metadata)
            : oldDecision(PODZOL, DIRT, 4, metadata);
      };
   }

   private static Decision oldDecision(Material top, Material filler, int depth, Metadata metadata) {
      return new Decision(top, filler, depth, metadata.slopeDiff(), metadata.slopeDegrees(), metadata.form());
   }

   private record Coordinate(int x, int z) { }

   private record Metadata(int slopeDiff, double slopeDegrees, MountainForm form) { }
}
