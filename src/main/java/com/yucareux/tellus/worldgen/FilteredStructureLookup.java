package com.yucareux.tellus.worldgen;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet.Named;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Holder lookup view that exposes only structure sets admitted by a policy. */
public final class FilteredStructureLookup implements HolderLookup<StructureSet> {
   private final HolderLookup<StructureSet> delegate;
   private final Predicate<Holder<StructureSet>> predicate;

   public FilteredStructureLookup(HolderLookup<StructureSet> delegate, Predicate<Holder<StructureSet>> predicate) {
      this.delegate = delegate;
      this.predicate = predicate;
   }

   public Stream<Reference<StructureSet>> listElements() {
      return Objects.requireNonNull(this.delegate.listElements().filter(this.predicate), "listElements");
   }

   public Stream<Named<StructureSet>> listTags() {
      return Objects.requireNonNull(this.delegate.listTags(), "listTags");
   }

   public Optional<Reference<StructureSet>> get(ResourceKey<StructureSet> key) {
      return Objects.requireNonNull(this.delegate.get(key), "getStructureSet");
   }

   public Optional<Named<StructureSet>> get(TagKey<StructureSet> tag) {
      return Objects.requireNonNull(this.delegate.get(tag), "getStructureSetTag");
   }
}
