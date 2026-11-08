package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.SphereFaceChunkGenerator;
import java.util.Set;
import java.util.Optional;

/** Explicit 1.20.1 Sphere-Face geometry-only settings projection. */
public final class SphereFaceGeneratorSettingsViewAdapter implements TellusGeneratorSettingsView {
   private final SphereFaceChunkGenerator generator;
   private final VerticalRange verticalRange;

   public SphereFaceGeneratorSettingsViewAdapter(SphereFaceChunkGenerator generator) {
      this.generator = java.util.Objects.requireNonNull(generator, "generator");
      this.verticalRange = new VerticalRange(generator.getMinY(), generator.getGenDepth(), generator.getGenDepth());
   }

   public SphereFaceChunkGenerator generator() {
      return this.generator;
   }

   @Override
   public String generatorId() {
      return "tellus:sphere_face";
   }

   @Override
   public Kind kind() {
      return Kind.SPHERE_FACE;
   }

   @Override
   public String geometryId() {
      return this.generator.model().mappingId();
   }

   @Override
   public double worldScale() {
      return this.generator.worldScale();
   }

   @Override
   public VerticalRange verticalRange() {
      return this.verticalRange;
   }

   @Override
   public int seaLevel() {
      return this.generator.getSeaLevel();
   }

   @Override
   public int heightOffset() {
      return this.generator.getSeaLevel();
   }

   @Override
   public Optional<SpawnPoint> spawn() {
      return Optional.empty();
   }

   @Override
   public Optional<TerrainOptions> terrain() {
      return Optional.empty();
   }

   @Override
   public Optional<StructureOptions> structures() {
      return Optional.empty();
   }

   @Override
   public Optional<RandomBiomeOptions> randomBiomes() {
      return Optional.empty();
   }

   @Override
   public Optional<RealtimeOptions> realtime() {
      return Optional.empty();
   }

   @Override
   public Optional<DistantHorizonsOptions> distantHorizons() {
      return Optional.empty();
   }

   @Override
   public Optional<VoxyOptions> voxy() {
      return Optional.empty();
   }

   @Override
   public Optional<ManagedTerrainOptions> managedTerrain() {
      return Optional.empty();
   }

   @Override
   public Optional<ExperimentalHeightOptions> experimentalHeight() {
      return Optional.empty();
   }

   @Override
   public Set<Capability> capabilities() {
      return Set.of();
   }
}
