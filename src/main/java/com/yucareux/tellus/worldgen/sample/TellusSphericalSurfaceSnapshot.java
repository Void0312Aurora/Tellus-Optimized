package com.yucareux.tellus.worldgen.sample;

import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import java.util.Objects;
import java.util.Optional;

/** Complete immutable spherical inputs; optional surface policy is resolved at the provider boundary. */
public record TellusSphericalSurfaceSnapshot(
   SphereChunkSampler.SurfaceData surface,
   Optional<CubeNetSurfacePolicy.Decision> surfaceDecision,
   Optional<SphereChunkSampler.Column> nativeColumn
) {
   public TellusSphericalSurfaceSnapshot {
      Objects.requireNonNull(surface, "surface");
      surfaceDecision = Objects.requireNonNull(surfaceDecision, "surfaceDecision");
      nativeColumn = Objects.requireNonNull(nativeColumn, "nativeColumn");
      nativeColumn.ifPresent(column -> {
         var point = column.point();
         if (!column.data().equals(surface) || !Double.isFinite(point.latitude())
            || !Double.isFinite(point.longitude()) || !Double.isFinite(point.elevationMeters())
            || Math.abs(point.latitude()) > 90.0 || Math.abs(point.longitude()) > 180.0) {
            throw new IllegalArgumentException("native spherical column differs from snapshot or has invalid source point");
         }
      });
   }

   /** Source-compatible legacy snapshot; its rules continue to use request coordinates. */
   public TellusSphericalSurfaceSnapshot(SphereChunkSampler.SurfaceData surface,
                                       Optional<CubeNetSurfacePolicy.Decision> surfaceDecision) {
      this(surface, surfaceDecision, Optional.empty());
   }

   public static TellusSphericalSurfaceSnapshot resolvedNativeColumn(
      SphereChunkSampler.Column column, CubeNetSurfacePolicy.Decision decision
   ) {
      Objects.requireNonNull(column, "column");
      return new TellusSphericalSurfaceSnapshot(column.data(), Optional.of(Objects.requireNonNull(decision, "decision")),
         Optional.of(column));
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
