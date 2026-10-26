package com.yucareux.tellus.worldgen.global;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.Atlas;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.BlockId;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.GridVersion;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.PatchDescriptor;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.PatchKey;

/**
 * Demand-driven materialization over the canonical lattice index.
 *
 * <p>The atlas remains the global identity and neighbor index; this class
 * stores only cells requested by a point or a neighboring-cell operation. It
 * is deliberately data-only and does not claim to be a Minecraft chunk or
 * Anvil storage implementation.</p>
 */
public final class SparseLatticeMaterializer {
   private static final Comparator<BlockId> BLOCK_ORDER = Comparator
      .comparing(BlockId::patch)
      .thenComparingLong(BlockId::i)
      .thenComparingLong(BlockId::j)
      .thenComparingInt(BlockId::k);

   private final Atlas atlas;
   private final Function<BlockId, CellData> generator;
   private final Map<BlockId, CellData> materialized = new ConcurrentHashMap<>();
   /**
    * Explicit edits are the durable overlay.  The materialized map is only a
    * demand-driven cache and may be evicted without changing the canonical
    * identity or the edited state.
    */
   private final Map<BlockId, Integer> edits = new ConcurrentHashMap<>();

   public SparseLatticeMaterializer(Atlas atlas, Function<BlockId, CellData> generator) {
      this.atlas = Objects.requireNonNull(atlas, "atlas");
      this.generator = Objects.requireNonNull(generator, "generator");
   }

   public Atlas atlas() { return this.atlas; }

   /** Own a geodetic point and materialize its vertical cell on demand. */
   public CellData request(SphereWorldModel.GeoPoint point, int k) {
      BlockId surface = this.atlas.owner(Objects.requireNonNull(point, "point"));
      return this.request(new BlockId(surface.grid(), surface.patch(), surface.i(), surface.j(), k));
   }

   /** Materialize a canonical identity exactly once for this instance. */
   public CellData request(BlockId id) {
      validate(id);
      return this.materialized.computeIfAbsent(id, key -> {
         CellData generated = Objects.requireNonNull(this.generator.apply(key), "generator result");
         if (!key.equals(generated.id())) throw new IllegalArgumentException("generator changed BlockID");
         Integer editedState = this.edits.get(key);
         return editedState == null ? generated : new CellData(key, editedState);
      });
   }

   /**
    * Apply an explicit block edit to the canonical identity.  The edit is
    * installed before updating the cache so a concurrent first request cannot
    * publish the generator's stale value.
    */
   public CellData setState(BlockId id, int state) {
      validate(id);
      this.edits.put(id, state);
      return this.materialized.compute(id, (key, ignored) -> new CellData(key, state));
   }

   /** Returns the durable edit overlay in deterministic identity order. */
   public List<SphericalLatticePersistence.CellEdit> edits() {
      return this.edits.entrySet().stream()
         .sorted(Map.Entry.comparingByKey(BLOCK_ORDER))
         .map(entry -> new SphericalLatticePersistence.CellEdit(entry.getKey(), entry.getValue()))
         .toList();
   }

   /**
    * Restore edits before serving requests.  Validation is completed for the
    * whole batch first, so a malformed snapshot cannot partially update the
    * overlay.
    */
   public void restoreEdits(List<SphericalLatticePersistence.CellEdit> restored) {
      Objects.requireNonNull(restored, "restored");
      Set<BlockId> seen = new HashSet<>();
      for (SphericalLatticePersistence.CellEdit edit : restored) {
         Objects.requireNonNull(edit, "edit");
         validate(edit.id());
         if (!seen.add(edit.id())) throw new IllegalArgumentException("duplicate restored edit");
      }
      for (SphericalLatticePersistence.CellEdit edit : restored) {
         this.edits.put(edit.id(), edit.state());
         this.materialized.compute(edit.id(), (key, ignored) -> new CellData(key, edit.state()));
      }
   }

   /** Evict one cached cell while retaining its canonical edit, if any. */
   public boolean evict(BlockId id) {
      validate(id);
      return this.materialized.remove(id) != null;
   }

   /** Evict all generated cells; explicit edits remain available after reload. */
   public void evictAll() {
      this.materialized.clear();
   }

   /** Resolve and materialize one cardinal neighbor, including a patch seam. */
   public CellData requestNeighbor(BlockId source, int dx, int dz) {
      return this.request(this.atlas.neighbor(Objects.requireNonNull(source, "source"), dx, dz));
   }

   public boolean isMaterialized(BlockId id) {
      return this.materialized.containsKey(Objects.requireNonNull(id, "id"));
   }

   public int size() { return this.materialized.size(); }

   public List<BlockId> materializedIds() {
      return this.materialized.keySet().stream().sorted(BLOCK_ORDER).toList();
   }

   public List<CellData> materializedCells() {
      return this.materialized.values().stream().sorted(Comparator.comparing(CellData::id, BLOCK_ORDER)).toList();
   }

   private void validate(BlockId id) {
      Objects.requireNonNull(id, "id");
      if (!this.atlas.grid().equals(id.grid())) throw new IllegalArgumentException("block belongs to another grid");
      PatchDescriptor patch = this.atlas.patch(id.patch());
      patch.cell(id.i(), id.j(), id.k());
      if (!this.atlas.isCanonical(id)) throw new IllegalArgumentException("block is a noncanonical overlap cell");
   }

   /** Maps a surface lattice cell to the storage chunk containing it. */
   public ChunkKey chunkOf(BlockId id, int cellsPerChunk) {
      validate(id);
      if (cellsPerChunk < 1) throw new IllegalArgumentException("cellsPerChunk");
      this.atlas.patch(id.patch());
      return new ChunkKey(id.grid(), id.patch(),
         Math.floorDiv(id.i(), (long)cellsPerChunk),
         Math.floorDiv(id.j(), (long)cellsPerChunk));
   }

   /** Maps a radial cell to the chunk section used by a vertical storage layer. */
   public ChunkSectionKey sectionOf(BlockId id, int cellsPerChunk, int cellsPerSection) {
      ChunkKey chunk = this.chunkOf(id, cellsPerChunk);
      if (cellsPerSection < 1) throw new IllegalArgumentException("cellsPerSection");
      return new ChunkSectionKey(chunk, Math.floorDiv(id.k(), cellsPerSection));
   }

   public record ChunkKey(GridVersion grid, PatchKey patch, long x, long z) {
      public ChunkKey {
         Objects.requireNonNull(grid, "grid");
         Objects.requireNonNull(patch, "patch");
      }
   }

   public record ChunkSectionKey(ChunkKey chunk, long sectionY) {
      public ChunkSectionKey { Objects.requireNonNull(chunk, "chunk"); }
   }

   public record CellData(BlockId id, int state) {
      public CellData { Objects.requireNonNull(id, "id"); }
   }
}
