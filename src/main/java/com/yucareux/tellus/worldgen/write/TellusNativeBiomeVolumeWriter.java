package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.TellusBiomeVolumePlan;
import com.yucareux.tellus.worldgen.decision.TellusBiomeVolumePlanner;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * Explicit biome-only ProtoChunk publication for participating target-object writers.
 * One non-reentrant lease covers preflight, planning, staging and publication.
 * Section, state-container and biome-container identities and native block counters are retained.
 * Recovery restores semantic quart holders and dirty state, not palette encoding/allocation history.
 * Direct/native-engine writes, world aliases and source changes require caller quiescence;
 * this is not a status/light/tick/disk or general concurrency transaction.
 */
public final class TellusNativeBiomeVolumeWriter {
   private final SphereWorldModel model;

   public TellusNativeBiomeVolumeWriter() { this(null); }
   public TellusNativeBiomeVolumeWriter(SphereWorldModel model) { this.model = model; }

   /** Deliberately distinct from a flat column storage payload. */
   public record Result(WriteStatus status, int writtenQuartCells, List<String> diagnostics) {
      public Result {
         Objects.requireNonNull(status, "status");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (status == WriteStatus.PARTIAL || writtenQuartCells < 0
            || (status == WriteStatus.WRITTEN ? writtenQuartCells == 0 : writtenQuartCells != 0)) {
            throw new IllegalArgumentException("biome volume result requires complete written cells or zero stopped cells");
         }
      }
   }

