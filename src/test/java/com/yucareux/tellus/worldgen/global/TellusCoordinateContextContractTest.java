package com.yucareux.tellus.worldgen.global;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.lang.reflect.Modifier;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for Phase 2 coordinate and ownership value objects. */
class TellusCoordinateContextContractTest {
    private static final CubeNetLayout LAYOUT = CubeNetLayout.standard(64);

    @Test
    void geoPointClampsLatitudeAndNormalizesLongitudeAtBoundaries() {
        SphereWorldModel.GeoPoint point = new SphereWorldModel.GeoPoint(123.0, -540.0, -250.5);
        assertEquals(90.0, point.latitude());
        assertEquals(180.0, point.longitude());
        assertEquals(-250.5, point.elevationMeters());

        SphereWorldModel.GeoPoint negativeLongitude = new SphereWorldModel.GeoPoint(0.0, -180.0, 0.0);
        SphereWorldModel.GeoPoint positiveLongitude = new SphereWorldModel.GeoPoint(0.0, 180.0, 0.0);
        assertEquals(positiveLongitude, negativeLongitude);
        assertThrows(IllegalArgumentException.class,
            () -> new SphereWorldModel.GeoPoint(Double.NaN, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
            () -> new SphereWorldModel.GeoPoint(0.0, Double.POSITIVE_INFINITY, 0.0));
    }

    @Test
    void earthCompatibilityPointKeepsProjectedSemanticsAndFloorForNegativeCoordinates() {
        EarthCompatibilityPoint point = new EarthCompatibilityPoint(
            -0.1, -16.1, 30.0, "legacy", true);
        assertEquals(-1L, point.floorBlockX());
        assertEquals(-17L, point.floorBlockZ());
        assertEquals("legacy", point.projectionModeId());
        assertTrue(point.withinMercatorCoverage());

        EarthCompatibilityPoint derived = EarthCompatibilityPoint.fromGeoPoint(
            new SphereWorldModel.GeoPoint(-10.0, -20.0, 0.0), 30.0);
        assertEquals(EarthProjection.projectionModeId(), derived.projectionModeId());
        assertEquals(30.0, derived.worldScale());
        assertTrue(Double.isFinite(derived.blockX()));
        assertTrue(Double.isFinite(derived.blockZ()));
        assertThrows(IllegalArgumentException.class,
            () -> new EarthCompatibilityPoint(0.0, 0.0, 0.0, "legacy", true));
        assertThrows(IllegalArgumentException.class,
            () -> new EarthCompatibilityPoint(0.0, 0.0, 1.0, " ", true));
    }

    @Test
    void sphericalNoisePointIsUnitLengthDeterministicAndVersioned() {
        SphereWorldModel.GeoPoint dateLine = new SphereWorldModel.GeoPoint(0.0, -180.0, 0.0);
        SphericalNoisePoint first = SphericalNoisePoint.fromGeoPoint(dateLine);
        SphericalNoisePoint second = SphericalNoisePoint.fromGeoPoint(
            new SphereWorldModel.GeoPoint(0.0, 180.0, 999.0));
        assertEquals(first, second);
        assertEquals(SphericalNoisePoint.DEFAULT_ALGORITHM_ID, first.algorithmId());
        assertEquals(1.0, Math.sqrt(first.unitX() * first.unitX()
            + first.unitY() * first.unitY() + first.unitZ() * first.unitZ()), 1.0E-12);

        SphericalNoisePoint named = SphericalNoisePoint.fromGeoPoint(dateLine, "test:noise-v2");
        assertEquals("test:noise-v2", named.algorithmId());
        assertEquals(first.noiseX(), named.noiseX());
        assertThrows(IllegalArgumentException.class,
            () -> new SphericalNoisePoint(1.0, 1.0, 1.0, 0, 0, "test"));
        assertThrows(IllegalArgumentException.class,
            () -> new SphericalNoisePoint(1.0, 0.0, 0.0, 0, 0, " "));
    }

    @Test
    void sphericalOwnerValidatesFaceChunkAgreementIncludingNegativeCoordinates() {
        SphereWorldModel.SphereBlockAddress block =
            new SphereWorldModel.SphereBlockAddress(2, -1L, -17L);
        SphereWorldModel.SphereChunkAddress chunk =
            new SphereWorldModel.SphereChunkAddress(2, -1L, -2L);
        SphericalOwnerView owner = new SphericalOwnerView(block, chunk);
        assertEquals(GeneratorOwner.SPHERE_FACE, owner.kind());

        assertThrows(IllegalArgumentException.class, () -> new SphericalOwnerView(
            block, new SphereWorldModel.SphereChunkAddress(3, -1L, -2L)));
        assertThrows(IllegalArgumentException.class, () -> new SphericalOwnerView(
            block, new SphereWorldModel.SphereChunkAddress(2, 0L, -2L)));
        assertThrows(IllegalArgumentException.class, () -> new SphericalOwnerView(
            new SphereWorldModel.SphereBlockAddress(-1, 0L, 0L),
            new SphereWorldModel.SphereChunkAddress(-1, 0L, 0L)));
    }

    @Test
    void canonicalAndApronOwnersKeepDistinctKindsAndCanonicalIdentity() {
        SphereWorldModel.SphereBlockAddress block =
            new SphereWorldModel.SphereBlockAddress(0, 16L, 32L);
        SphereWorldModel.SphereChunkAddress chunk =
            new SphereWorldModel.SphereChunkAddress(0, 1L, 2L);
        CubeNetLayout.FacePoint facePoint =
            new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 0.5, 0.5);
        CubeNetLayout.PlanarPoint planarPoint = LAYOUT.toPlanar(facePoint);
        CubeNetCanonicalOwnerView canonical = new CubeNetCanonicalOwnerView(
            block, chunk, facePoint, planarPoint);
        assertEquals(GeneratorOwner.CUBE_NET_CANONICAL, canonical.kind());

        CubeNetApronResolver.ApronAddress blockProjection = new CubeNetApronResolver.ApronAddress(
            CubeNetLayout.Face.NEG_X, CubeNetLayout.Side.WEST,
            CubeNetLayout.Face.POS_X, 0.5, 0.5, 16, 32, 0.5, 1.0);
        CubeNetApronResolver.ChunkProjection chunkProjection = new CubeNetApronResolver.ChunkProjection(
            CubeNetLayout.Face.NEG_X, CubeNetLayout.Side.WEST,
            CubeNetLayout.Face.POS_X, 0, 0, 1, 2, 16, 32, 1, 0, 0, 1);
        CubeNetApronOwnerView apron = new CubeNetApronOwnerView(
            new CubeNetLayout.PlanarPoint(100.0, 200.0), blockProjection, chunkProjection, canonical);
        assertEquals(GeneratorOwner.CUBE_NET_APRON, apron.kind());
        assertEquals(canonical, apron.canonicalOwner());

        CubeNetLayout.FacePoint wrongFace =
            new CubeNetLayout.FacePoint(CubeNetLayout.Face.NEG_X, 0.5, 0.5);
        assertThrows(IllegalArgumentException.class, () -> new CubeNetCanonicalOwnerView(
            block, chunk, wrongFace, planarPoint));
        assertThrows(IllegalArgumentException.class, () -> new CubeNetApronOwnerView(
            new CubeNetLayout.PlanarPoint(100.0, 200.0),
            new CubeNetApronResolver.ApronAddress(
                CubeNetLayout.Face.NEG_X, CubeNetLayout.Side.WEST,
                CubeNetLayout.Face.POS_X, 0.5, 0.5, 17, 32, 0.5, 1.0),
            chunkProjection, canonical));
    }

