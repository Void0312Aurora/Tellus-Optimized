package com.yucareux.tellus.worldgen.sample;

import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import java.util.Objects;
import java.util.Optional;

/** Complete immutable spherical inputs; optional surface policy is resolved at the provider boundary. */
public record TellusSphericalSurfaceSnapshot(
   SphereChunkSampler.SurfaceData surface,
   Optional<CubeNetSurfacePolicy.Decision> surfaceDecision
) {
   public TellusSphericalSurfaceSnapshot {
      Objects.requireNonNull(surface, "surface");
      surfaceDecision = Objects.requireNonNull(surfaceDecision, "surfaceDecision");
   }

   public static TellusSphericalSurfaceSnapshot raw(SphereChunkSampler.SurfaceData surface) {
      return new TellusSphericalSurfaceSnapshot(surface, Optional.empty());
   }

   public static TellusSphericalSurfaceSnapshot resolved(
      SphereChunkSampler.SurfaceData surface, CubeNetSurfacePolicy.Decision decision
   ) {
      return new TellusSphericalSurfaceSnapshot(surface, Optional.of(Objects.requireNonNull(decision, "decision")));
   }
}
