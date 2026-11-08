package com.yucareux.tellus.worldgen;

/**
 * Shared semantic surface-variant roll seam. The distribution and clustering
 * remain owned by SurfacePatchNoise; this wrapper only gives Earth and
 * spherical/Cube-Net policies one stable call boundary.
 */
public final class TellusSurfaceVariantRoll {
    private TellusSurfaceVariantRoll() {
    }

    public static int percent(int worldX, int worldZ, long salt) {
        return SurfacePatchNoise.percent(worldX, worldZ, salt);
    }

    public static boolean covers(int worldX, int worldZ, long salt, double coveragePercent) {
        return SurfacePatchNoise.covers(worldX, worldZ, salt, coveragePercent);
    }
}
