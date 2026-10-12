package com.yucareux.tellus.worldgen.global;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * A deterministic closed quadrilateral sphere topology used by RFC Phase 1.
 * It is a subdivided cube: its eight degree-three vertices are explicit
 * extraordinary vertices and every other vertex is degree four. Geometry is
 * intentionally supplied separately by SphereWorldModel.
 */
public final class ClosedQuadSphereTopology {
   private final int subdivision;
   private final List<CellId> cells;
   private final Map<CellId, List<VertexKey>> cellVertices;
   private final Map<DirectedEdge, CellId> neighbors;
   private final Map<VertexKey, Set<VertexKey>> vertexNeighbors;
   private final Map<VertexKey, List<FaceVertex>> vertexUses;

   private ClosedQuadSphereTopology(
      int subdivision,
      List<CellId> cells,
      Map<CellId, List<VertexKey>> cellVertices,
      Map<DirectedEdge, CellId> neighbors,
      Map<VertexKey, Set<VertexKey>> vertexNeighbors,
      Map<VertexKey, List<FaceVertex>> vertexUses
   ) {
      this.subdivision = subdivision;
      this.cells = List.copyOf(cells);
      this.cellVertices = Map.copyOf(cellVertices);
      this.neighbors = Map.copyOf(neighbors);
      this.vertexNeighbors = Map.copyOf(vertexNeighbors);
      this.vertexUses = Map.copyOf(vertexUses);
   }

   /** Build the same topology in either insertion order. */
   public static ClosedQuadSphereTopology create(int subdivision) {
      return create(subdivision, false);
   }

   public static ClosedQuadSphereTopology create(int subdivision, boolean reverseInsertion) {
      if (subdivision < 1) throw new IllegalArgumentException("subdivision");
      Map<CellId, List<VertexKey>> cellsById = new HashMap<>();
      Map<VertexKey, List<FaceVertex>> uses = new HashMap<>();
      int begin = reverseInsertion ? subdivision - 1 : 0;
      int end = reverseInsertion ? -1 : subdivision;
      int step = reverseInsertion ? -1 : 1;
      for (int face = reverseInsertion ? 5 : 0; reverseInsertion ? face >= 0 : face < 6; face += reverseInsertion ? -1 : 1) {
         for (int i = begin; i != end; i += step) {
            for (int j = begin; j != end; j += step) {
               CellId id = new CellId(face, i, j);
               List<FaceVertex> corners = List.of(
                  new FaceVertex(face, i, j),
                  new FaceVertex(face, i + 1, j),
                  new FaceVertex(face, i + 1, j + 1),
                  new FaceVertex(face, i, j + 1));
               List<VertexKey> keys = new ArrayList<>(4);
               for (FaceVertex corner : corners) {
                  VertexKey key = cubeVertex(corner, subdivision);
                  keys.add(key);
                  uses.computeIfAbsent(key, ignored -> new ArrayList<>()).add(corner);
               }
               cellsById.put(id, List.copyOf(keys));
            }
         }
      }
      List<CellId> cells = cellsById.keySet().stream().sorted().toList();
      Map<EdgeKey, List<EdgeUse>> edgeUses = new HashMap<>();
      Map<VertexKey, Set<VertexKey>> vertexNeighbors = new HashMap<>();
      for (CellId cell : cells) {
         List<VertexKey> vertices = cellsById.get(cell);
         for (int index = 0; index < 4; index++) {
            VertexKey first = vertices.get(index);
            VertexKey second = vertices.get((index + 1) % 4);
            EdgeDirection direction = switch (index) {
               case 0 -> EdgeDirection.SOUTH;
               case 1 -> EdgeDirection.EAST;
               case 2 -> EdgeDirection.NORTH;
               case 3 -> EdgeDirection.WEST;
               default -> throw new AssertionError();
            };
            EdgeKey edge = new EdgeKey(first, second);
            edgeUses.computeIfAbsent(edge, ignored -> new ArrayList<>()).add(new EdgeUse(cell, direction));
            vertexNeighbors.computeIfAbsent(first, ignored -> new HashSet<>()).add(second);
            vertexNeighbors.computeIfAbsent(second, ignored -> new HashSet<>()).add(first);
         }
      }
      Map<DirectedEdge, CellId> neighbors = new HashMap<>();
      for (Map.Entry<EdgeKey, List<EdgeUse>> entry : edgeUses.entrySet()) {
         List<EdgeUse> usesForEdge = entry.getValue();
         if (usesForEdge.size() != 2) {
            throw new IllegalStateException("closed quadrilateral edge has " + usesForEdge.size() + " incidences: " + entry.getKey());
         }
         EdgeUse first = usesForEdge.get(0), second = usesForEdge.get(1);
         neighbors.put(new DirectedEdge(first.cell(), first.direction()), second.cell());
         neighbors.put(new DirectedEdge(second.cell(), second.direction()), first.cell());
      }
      Map<VertexKey, List<FaceVertex>> immutableUses = new HashMap<>();
      uses.forEach((key, value) -> immutableUses.put(key, List.copyOf(value)));
      Map<VertexKey, Set<VertexKey>> immutableNeighbors = new HashMap<>();
      vertexNeighbors.forEach((key, value) -> immutableNeighbors.put(key, Set.copyOf(value)));
      return new ClosedQuadSphereTopology(subdivision, cells, cellsById, neighbors, immutableNeighbors, immutableUses);
   }

