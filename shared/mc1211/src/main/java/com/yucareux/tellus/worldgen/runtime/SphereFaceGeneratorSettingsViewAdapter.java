package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.SphereFaceChunkGenerator;
import java.util.Optional;
import java.util.Set;

/** 1.21.1 typed Sphere-Face projection; terrain policy remains codec-owned. */
public final class SphereFaceGeneratorSettingsViewAdapter implements TellusGeneratorSettingsView {
   private static final String SOURCE_LAYER = "mc1211";
   private final SphereFaceChunkGenerator generator;
   private final VerticalRange verticalRange;

   public SphereFaceGeneratorSettingsViewAdapter(SphereFaceChunkGenerator generator) {
      this.generator = java.util.Objects.requireNonNull(generator, "generator");
      this.verticalRange = new VerticalRange(generator.getMinY(), generator.getGenDepth(), generator.getGenDepth());
   }

   public String sourceLayer() { return SOURCE_LAYER; }
   public SphereFaceChunkGenerator generator() { return this.generator; }
   @Override public String generatorId() { return "tellus:sphere_face"; }
   @Override public Kind kind() { return Kind.SPHERE_FACE; }
   @Override public String geometryId() { return this.generator.model().mappingId(); }
   @Override public double worldScale() { return this.generator.worldScale(); }
   @Override public VerticalRange verticalRange() { return this.verticalRange; }
   @Override public int seaLevel() { return this.generator.getSeaLevel(); }
   @Override public int heightOffset() { return this.generator.getSeaLevel(); }
   @Override public Optional<SpawnPoint> spawn() { return Optional.empty(); }
   @Override public Optional<TerrainOptions> terrain() { return Optional.empty(); }
   @Override public Optional<StructureOptions> structures() { return Optional.empty(); }
   @Override public Optional<RandomBiomeOptions> randomBiomes() { return Optional.empty(); }
   @Override public Optional<RealtimeOptions> realtime() { return Optional.empty(); }
   @Override public Optional<DistantHorizonsOptions> distantHorizons() { return Optional.empty(); }
   @Override public Optional<VoxyOptions> voxy() { return Optional.empty(); }
   @Override public Optional<ManagedTerrainOptions> managedTerrain() { return Optional.empty(); }
   @Override public Optional<ExperimentalHeightOptions> experimentalHeight() { return Optional.empty(); }
   @Override public Set<Capability> capabilities() { return Set.of(); }
}
