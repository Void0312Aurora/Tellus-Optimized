package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.write.WriteContext;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minecraft-free semantic biome volume plan for one canonical chunk.
 *
 * <p>The available form is deliberately exact: it contains every native
 * Minecraft quart cell in section/X/Y/Z order. Unknown and queued forms carry
 * no speculative prefix, so a caller cannot mistake a partial volume for a
 * complete biome container.</p>
 */
public record TellusBiomeVolumePlan(
   Request request,
   DecisionStatus status,
   List<Cell> cells,
   List<String> diagnostics
) {
   private static final int CHUNK_SIZE = 16;
   private static final int QUARTS_PER_AXIS = 4;
   private static final long MAX_CELLS = 16_384L;

   public TellusBiomeVolumePlan {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(status, "status");
      cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      if (status == DecisionStatus.PARTIAL) {
         throw new IllegalArgumentException("biome volume plan cannot expose PARTIAL cells");
      }
      if (status == DecisionStatus.UNKNOWN || status == DecisionStatus.QUEUED) {
         if (!cells.isEmpty()) throw new IllegalArgumentException("blocked biome volume must have no cells");
      } else if (status == DecisionStatus.AVAILABLE) {
         validateAvailable(request, cells);
      }
   }

   /** Structural request; semantic profile and anchor validation belongs to the planner. */
   public record Request(
      TellusGenerationContext generation,
      int chunkX,
      int chunkZ,
      List<WriteContext> columns,
      SamplingResolution resolution
   ) {
      public Request {
         Objects.requireNonNull(generation, "generation");
         columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
         Objects.requireNonNull(resolution, "resolution");
      }
   }

   /** One exact native quart biome cell. */
   public record Cell(int quartX, int quartY, int quartZ, BiomeDecision biome) {
      public Cell {
         Objects.requireNonNull(biome, "biome");
      }
   }

   /** Coordinate-only native order, matching MCLevelChunkSection.fillBiomesFromNoise. */
   public record QuartCoordinate(int quartX, int quartY, int quartZ) { }

   public static TellusBiomeVolumePlan blocked(Request request, DecisionStatus status, String diagnostic) {
      Objects.requireNonNull(request, "request");
      if (status != DecisionStatus.UNKNOWN && status != DecisionStatus.QUEUED) {
         throw new IllegalArgumentException("blocked biome volume requires UNKNOWN or QUEUED");
      }
      return new TellusBiomeVolumePlan(request, status, List.of(),
         List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
   }

   public static TellusBiomeVolumePlan available(Request request, List<Cell> cells, List<String> diagnostics) {
      return new TellusBiomeVolumePlan(request, DecisionStatus.AVAILABLE,
         Objects.requireNonNull(cells, "cells"), Objects.requireNonNull(diagnostics, "diagnostics"));
   }

   /**
    * Returns native section/X/Y/Z order. The method is coordinate-only and
    * does not validate source ownership or invoke a resolver.
    */
   public static List<QuartCoordinate> nativeCellOrder(Request request) {
      Objects.requireNonNull(request, "request");
      int count = checkedCellCount(request);
      TellusGenerationContext generation = request.generation();
      int sections = (generation.maxYExclusive() - generation.minY()) / CHUNK_SIZE;
      List<QuartCoordinate> result = new ArrayList<>(count);
      for (int section = 0; section < sections; section++) {
         for (int quartX = 0; quartX < QUARTS_PER_AXIS; quartX++) {
            for (int quartY = 0; quartY < QUARTS_PER_AXIS; quartY++) {
               for (int quartZ = 0; quartZ < QUARTS_PER_AXIS; quartZ++) {
                  result.add(new QuartCoordinate(
                     Math.addExact(Math.multiplyExact(request.chunkX(), QUARTS_PER_AXIS), quartX),
                     Math.addExact(generation.minY() / 4, Math.addExact(Math.multiplyExact(section, QUARTS_PER_AXIS), quartY)),
                     Math.addExact(Math.multiplyExact(request.chunkZ(), QUARTS_PER_AXIS), quartZ)));
               }
            }
         }
      }
      return List.copyOf(result);
   }

   static int checkedCellCount(Request request) {
      Objects.requireNonNull(request, "request");
      TellusGenerationContext generation = request.generation();
      long height = (long)generation.maxYExclusive() - generation.minY();
      if (height <= 0L || height > 4096L
         || generation.minY() % CHUNK_SIZE != 0 || generation.maxYExclusive() % CHUNK_SIZE != 0) {
         throw new IllegalArgumentException("biome volume requires aligned bounds and a positive height <=4096");
      }
      long count = Math.multiplyExact(height / QUARTS_PER_AXIS, 16L);
      if (count > MAX_CELLS) throw new IllegalArgumentException("biome volume exceeds cell budget");
      checkedBlockOrigin(request.chunkX());
      checkedBlockOrigin(request.chunkZ());
      if (request.columns().size() != 16) throw new IllegalArgumentException("biome volume requires 16 columns");
      return Math.toIntExact(count);
   }

   static int checkedBlockOrigin(int chunk) {
      long origin = Math.multiplyExact((long)chunk, (long)CHUNK_SIZE);
      int checked = Math.toIntExact(origin);
      Math.toIntExact(Math.addExact(origin, CHUNK_SIZE - 1L));
      return checked;
   }

   private static void validateAvailable(Request request, List<Cell> cells) {
      List<QuartCoordinate> order = nativeCellOrder(request);
      if (cells.size() != order.size()) {
         throw new IllegalArgumentException("AVAILABLE biome volume must contain every native quart cell");
      }
      for (int i = 0; i < order.size(); i++) {
         Cell cell = cells.get(i);
         QuartCoordinate expected = order.get(i);
         if (cell.quartX() != expected.quartX() || cell.quartY() != expected.quartY()
            || cell.quartZ() != expected.quartZ()) {
            throw new IllegalArgumentException("biome volume cells are not in native order or contain a hole");
         }
         if (!cell.biome().known()) {
            throw new IllegalArgumentException("AVAILABLE biome volume requires known biome decisions");
         }
      }
   }
}
