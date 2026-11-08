package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Focused Earth backend climate attachment contracts. */
class TellusWorldgenEarthSampleClimateTest {
    private static final double SCALE = EarthGeneratorSettings.DEFAULT.worldScale();
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:earth", "climate-backend-v1", "legacy-projection-v1", "legacy-noise-v1", SCALE);

    @BeforeAll
    static void bootstrap() {
        System.setProperty("tellus.gameDir", Path.of("build/tmp/earth-climate").toAbsolutePath().toString());
        System.setProperty("tellus.configDir", Path.of("build/tmp/earth-climate/config").toAbsolutePath().toString());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void attachesExistingDitheredKoppenWithoutChangingAggregateOrLocalOnlyProvenance() {
        TellusCoordinateContext coordinate = earth(0.0, 0.0);
        TellusSampleRequest request = TellusSampleRequest.fromContext(
            coordinate, SamplingPurpose.BIOME, SamplingResolution.exact(SCALE), GEOMETRY, 27L);
        TellusWorldgenEarthSampleBackend backend =
            new TellusWorldgenEarthSampleBackend(EarthGeneratorSettings.DEFAULT);

        TellusSample sample = backend.sample(request);
        String expectedCode = sample.status() == SampleStatus.UNKNOWN ? null
            : TellusWorldgenSources.koppen().sampleDitheredCode(
                (int)coordinate.earth().floorBlockX(), (int)coordinate.earth().floorBlockZ(), SCALE);

        assertEquals(SampleStatus.UNKNOWN.equals(sample.status())
            ? "unknown" : "snapshot", sample.provenance().snapshot() ? "snapshot" : "unknown");
        assertEquals(expectedCode, sample.climate().map(climate -> climate.koppenCode()).orElse(null));
        assertEquals(sample.status(), new TellusSample(request, sample.status(), sample.elevation(),
            sample.cover(), sample.water(), sample.provenance()).status());
        if (expectedCode != null) {
            assertTrue(sample.climate().isPresent());
            assertEquals(expectedCode, sample.climate().orElseThrow().code());
        } else {
            assertTrue(sample.climate().isEmpty());
        }
    }

    @Test
    void unsupportedEarthRequestRemainsUnknownWithoutClimateAttachment() {
        TellusCoordinateContext spherical = TellusCoordinateContextFactory.fromSphericalBlock(
            MODEL, new SphereWorldModel.SphereBlockAddress(0, 32, 32));
        TellusSampleRequest request = TellusSampleRequest.fromContext(
            spherical, SamplingPurpose.BIOME, SamplingResolution.exact(SCALE), GEOMETRY, 28L);
        TellusSample sample = new TellusWorldgenEarthSampleBackend(EarthGeneratorSettings.DEFAULT)
            .sample(request);

        assertEquals(SampleStatus.UNKNOWN, sample.status());
        assertTrue(sample.climate().isEmpty());
        assertSame(request, sample.request());
    }

    private static TellusCoordinateContext earth(double latitude, double longitude) {
        SphereWorldModel.GeoPoint geo = new SphereWorldModel.GeoPoint(latitude, longitude, 0.0);
        return TellusCoordinateContextFactory.fromGeo(
            MODEL, geo, new EarthOwnerView(EarthCompatibilityPoint.fromGeoPoint(geo, SCALE)));
    }
}
