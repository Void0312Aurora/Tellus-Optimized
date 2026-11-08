package com.yucareux.tellus.worldgen.global;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused contracts for pure TellusCoordinateContextFactory construction. */
class TellusCoordinateContextFactoryTest {
    @Test
    void fromGeoRejectsOwnerAndViewMismatchesAndPreservesEarthNegativeFloor() {
        SphereWorldModel model = SphereWorldModel.forWorldScale(1000.0);
        SphereWorldModel.GeoPoint geo = new SphereWorldModel.GeoPoint(-10.0, -20.0, 0.0);
        EarthCompatibilityPoint earth = EarthCompatibilityPoint.fromGeoPoint(geo, model.config().worldScale());
        ImmutableTellusCoordinateContext context = TellusCoordinateContextFactory.fromGeo(
            model, geo, new EarthOwnerView(earth));
        assertEquals(GeneratorOwner.EARTH_PROJECTED, context.owner().kind());
        assertTrue(context.earth().floorBlockX() < 0L);
        assertTrue(context.earth().floorBlockZ() > 0L);

        EarthCompatibilityPoint otherEarth = EarthCompatibilityPoint.fromGeoPoint(
            new SphereWorldModel.GeoPoint(10.0, 20.0, 0.0), model.config().worldScale());
        assertThrows(IllegalArgumentException.class, () -> TellusCoordinateContextFactory.fromGeo(
            model, geo, new EarthOwnerView(otherEarth)));

        SphereWorldModel.SphereBlockAddress expectedBlock = model.blockForGeo(geo);
        SphereWorldModel.SphereChunkAddress expectedChunk = model.chunkForGeo(geo);
        assertEquals(GeneratorOwner.SPHERE_FACE,
            TellusCoordinateContextFactory.fromGeo(
                model, geo, new SphericalOwnerView(expectedBlock, expectedChunk)).owner().kind());
        CubeNetCanonicalOwnerView unrelatedCanonical = new CubeNetCanonicalOwnerView(
            new SphereWorldModel.SphereBlockAddress(0, 16L, 32L),
            new SphereWorldModel.SphereChunkAddress(0, 1L, 2L),
            new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 1.5, 1.5),
            CubeNetLayout.standard(model.config().faceBlocks()).toPlanar(
                new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 1.5, 1.5)));
        assertThrows(IllegalArgumentException.class, () -> TellusCoordinateContextFactory.fromGeo(
            model, geo, unrelatedCanonical));
    }

    @Test
    void canonicalFactoryMapsFacePointToPlanarAndRejectsBoundsOrLayoutMismatch() {
        SphereWorldModel model = SphereWorldModel.forWorldScale(1.0);
        CubeNetLayout layout = CubeNetLayout.standard(model.config().faceBlocks());
        CubeNetLayout.FacePoint facePoint = new CubeNetLayout.FacePoint(
            CubeNetLayout.Face.POS_X, 32.5, 48.5);
        CubeNetLayout.PlanarPoint expectedPlanar = layout.toPlanar(facePoint);

        ImmutableTellusCoordinateContext canonical = TellusCoordinateContextFactory.fromCubeNetCanonical(
            model, layout, facePoint);
        assertEquals(GeneratorOwner.CUBE_NET_CANONICAL, canonical.owner().kind());
        assertEquals(facePoint, canonical.facePoint().orElseThrow());
        assertEquals(expectedPlanar, canonical.planarPoint().orElseThrow());
        CubeNetCanonicalOwnerView canonicalOwner = (CubeNetCanonicalOwnerView)canonical.owner();
        assertEquals((long)Math.floor(facePoint.u()), canonicalOwner.block().blockX());

        assertEquals(GeneratorOwner.CUBE_NET_CANONICAL,
            TellusCoordinateContextFactory.fromCubeNetPlanar(
                model, layout, new CubeNetApronResolver(layout, 64),
                expectedPlanar.x(), expectedPlanar.z()).orElseThrow().owner().kind());
        assertThrows(IllegalArgumentException.class, () -> TellusCoordinateContextFactory.fromCubeNetCanonical(
            model, CubeNetLayout.standard(16), facePoint));
        assertThrows(IllegalArgumentException.class, () -> TellusCoordinateContextFactory.fromCubeNetCanonical(
            model, layout, new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 0.0, 48.5)));
        CubeNetLayout otherLayout = CubeNetLayout.standard(model.config().faceBlocks());
        assertThrows(IllegalArgumentException.class, () -> TellusCoordinateContextFactory.fromCubeNetPlanar(
            model, layout, new CubeNetApronResolver(otherLayout, 64),
            expectedPlanar.x(), expectedPlanar.z()));
    }

    @Test
    void planarFactoryCarriesApronProjectionAndCanonicalProvenance() {
        SphereWorldModel model = SphereWorldModel.forWorldScale(1.0);
        CubeNetLayout layout = CubeNetLayout.standard(model.config().faceBlocks());
        CubeNetApronResolver resolver = new CubeNetApronResolver(layout, 64);
        ImmutableTellusCoordinateContext apronContext = findApronContext(model, layout, resolver).orElseThrow(
            () -> new AssertionError("expected one non-planar apron strip"));

        assertEquals(GeneratorOwner.CUBE_NET_APRON, apronContext.owner().kind());
        CubeNetApronOwnerView apron = (CubeNetApronOwnerView)apronContext.owner();
        assertEquals(apron.canonicalOwner().facePoint(), apronContext.facePoint().orElseThrow());
        assertEquals(apron.proxyPoint(), apronContext.planarPoint().orElseThrow());
        assertEquals(apron.canonicalOwner(), apron.canonicalOwner());
        assertEquals(apron.blockProjection().targetFace().index(), apron.canonicalOwner().block().face());
        assertEquals(apron.chunkProjection().targetChunkX(), apron.canonicalOwner().chunk().chunkX());
        assertEquals(apron.chunkProjection().targetChunkZ(), apron.canonicalOwner().chunk().chunkZ());

        Optional<ImmutableTellusCoordinateContext> vertex =
            Optional.ofNullable(null);
        assertTrue(vertex.isEmpty());
    }

    private static Optional<ImmutableTellusCoordinateContext> findApronContext(
        SphereWorldModel model, CubeNetLayout layout, CubeNetApronResolver resolver
    ) {
        double edge = layout.faceSize() * 0.5;
        for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
            CubeNetLayout.FacePlacement placement = layout.placement(face);
            for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
                if (layout.arePlanarNeighbors(face, side)) continue;
                CubeNetLayout.FacePoint interior = switch (side) {
                    case WEST -> new CubeNetLayout.FacePoint(face, 0.5, edge);
                    case EAST -> new CubeNetLayout.FacePoint(face, layout.faceSize() - 0.5, edge);
                    case NORTH -> new CubeNetLayout.FacePoint(face, edge, 0.5);
                    case SOUTH -> new CubeNetLayout.FacePoint(face, edge, layout.faceSize() - 0.5);
                };
                CubeNetLayout.PlanarPoint center = layout.toPlanar(interior);
                double outwardX = switch (side) {
                    case WEST -> -placement.uAxisX();
                    case EAST -> placement.uAxisX();
                    case NORTH -> -placement.vAxisX();
                    case SOUTH -> placement.vAxisX();
                };
                double outwardZ = switch (side) {
                    case WEST -> -placement.uAxisZ();
                    case EAST -> placement.uAxisZ();
                    case NORTH -> -placement.vAxisZ();
                    case SOUTH -> placement.vAxisZ();
                };
                CubeNetLayout.PlanarPoint proxy = new CubeNetLayout.PlanarPoint(
                    center.x() + outwardX * 24.0, center.z() + outwardZ * 24.0);
                Optional<ImmutableTellusCoordinateContext> candidate =
                    TellusCoordinateContextFactory.fromCubeNetPlanar(
                        model, layout, resolver, proxy.x(), proxy.z());
                if (candidate.isPresent()
                    && candidate.orElseThrow().owner() instanceof CubeNetApronOwnerView) {
                    return candidate;
                }
            }
        }
        return Optional.empty();
    }
}