   public Result write(boolean enabled, ChunkAccess target, TellusBiomeVolumePlan.Request request,
                       TellusBiomeVolumePlanner planner, Registry<Biome> registry) {
      if (!enabled) return stopped(WriteStatus.NO_WRITE_INVALID, "native biome volume writer is disabled");
      if (!(target instanceof ProtoChunk) || request == null || planner == null || registry == null) {
         return stopped(WriteStatus.NO_WRITE_INVALID, "biome volume writer requires ProtoChunk, request, planner and registry");
      }
      var acquired = TellusTargetWriteGuard.tryAcquire(target);
      if (acquired.isEmpty()) return stopped(WriteStatus.NO_WRITE_QUEUED, "native target already has a participating writer");
      try (var lease = acquired.orElseThrow()) {
         lease.requireOwned(target);
         final LevelChunkSection[] backing;
         final LevelChunkSection[] originals;
         final List<PalettedContainer<BlockState>> states = new ArrayList<>();
         final List<PalettedContainer<Holder<Biome>>> biomes = new ArrayList<>();
         final List<Holder<Biome>> oldHolders = new ArrayList<>();
         final boolean dirty;
         final List<Holder.Reference<Biome>> mapped = new ArrayList<>();
         final List<PalettedContainer<Holder<Biome>>> staged = new ArrayList<>();
         try {
            TellusBiomeVolumePlanner.validateRequest(request, model);
            if (target.getPos().x != request.chunkX() || target.getPos().z != request.chunkZ()
               || target.getMinBuildHeight() != request.generation().minY()
               || target.getMaxBuildHeight() != request.generation().maxYExclusive()) {
               return stopped(WriteStatus.NO_WRITE_INVALID, "biome target chunk or build bounds differ from request");
            }
            backing = Objects.requireNonNull(target.getSections(), "target sections");
            int sectionCount = (request.generation().maxYExclusive() - request.generation().minY()) / 16;
            if (backing.length != sectionCount || sectionCount <= 0 || sectionCount > 256) {
               return stopped(WriteStatus.NO_WRITE_INVALID, "biome target section count exceeds exact volume bounds");
            }
            if (request.resolution().kind() == com.yucareux.tellus.worldgen.sample.SamplingResolution.Kind.NON_BLOCKING) {
               return stopped(WriteStatus.NO_WRITE_QUEUED, "nonblocking biome volume remains planner, palette and registry cold");
            }
            originals = backing.clone();
            var sectionIdentities = new IdentityHashMap<LevelChunkSection, Boolean>();
            var paletteIdentities = new IdentityHashMap<PalettedContainer<Holder<Biome>>, Boolean>();
            for (LevelChunkSection section : originals) {
               if (section == null || section.getClass() != LevelChunkSection.class
                  || sectionIdentities.put(section, Boolean.TRUE) != null) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "biome publication requires unique exact vanilla sections");
               }
            }
            for (LevelChunkSection section : originals) {
               states.add(Objects.requireNonNull(section.getStates(), "original state container"));
               var palette = Objects.requireNonNull(section.getBiomes(), "original biome container");
               if (palette.getClass() != PalettedContainer.class) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "biome publication requires exact mutable native palettes");
               }
               @SuppressWarnings("unchecked") var mutable = (PalettedContainer<Holder<Biome>>)palette;
               if (paletteIdentities.put(mutable, Boolean.TRUE) != null) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "biome publication requires unique native palettes");
               }
               biomes.add(mutable);
            }
            dirty = target.isUnsaved();
            // Preserve the complete original semantic volume. Target palette holders must
            // belong to the supplied registry even when foreign holders have matching ids.
            for (var palette : biomes) for (int r=0;r<64;r++) {
               Holder<Biome> holder = Objects.requireNonNull(palette.get(r/16,(r/4)%4,r%4), "original biome holder");
               if (!owned(holder, registry)) return stopped(WriteStatus.NO_WRITE_INVALID, "target biome palette belongs to another registry");
               oldHolders.add(holder);
            }
            TellusBiomeVolumePlan plan = Objects.requireNonNull(planner.plan(request), "biome volume plan");
            if (!plan.request().equals(request)) return stopped(WriteStatus.NO_WRITE_INVALID, "biome volume plan request differs");
            if (plan.status() == DecisionStatus.UNKNOWN) return stopped(WriteStatus.NO_WRITE_UNKNOWN, "biome volume is unknown");
            if (plan.status() == DecisionStatus.QUEUED) return stopped(WriteStatus.NO_WRITE_QUEUED, "biome volume is queued");
            if (plan.status() == DecisionStatus.PARTIAL) return stopped(WriteStatus.NO_WRITE_PARTIAL, "biome volume must be complete");
            if (plan.status() != DecisionStatus.AVAILABLE || plan.cells().size() != sectionCount * 64) {
               return stopped(WriteStatus.NO_WRITE_INVALID, "biome volume does not cover every target quart");
            }
            for (var cell : plan.cells()) {
               if (!cell.biome().known()) return stopped(WriteStatus.NO_WRITE_INVALID, "unknown biome cannot be mapped");
               ResourceLocation id = ResourceLocation.tryParse(cell.biome().id());
               if (id == null) return stopped(WriteStatus.NO_WRITE_INVALID, "invalid semantic biome id");
               var key = ResourceKey.create(Registries.BIOME, id);
               Holder.Reference<Biome> holder = registry.getHolder(key).orElse(null);
               if (holder == null || !owned(holder, registry) || !holder.unwrapKey().orElseThrow().equals(key)) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "semantic biome is absent from target registry");
               }
               mapped.add(holder);
            }
            var originalPalettes = new IdentityHashMap<PalettedContainer<Holder<Biome>>, Boolean>();
            var stagedPalettes = new IdentityHashMap<PalettedContainer<Holder<Biome>>, Boolean>();
            for (var palette : biomes) originalPalettes.put(palette, Boolean.TRUE);
            for (int section=0;section<sectionCount;section++) {
               PalettedContainer<Holder<Biome>> palette = Objects.requireNonNull(biomes.get(section).recreate(), "recreated biome palette");
               if (originalPalettes.containsKey(palette) || stagedPalettes.put(palette, Boolean.TRUE) != null) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "biome staging aliases a prepared palette");
               }
               for (int r=0;r<64;r++) palette.set(r/16,(r/4)%4,r%4,mapped.get(section*64+r));
               for (int r=0;r<64;r++) if (palette.get(r/16,(r/4)%4,r%4) != mapped.get(section*64+r)) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "staged biome palette did not retain mapped holder");
               }
               staged.add(palette);
            }
            lease.requireOwned(target);
            if (target.getSections() != backing || target.isUnsaved() != dirty) {
               return stopped(WriteStatus.NO_WRITE_INVALID, "target structure changed during read-only biome preparation");
            }
            for (int i=0;i<originals.length;i++) {
               if (backing[i] != originals[i] || originals[i].getStates() != states.get(i) || originals[i].getBiomes() != biomes.get(i)) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "target section changed during read-only biome preparation");
               }
               for (int r=0;r<64;r++) if (biomes.get(i).get(r/16,(r/4)%4,r%4) != oldHolders.get(i*64+r)) {
                  return stopped(WriteStatus.NO_WRITE_INVALID, "target biome contents changed during read-only preparation");
               }
            }
         } catch (RuntimeException error) {
            // All work above stages detached palettes only. No restorative writes are justified yet.
            return invalid("biome volume preparation failed before publication", error);
         }

         Throwable failure = null;
         try {
            for (int i=0;i<mapped.size();i++) {
               int section=i/64, r=i%64;
               Holder<Biome> holder=staged.get(section).get(r/16,(r/4)%4,r%4);
               biomes.get(section).set(r/16,(r/4)%4,r%4,holder);
               if (biomes.get(section).get(r/16,(r/4)%4,r%4) != holder) {
                  throw new IllegalStateException("target did not retain mapped biome holder");
               }
            }
            target.setUnsaved(true);
            if (!target.isUnsaved()) throw new IllegalStateException("target did not retain biome dirty flag");
         } catch (RuntimeException | Error error) { failure = error; }
         if (failure != null) {
            restore(target, backing, originals, states, biomes, oldHolders, dirty, failure);
            if (failure instanceof Error error) throw error;
            return invalid("biome publication failed and original semantic volume was restored", failure);
         }
         return new Result(WriteStatus.WRITTEN, mapped.size(), List.of("complete biome volume published without replacing native section or container identities"));
      }
   }

   private static boolean owned(Holder<Biome> holder, Registry<Biome> registry) {
      if (!(holder instanceof Holder.Reference<Biome>) || !holder.isBound()
         || !holder.canSerializeIn(registry.holderOwner()) || holder.unwrapKey().isEmpty()) return false;
      return registry.getId(holder.value()) >= 0 && registry.getHolder(holder.unwrapKey().orElseThrow()).orElse(null) == holder;
   }

   private static void restore(ChunkAccess target, LevelChunkSection[] backing, LevelChunkSection[] originals,
                               List<PalettedContainer<BlockState>> states, List<PalettedContainer<Holder<Biome>>> biomes,
                               List<Holder<Biome>> oldHolders, boolean dirty, Throwable cause) {
      Throwable failures = null;
      for (int i=oldHolders.size()-1;i>=0;i--) {
         try {
            int section=i/64, r=i%64;
            biomes.get(section).set(r/16,(r/4)%4,r%4,oldHolders.get(i));
            if (biomes.get(section).get(r/16,(r/4)%4,r%4) != oldHolders.get(i)) throw new IllegalStateException("biome quart rollback readback differs");
         } catch (RuntimeException | Error error) { failures = aggregate(failures, error); }
      }
      for (int i=originals.length-1;i>=0;i--) {
         try {
            if (target.getSections() != backing || backing[i] != originals[i]
               || originals[i].getStates() != states.get(i) || originals[i].getBiomes() != biomes.get(i)) {
               throw new IllegalStateException("biome section/container identity changed during publication");
            }
         } catch (RuntimeException | Error error) { failures = aggregate(failures, error); }
      }
      try {
         target.setUnsaved(dirty);
         if (target.isUnsaved() != dirty) throw new IllegalStateException("biome dirty rollback readback differs");
      } catch (RuntimeException | Error error) { failures = aggregate(failures, error); }
      if (failures != null) {
         IllegalStateException incomplete = new IllegalStateException("biome volume rollback incomplete", cause);
         incomplete.addSuppressed(failures);
         throw incomplete;
      }
   }

   private static Throwable aggregate(Throwable current, Throwable next) {
      if (current == null) return next;
      if (current != next && java.util.Arrays.stream(current.getSuppressed()).noneMatch(value -> value == next)) current.addSuppressed(next);
      return current;
   }
   private static Result invalid(String diagnostic, Throwable error) { return stopped(WriteStatus.NO_WRITE_INVALID, diagnostic + ": " + error.getClass().getSimpleName()); }
   private static Result stopped(WriteStatus status, String diagnostic) { return new Result(status,0,List.of(diagnostic)); }
}
