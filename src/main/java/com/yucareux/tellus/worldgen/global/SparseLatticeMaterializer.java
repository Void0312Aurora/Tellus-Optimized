package com.yucareux.tellus.worldgen.global;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.Atlas;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.BlockId;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.PatchDescriptor;

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
         return generated;
      });
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
   }

   public record CellData(BlockId id, int state) {
      public CellData { Objects.requireNonNull(id, "id"); }
   }
}
