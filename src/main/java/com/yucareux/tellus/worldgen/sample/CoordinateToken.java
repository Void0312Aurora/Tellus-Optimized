package com.yucareux.tellus.worldgen.sample;

import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetCanonicalOwnerView;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.GeneratorOwnerView;
import com.yucareux.tellus.worldgen.global.SphericalOwnerView;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.Objects;

/**
 * Structured coordinate identity derived from a validated coordinate context.
 * Callers do not supply a free-form coordinate string; cache identity is
 * derived from the owner, projected floors, noise pair and view bits.
 */
public record CoordinateToken(
    GeneratorOwner owner,
    int face,
    long blockX,
    long blockZ,
    long chunkX,
    long chunkZ,
    long earthX,
    long earthZ,
    int noiseX,
    int noiseZ,
    long viewXBits,
    long viewZBits
) {
    public CoordinateToken {
        Objects.requireNonNull(owner, "owner");
        if (face < -1 || face >= 6) throw new IllegalArgumentException("face");
    }

    public static CoordinateToken fromContext(TellusCoordinateContext context) {
        Objects.requireNonNull(context, "context");
        GeneratorOwnerView ownerView = context.owner();
        long earthX = context.earth().floorBlockX();
        long earthZ = context.earth().floorBlockZ();
        int noiseX = context.sphericalNoise().noiseX();
        int noiseZ = context.sphericalNoise().noiseZ();
        if (ownerView instanceof EarthOwnerView) {
            return new CoordinateToken(GeneratorOwner.EARTH_PROJECTED, -1,
                earthX, earthZ, Long.MIN_VALUE, Long.MIN_VALUE,
                earthX, earthZ, noiseX, noiseZ, 0L, 0L);
        }
        if (ownerView instanceof SphericalOwnerView spherical) {
            var block = spherical.block();
            var chunk = spherical.chunk();
            return new CoordinateToken(GeneratorOwner.SPHERE_FACE, block.face(),
                block.blockX(), block.blockZ(), chunk.chunkX(), chunk.chunkZ(),
                earthX, earthZ, noiseX, noiseZ, 0L, 0L);
        }
        CubeNetCanonicalOwnerView canonical = ownerView instanceof CubeNetApronOwnerView apron
            ? apron.canonicalOwner()
            : (CubeNetCanonicalOwnerView)ownerView;
        var block = canonical.block();
        var chunk = canonical.chunk();
        var view = ownerView instanceof CubeNetApronOwnerView apron
            ? apron.proxyPoint()
            : canonical.planarPoint();
        return new CoordinateToken(ownerView.kind(), block.face(),
            block.blockX(), block.blockZ(), chunk.chunkX(), chunk.chunkZ(),
            earthX, earthZ, noiseX, noiseZ,
            Double.doubleToLongBits(view.x()), Double.doubleToLongBits(view.z()));
    }
}
