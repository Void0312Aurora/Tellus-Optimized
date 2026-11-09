package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.CubeNetAddressAdapter;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Detached semantic underground deltas for one canonical Cube-Net engine chunk. */
public record UndergroundStagePlan(
   Request request,
   DecisionStatus status,
   List<Delta> deltas,
   List<String> diagnostics
) {
   public static final int MAX_DELTAS = 1_048_576;
   public static final int MAX_EXCLUSIONS = 4096;
   public static final int SURFACE_HALO_RADIUS = 2;
   public static final int SURFACE_HALO_SIDE = 20;

   public UndergroundStagePlan {
      validateRequest(request);
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(deltas, "deltas");
      if (deltas.size() > MAX_DELTAS) throw new IllegalArgumentException("underground delta budget exceeded");
      deltas = List.copyOf(deltas);
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      if (status == DecisionStatus.PARTIAL) {
         throw new IllegalArgumentException("underground stage cannot expose PARTIAL deltas");
      }
      if (status != DecisionStatus.AVAILABLE && !deltas.isEmpty()) {
         throw new IllegalArgumentException("blocked underground stage must contain no deltas");
      }
      if (status == DecisionStatus.AVAILABLE) validateDeltas(request, deltas);
   }

   /**
    * Complete detached source snapshot. chunkX/chunkZ are engine coordinates;
    * address is the corresponding face-local canonical address. The optional
    * radius-two halo is row-major, from engine origin (-2,-2) through (17,17).
    * Geology requires all 400 halo values before a resolver can run.
    */
   public record Request(
      TellusGenerationContext generation,
      SphereWorldModel model,
      SphereWorldModel.SphereChunkAddress address,
      int chunkX,
      int chunkZ,
      int[] surfaceY,
      int[] floodGuardY,
      int[] generationFloorY,
      List<Exclusion> structureExclusions,
      int[] surfaceHaloY,
      Flags flags,
      String sourceRevision,
      SamplingResolution resolution
   ) {
      public Request {
         generation = Objects.requireNonNull(generation, "generation");
         model = Objects.requireNonNull(model, "model");
         address = Objects.requireNonNull(address, "address");
         surfaceY = copyArray(surfaceY, "surfaceY", 256, false);
         floodGuardY = copyArray(floodGuardY, "floodGuardY", 256, true);
         generationFloorY = copyArray(generationFloorY, "generationFloorY", 256, true);
         Objects.requireNonNull(structureExclusions, "structureExclusions");
         if (structureExclusions.size() > MAX_EXCLUSIONS) throw new IllegalArgumentException("underground exclusion budget exceeded");
         structureExclusions = List.copyOf(structureExclusions);
         surfaceHaloY = copyArray(surfaceHaloY, "surfaceHaloY", 400, true);
         flags = Objects.requireNonNull(flags, "flags");
         if (!flags.anyEnabled()) throw new IllegalArgumentException("at least one underground flag must be enabled");
         sourceRevision = requireText(sourceRevision, "sourceRevision");
         resolution = Objects.requireNonNull(resolution, "resolution");
         validateChunkIdentity(generation, model, address, chunkX, chunkZ);
         validateHalo(surfaceY, surfaceHaloY, flags);
         validateArithmetic(generation, chunkX, chunkZ, surfaceY, generationFloorY, surfaceHaloY, flags);
      }

      /** Convenience overload for stages that do not need a geological halo. */
      public Request(TellusGenerationContext generation, SphereWorldModel model,
                     SphereWorldModel.SphereChunkAddress address, int chunkX, int chunkZ,
                     int[] surfaceY, int[] floodGuardY, int[] generationFloorY,
                     List<Exclusion> structureExclusions, Flags flags,
                     String sourceRevision, SamplingResolution resolution) {
         this(generation, model, address, chunkX, chunkZ, surfaceY, floodGuardY,
            generationFloorY, structureExclusions, null, flags, sourceRevision, resolution);
      }

      @Override public int[] surfaceY() { return surfaceY.clone(); }
      @Override public int[] floodGuardY() { return floodGuardY == null ? null : floodGuardY.clone(); }
      @Override public int[] generationFloorY() { return generationFloorY == null ? null : generationFloorY.clone(); }
      @Override public int[] surfaceHaloY() { return surfaceHaloY == null ? null : surfaceHaloY.clone(); }

      private static int[] copyArray(int[] values, String name, int length, boolean nullable) {
         if (values == null) {
            if (nullable) return null;
            throw new NullPointerException(name);
         }
         if (values.length != length) throw new IllegalArgumentException(name + " must contain " + length + " values");
         return values.clone();
      }
   }

   public record Flags(boolean applyCaves, boolean cavesReachSurface,
                       boolean applyOreVeins, boolean applyGeologicalStonePatches) {
      public boolean anyEnabled() {
         return applyCaves || applyOreVeins || applyGeologicalStonePatches;
      }
   }

   /** Pure piece geometry; native structure types are converted by an adapter. */
   public record Exclusion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                           int carvingMargin, boolean blocksFeaturePlacement) {
      public Exclusion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
         this(minX, minY, minZ, maxX, maxY, maxZ, 4, true);
      }

      public Exclusion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                       boolean blocksFeaturePlacement) {
         this(minX, minY, minZ, maxX, maxY, maxZ, 4, blocksFeaturePlacement);
      }

      public Exclusion {
         if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("underground exclusion bounds are inverted");
         }
         if (carvingMargin < 0) throw new IllegalArgumentException("underground carving margin cannot be negative");
      }

      public boolean containsExpanded(int x, int y, int z, int margin) {
         int safe = Math.max(0, margin);
         return (long)x >= (long)minX - safe && (long)x <= (long)maxX + safe
            && (long)y >= (long)minY - safe && (long)y <= (long)maxY + safe
            && (long)z >= (long)minZ - safe && (long)z <= (long)maxZ + safe;
      }

      public boolean blocksCarving(int x, int y, int z) {
         return containsExpanded(x, y, z, carvingMargin);
      }

      public boolean blocksFeaturePlacement(int x, int y, int z) {
         return blocksFeaturePlacement && containsExpanded(x, y, z, 16);
      }
   }

   public record Delta(int x, int y, int z, UndergroundMaterial material, boolean markPostProcessing) {
      public Delta {
         Objects.requireNonNull(material, "material");
         boolean fluid = material == UndergroundMaterial.WATER || material == UndergroundMaterial.LAVA;
         if (markPostProcessing != fluid) {
            throw new IllegalArgumentException("underground marker intent must match water or lava");
         }
      }
   }

   public enum UndergroundMaterial {
      CAVE_AIR, WATER, LAVA, COPPER_ORE, DEEPSLATE_IRON_ORE, GRANITE, TUFF
   }

   public static UndergroundStagePlan blocked(Request request, DecisionStatus status, String diagnostic) {
      if (status != DecisionStatus.UNKNOWN && status != DecisionStatus.QUEUED) {
         throw new IllegalArgumentException("blocked underground stage requires UNKNOWN or QUEUED");
      }
      return new UndergroundStagePlan(request, status, List.of(),
         List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
   }

   public static UndergroundStagePlan available(Request request, List<Delta> deltas, List<String> diagnostics) {
      return new UndergroundStagePlan(request, DecisionStatus.AVAILABLE, deltas, diagnostics);
   }

   static void validateRequest(Request request) {
      Objects.requireNonNull(request, "request");
      validateChunkIdentity(request.generation(), request.model(), request.address(), request.chunkX(), request.chunkZ());
      validateHalo(request.surfaceY, request.surfaceHaloY, request.flags());
   }

   private static void validateChunkIdentity(TellusGenerationContext generation, SphereWorldModel model,
                                            SphereWorldModel.SphereChunkAddress address, int chunkX, int chunkZ) {
      if (Double.compare(model.config().worldScale(), generation.worldScale()) != 0) {
         throw new IllegalArgumentException("underground model scale differs from generation");
      }
      long height = (long)generation.maxYExclusive() - generation.minY();
      if (height <= 0L || height > 4096L || generation.minY() % 16 != 0
         || generation.maxYExclusive() % 16 != 0) {
         throw new IllegalArgumentException("underground generation bounds must be aligned and <=4096");
      }
      long minX = (long)chunkX * 16L, minZ = (long)chunkZ * 16L;
      Math.toIntExact(minX);
      Math.toIntExact(minX + 15L);
      Math.toIntExact(minZ);
      Math.toIntExact(minZ + 15L);
      model.chartForChunk(address);
      new CubeNetAddressAdapter(model).chunkAddress(chunkX, chunkZ).filter(address::equals)
         .orElseThrow(() -> new IllegalArgumentException("underground target is not its canonical Cube-Net chunk"));
   }

   private static void validateHalo(int[] surface, int[] halo, Flags flags) {
      if (halo == null) {
         if (flags.applyGeologicalStonePatches()) {
            throw new IllegalArgumentException("geological underground stage requires a complete surface halo");
         }
         return;
      }
      for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
         if (halo[(z + SURFACE_HALO_RADIUS) * SURFACE_HALO_SIDE + x + SURFACE_HALO_RADIUS]
            != surface[z * 16 + x]) {
            throw new IllegalArgumentException("underground halo center differs from surface snapshot");
         }
      }
   }

   private static void validateArithmetic(TellusGenerationContext generation, int chunkX, int chunkZ,
                                          int[] surface, int[] floor, int[] halo, Flags flags) {
      Math.subtractExact(generation.minY(), generation.seaLevel());
      Math.subtractExact(generation.maxYExclusive() - 1, generation.seaLevel());
      for (int value : surface) {
         Math.subtractExact(value, 5);
         Math.subtractExact(value, generation.minY());
      }
      if (floor != null) for (int value : floor) Math.addExact(value, 1);
      if (flags.applyGeologicalStonePatches()) {
         Math.toIntExact((long)chunkX * 16 - SURFACE_HALO_RADIUS);
         Math.toIntExact((long)chunkX * 16 + 15 + SURFACE_HALO_RADIUS);
         Math.toIntExact((long)chunkZ * 16 - SURFACE_HALO_RADIUS);
         Math.toIntExact((long)chunkZ * 16 + 15 + SURFACE_HALO_RADIUS);
         for (int value : halo) Math.subtractExact(value, 2);
      }
   }

   private static void validateDeltas(Request request, List<Delta> deltas) {
      long minX = (long)request.chunkX() * 16L, minZ = (long)request.chunkZ() * 16L;
      Set<Position> positions = new HashSet<>();
      for (Delta delta : deltas) {
         if ((long)delta.x() < minX || (long)delta.x() >= minX + 16L
            || (long)delta.z() < minZ || (long)delta.z() >= minZ + 16L
            || delta.y() < request.generation().minY()
            || delta.y() >= request.generation().maxYExclusive()) {
            throw new IllegalArgumentException("underground delta is outside target chunk or build bounds");
         }
         if (!positions.add(new Position(delta.x(), delta.y(), delta.z()))) {
            throw new IllegalArgumentException("underground deltas contain duplicate positions");
         }
      }
   }

   private record Position(int x, int y, int z) { }

   private static String requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
      return value;
   }
}
