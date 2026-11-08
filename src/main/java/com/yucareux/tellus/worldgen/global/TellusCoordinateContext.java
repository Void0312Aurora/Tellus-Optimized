package com.yucareux.tellus.worldgen.global;

import java.util.Optional;

/**
 * Typed coordinate bundle for one request/column.
 *
 * <p>The three coordinate views describe the same physical point for their
 * respective consumers. The owner view identifies which generator/address is
 * authoritative; it does not make planar Cube-Net coordinates canonical.</p>
 */
public interface TellusCoordinateContext {
   SphereWorldModel.GeoPoint geo();

   EarthCompatibilityPoint earth();

   SphericalNoisePoint sphericalNoise();

   GeneratorOwnerView owner();

   /** Canonical face-local point when the request is backed by a Cube-Net view. */
   Optional<CubeNetLayout.FacePoint> facePoint();

   /** Current planar view point when the request entered through a Cube-Net view. */
   Optional<CubeNetLayout.PlanarPoint> planarPoint();
}