    @Test
    void coordinateContextEnforcesOwnerSpecificCubeNetPointsAndNormalizesNullOptionals() {
        SphereWorldModel.GeoPoint geo = new SphereWorldModel.GeoPoint(20.0, 30.0, 10.0);
        EarthCompatibilityPoint earth = EarthCompatibilityPoint.fromGeoPoint(geo, 30.0);
        SphericalNoisePoint noise = SphericalNoisePoint.fromGeoPoint(geo);
        EarthOwnerView earthOwner = new EarthOwnerView(earth);

        ImmutableTellusCoordinateContext earthContext = new ImmutableTellusCoordinateContext(
            geo, earth, noise, earthOwner, null, null);
        assertEquals(GeneratorOwner.EARTH_PROJECTED, earthContext.owner().kind());
        assertTrue(earthContext.facePoint().isEmpty());
        assertTrue(earthContext.planarPoint().isEmpty());

        CubeNetCanonicalOwnerView canonical = canonicalOwner();
        ImmutableTellusCoordinateContext cubeContext = ImmutableTellusCoordinateContext.cubeNetView(
            geo, earth, noise, canonical, canonical.facePoint(), canonical.planarPoint());
        assertEquals(canonical.facePoint(), cubeContext.facePoint().orElseThrow());
        assertEquals(canonical.planarPoint(), cubeContext.planarPoint().orElseThrow());

        assertThrows(IllegalArgumentException.class, () -> new ImmutableTellusCoordinateContext(
            geo, earth, noise, canonical, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ImmutableTellusCoordinateContext(
            geo, earth, noise, earthOwner,
            Optional.of(canonical.facePoint()), Optional.of(canonical.planarPoint())));
        assertThrows(IllegalArgumentException.class, () -> new ImmutableTellusCoordinateContext(
            geo, earth, noise, canonical,
            Optional.of(new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 1.5, 1.5)),
            Optional.of(canonical.planarPoint())));
    }

    @Test
    void coordinateAndOwnerRecordsAreImmutableFinalValueObjects() {
        Class<?>[] records = {
            EarthCompatibilityPoint.class,
            SphericalNoisePoint.class,
            EarthOwnerView.class,
            SphericalOwnerView.class,
            CubeNetCanonicalOwnerView.class,
            CubeNetApronOwnerView.class,
            ImmutableTellusCoordinateContext.class
        };
        for (Class<?> type : records) {
            assertTrue(type.isRecord(), type.getName());
            assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
            assertFalse(java.util.Arrays.stream(type.getMethods())
                .anyMatch(method -> method.getName().startsWith("set")), type.getName());
        }
        assertTrue(GeneratorOwnerView.class.isSealed());
        assertNotNull(GeneratorOwnerView.class.getPermittedSubclasses());
        assertEquals(4, GeneratorOwnerView.class.getPermittedSubclasses().length);
        assertInstanceOf(EarthOwnerView.class, new EarthOwnerView(
            EarthCompatibilityPoint.fromGeoPoint(new SphereWorldModel.GeoPoint(0.0, 0.0, 0.0), 1.0)));
    }

    private static CubeNetCanonicalOwnerView canonicalOwner() {
        SphereWorldModel.SphereBlockAddress block =
            new SphereWorldModel.SphereBlockAddress(0, 16L, 32L);
        SphereWorldModel.SphereChunkAddress chunk =
            new SphereWorldModel.SphereChunkAddress(0, 1L, 2L);
        CubeNetLayout.FacePoint facePoint =
            new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 0.5, 0.5);
        return new CubeNetCanonicalOwnerView(block, chunk, facePoint, LAYOUT.toPlanar(facePoint));
    }
}
