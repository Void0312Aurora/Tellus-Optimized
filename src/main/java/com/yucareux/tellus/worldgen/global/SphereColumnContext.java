package com.yucareux.tellus.worldgen.global;

import java.util.Objects;

/**
 * Complete geographic input for one canonical spherical terrain column.
 * Minecraft block coordinates remain a local write address; geographic
 * decisions should carry this context instead of reconstructing a point from
 * a planar Cube-Net coordinate.
 */
public record SphereColumnContext(
   SphereWorldModel.SphereBlockAddress address,
   SphereWorldModel.GeoPoint point,
   SphereChunkSampler.SurfaceData surface
) {
   public SphereColumnContext {
      Objects.requireNonNull(address, "address");
      Objects.requireNonNull(point, "point");
      Objects.requireNonNull(surface, "surface");
   }

   public boolean waterAvailable() {
      return this.surface.waterAvailable();
   }

   public boolean elevationAvailable() {
      return this.surface.elevationAvailable();
   }
}
