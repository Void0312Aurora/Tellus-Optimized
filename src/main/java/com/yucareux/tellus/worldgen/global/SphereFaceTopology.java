package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/**
 * Maps engine-local chunk coordinates to a cubed-sphere face and resolves
 * cardinal neighbors, including transitions across a face edge.
 *
 * <p>This is an address adapter only. It does not mutate Minecraft dimensions
 * or teleport entities; those engine operations must use this mapping as their
 * authoritative neighbor relation.</p>
 */
public final class SphereFaceTopology {
   private final SphereWorldModel model;
   private final long halfChunks;

   public SphereFaceTopology(SphereWorldModel model) {
      this.model = Objects.requireNonNull(model, "model");
      this.halfChunks = model.chunksPerFace() / 2L;
   }

   public SphereWorldModel model() {
      return this.model;
   }

   public double minEngineBlock() { return -this.halfChunks * 16.0; }

   public double maxEngineBlock() { return this.model.config().faceBlocks() + this.minEngineBlock(); }

   public boolean containsEnginePoint(double x, double z) {
      return Double.isFinite(x) && Double.isFinite(z)
         && x >= this.minEngineBlock() && x < this.maxEngineBlock()
         && z >= this.minEngineBlock() && z < this.maxEngineBlock();
   }

   /** Continuous coordinates for teleportation and entity motion, with no cell rounding. */
   public EnginePoint enginePoint(SphereWorldModel.ContinuousFaceCoordinate coordinate) {
      return new EnginePoint(coordinate.face(), coordinate.blockX() + this.minEngineBlock(),
         coordinate.blockZ() + this.minEngineBlock());
   }

   public SphereWorldModel.FaceTransition transition(int face, double x, double z) {
      return this.model.transition(face, x - this.minEngineBlock(), z - this.minEngineBlock());
   }

   /** Converts an engine-local coordinate in one face to its canonical address. */
   public SphereWorldModel.SphereChunkAddress address(int face, long engineChunkX, long engineChunkZ) {
      validateEngineCoordinate(engineChunkX, engineChunkZ);
      var address = new SphereWorldModel.SphereChunkAddress(
         face, engineChunkX + this.halfChunks, engineChunkZ + this.halfChunks
      );
      this.model.chartForChunk(address);
      return address;
   }

   public SphereWorldModel.SphereBlockAddress blockAddress(int face, long engineBlockX, long engineBlockZ) {
      validateEngineCoordinate(Math.floorDiv(engineBlockX, 16L), Math.floorDiv(engineBlockZ, 16L));
      long offset = this.halfChunks * 16L;
      var address = new SphereWorldModel.SphereBlockAddress(face, engineBlockX + offset, engineBlockZ + offset);
      this.model.chartForBlock(face, address.blockX(), address.blockZ());
      return address;
   }

   public boolean containsEngineChunk(long chunkX, long chunkZ) {
      long end = this.model.chunksPerFace() - this.halfChunks;
      return chunkX >= -this.halfChunks && chunkX < end && chunkZ >= -this.halfChunks && chunkZ < end;
   }

   /** Converts a canonical face address to the engine-local coordinate used by that face. */
   public EngineChunkCoordinate engineCoordinate(SphereWorldModel.SphereChunkAddress address) {
      Objects.requireNonNull(address, "address");
      this.model.chartForChunk(address);
      return new EngineChunkCoordinate(
         address.face(), address.chunkX() - this.halfChunks, address.chunkZ() - this.halfChunks
      );
   }

   /** Resolves a cardinal chunk neighbor on the same or an adjacent face. */
   public SphereWorldModel.SphereChunkAddress neighbor(
      SphereWorldModel.SphereChunkAddress source, int deltaX, int deltaZ
   ) {
      Objects.requireNonNull(source, "source");
      if (Math.abs(deltaX) + Math.abs(deltaZ) != 1) {
         throw new IllegalArgumentException("only cardinal neighbors are supported");
      }
      this.model.chartForChunk(source);
      long chunkX = source.chunkX(), chunkZ = source.chunkZ();
      long max = this.model.chunksPerFace() - 1L;
      if (chunkX + deltaX >= 0L && chunkX + deltaX <= max
         && chunkZ + deltaZ >= 0L && chunkZ + deltaZ <= max) {
         return new SphereWorldModel.SphereChunkAddress(source.face(), chunkX + deltaX, chunkZ + deltaZ);
      }

      long blockX = source.blockX() + (deltaX > 0 ? 15L : deltaX < 0 ? 0L : 8L);
      long blockZ = source.blockZ() + (deltaZ > 0 ? 15L : deltaZ < 0 ? 0L : 8L);
      SphereWorldModel.SphereBlockAddress mapped = this.model.neighbor(
         new SphereWorldModel.SphereBlockAddress(source.face(), blockX, blockZ), deltaX, deltaZ
      );
      SphereWorldModel.SphereChunkAddress result = new SphereWorldModel.SphereChunkAddress(
         mapped.face(), Math.floorDiv(mapped.blockX(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS),
         Math.floorDiv(mapped.blockZ(), (long)SphereWorldModel.CHUNK_SIZE_BLOCKS)
      );
      this.model.chartForChunk(result);
      return result;
   }

   /** Maps one edge block to the adjacent face's block cell. */
   public SphereWorldModel.SphereBlockAddress neighborBlock(
      SphereWorldModel.SphereBlockAddress source, int deltaX, int deltaZ
   ) {
      return this.model.neighbor(Objects.requireNonNull(source, "source"), deltaX, deltaZ);
   }

   private void validateEngineCoordinate(long chunkX, long chunkZ) {
      if (!this.containsEngineChunk(chunkX, chunkZ)) {
         throw new IllegalArgumentException("engine chunk coordinate is outside this face");
      }
   }

   public record EngineChunkCoordinate(int face, long chunkX, long chunkZ) { }

   public record EnginePoint(int face, double x, double z) { }
}
