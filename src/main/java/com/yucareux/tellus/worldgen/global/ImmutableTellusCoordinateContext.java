package com.yucareux.tellus.worldgen.global;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable implementation of {@link TellusCoordinateContext}.
 *
 * <p>This record intentionally performs no sampling, caching, generator
 * access or owner resolution. Callers construct it only after resolving the
 * owner at their engine boundary.</p>
 */
public record ImmutableTellusCoordinateContext(
   SphereWorldModel.GeoPoint geo,
   EarthCompatibilityPoint earth,
   SphericalNoisePoint sphericalNoise,
   GeneratorOwnerView owner,
   Optional<CubeNetLayout.FacePoint> facePoint,
   Optional<CubeNetLayout.PlanarPoint> planarPoint
) implements TellusCoordinateContext {
   public ImmutableTellusCoordinateContext {
      Objects.requireNonNull(geo, "geo");
      Objects.requireNonNull(earth, "earth");
      Objects.requireNonNull(sphericalNoise, "sphericalNoise");
      Objects.requireNonNull(owner, "owner");
      facePoint = normalize(facePoint);
      planarPoint = normalize(planarPoint);

      if (owner instanceof CubeNetCanonicalOwnerView canonical) {
         requireCubeNetPoints(facePoint, planarPoint);
         if (!facePoint.get().equals(canonical.facePoint())
            || !planarPoint.get().equals(canonical.planarPoint())) {
            throw new IllegalArgumentException("context points do not match canonical owner");
         }
      } else if (owner instanceof CubeNetApronOwnerView apron) {
         requireCubeNetPoints(facePoint, planarPoint);
         if (!facePoint.get().equals(apron.canonicalOwner().facePoint())
            || !planarPoint.get().equals(apron.proxyPoint())) {
            throw new IllegalArgumentException("context points do not match apron owner");
         }
      } else if (facePoint.isPresent() || planarPoint.isPresent()) {
         throw new IllegalArgumentException("non-Cube-Net owner cannot carry Cube-Net view points");
      }
   }

   /** Constructs a context with no Cube-Net view coordinates. */
   public static ImmutableTellusCoordinateContext withoutCubeNetView(
      SphereWorldModel.GeoPoint geo,
      EarthCompatibilityPoint earth,
      SphericalNoisePoint sphericalNoise,
      GeneratorOwnerView owner
   ) {
      return new ImmutableTellusCoordinateContext(
         geo, earth, sphericalNoise, owner, Optional.empty(), Optional.empty()
      );
   }

   /** Constructs a context carrying the canonical Cube-Net face and planar view. */
   public static ImmutableTellusCoordinateContext cubeNetView(
      SphereWorldModel.GeoPoint geo,
      EarthCompatibilityPoint earth,
      SphericalNoisePoint sphericalNoise,
      GeneratorOwnerView owner,
      CubeNetLayout.FacePoint facePoint,
      CubeNetLayout.PlanarPoint planarPoint
   ) {
      return new ImmutableTellusCoordinateContext(
         geo, earth, sphericalNoise, owner,
         Optional.of(Objects.requireNonNull(facePoint, "facePoint")),
         Optional.of(Objects.requireNonNull(planarPoint, "planarPoint"))
      );
   }

   private static <T> Optional<T> normalize(Optional<T> value) {
      return value == null ? Optional.empty() : value;
   }

   private static void requireCubeNetPoints(
      Optional<CubeNetLayout.FacePoint> facePoint,
      Optional<CubeNetLayout.PlanarPoint> planarPoint
   ) {
      if (facePoint.isEmpty() || planarPoint.isEmpty()) {
         throw new IllegalArgumentException("Cube-Net owner requires face and planar points");
      }
   }
}