   public int subdivision() { return this.subdivision; }
   public List<CellId> cells() { return this.cells; }
   public List<VertexKey> vertices(CellId cell) { return this.cellVertices.get(Objects.requireNonNull(cell, "cell")); }
   public List<FaceVertex> faceVertices(CellId cell) {
      Objects.requireNonNull(cell, "cell");
      return List.of(new FaceVertex(cell.face(), cell.i(), cell.j()),
         new FaceVertex(cell.face(), cell.i() + 1, cell.j()),
         new FaceVertex(cell.face(), cell.i() + 1, cell.j() + 1),
         new FaceVertex(cell.face(), cell.i(), cell.j() + 1));
   }
   public List<FaceVertex> uses(VertexKey vertex) { return this.vertexUses.getOrDefault(vertex, List.of()); }
   public Set<VertexKey> vertices() { return this.vertexNeighbors.keySet(); }

   public CellId neighbor(CellId cell, EdgeDirection direction) {
      CellId result = this.neighbors.get(new DirectedEdge(Objects.requireNonNull(cell, "cell"), direction));
      if (result == null) throw new IllegalArgumentException("unknown cell edge");
      return result;
   }

   public int vertexDegree(VertexKey vertex) {
      Set<VertexKey> neighbors = this.vertexNeighbors.get(Objects.requireNonNull(vertex, "vertex"));
      if (neighbors == null) throw new IllegalArgumentException("unknown vertex");
      return neighbors.size();
   }

   public Audit audit() {
      int degreeThree = 0, degreeFour = 0, other = 0;
      int defectSum = 0;
      for (VertexKey vertex : this.vertices()) {
         int degree = this.vertexDegree(vertex);
         if (degree == 3) degreeThree++;
         else if (degree == 4) degreeFour++;
         else other++;
         defectSum += 4 - degree;
      }
      return new Audit(this.vertices().size(), this.cells.size(), this.neighbors.size() / 2,
         degreeThree, degreeFour, other, defectSum,
         this.vertices().size() - this.neighbors.size() / 2 + this.cells.size());
   }

   /** Stable representation used to compare arbitrary generation orders. */
   public String canonicalSignature() {
      StringBuilder result = new StringBuilder();
      for (CellId cell : this.cells) {
         result.append(cell).append(':').append(this.vertices(cell));
         for (EdgeDirection direction : EdgeDirection.values()) {
            result.append('>').append(direction).append('=').append(this.neighbor(cell, direction));
         }
         result.append(';');
      }
      return result.toString();
   }

   /** Maps one local face vertex to a shared cube-boundary key. */
   public static VertexKey cubeVertex(FaceVertex vertex, int subdivision) {
      if (vertex.face() < 0 || vertex.face() >= 6 || vertex.i() < 0 || vertex.i() > subdivision
         || vertex.j() < 0 || vertex.j() > subdivision) throw new IllegalArgumentException("face vertex");
      int u = 2 * vertex.i() - subdivision;
      int v = 2 * vertex.j() - subdivision;
      return switch (vertex.face()) {
         case 0 -> new VertexKey(subdivision, v, -u);
         case 1 -> new VertexKey(-subdivision, v, u);
         case 2 -> new VertexKey(u, subdivision, -v);
         case 3 -> new VertexKey(u, -subdivision, v);
         case 4 -> new VertexKey(u, v, subdivision);
         case 5 -> new VertexKey(-u, v, -subdivision);
         default -> throw new AssertionError();
      };
   }

   public record CellId(int face, int i, int j) implements Comparable<CellId> {
      @Override public int compareTo(CellId other) {
         int result = Integer.compare(this.face, other.face);
         if (result == 0) result = Integer.compare(this.i, other.i);
         if (result == 0) result = Integer.compare(this.j, other.j);
         return result;
      }
   }

   public record FaceVertex(int face, int i, int j) { }
   public record VertexKey(int x, int y, int z) implements Comparable<VertexKey> {
      @Override public int compareTo(VertexKey other) {
         int result = Integer.compare(this.x, other.x);
         if (result == 0) result = Integer.compare(this.y, other.y);
         if (result == 0) result = Integer.compare(this.z, other.z);
         return result;
      }
   }

   public enum EdgeDirection { SOUTH, EAST, NORTH, WEST;
      public EdgeDirection opposite() {
         return switch (this) {
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case NORTH -> SOUTH;
            case WEST -> EAST;
         };
      }
   }

   public record Audit(int vertices, int faces, int edges, int degreeThreeVertices,
      int degreeFourVertices, int otherDegreeVertices, int defectSum, int eulerCharacteristic) { }

   private record EdgeUse(CellId cell, EdgeDirection direction) { }
   private record DirectedEdge(CellId cell, EdgeDirection direction) { }
   private record EdgeKey(VertexKey first, VertexKey second) {
      EdgeKey {
         if (first.compareTo(second) > 0) {
            VertexKey swap = first;
            first = second;
            second = swap;
         }
      }
   }
}
