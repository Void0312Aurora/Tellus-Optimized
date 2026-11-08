package com.yucareux.tellus.worldgen.global;

/**
 * Typed owner metadata for one coordinate context.
 *
 * <p>An owner view identifies where data is authoritative; it does not imply
 * that the current engine position is physically located at that owner view.
 * In particular, an apron is a derived rendering/synchronization view of a
 * canonical Cube-Net owner.</p>
 */
public sealed interface GeneratorOwnerView
   permits EarthOwnerView, SphericalOwnerView, CubeNetCanonicalOwnerView, CubeNetApronOwnerView {

   GeneratorOwner kind();
}
