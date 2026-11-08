package com.yucareux.tellus.worldgen.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Contract coverage for the trailing optional climate snapshot. */
class TellusClimateSampleContractTest {
    private static final double SCALE = 1.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:climate", "climate-v1", "spherical", "noise-v1", SCALE);

    @Test
    void preservesCanonicalKoppenCodesWithoutClassifierOrCallsiteChanges() {
        assertEquals("BWh", TellusClimateSample.available("BWh").koppenCode());
        assertEquals("BWk", TellusClimateSample.available("BWk").code());
        assertEquals("BSh", TellusClimateSample.available("BSh").koppenCode());
        assertEquals("Csa", TellusClimateSample.available("Csa").code());
        assertEquals(SampleStatus.AVAILABLE, TellusClimateSample.available("BWh").status());
        assertThrows(IllegalArgumentException.class, () -> TellusClimateSample.available(" "));
    }

    @Test
    void sixArgumentTellusSampleRemainsBackwardCompatibleWithAbsentClimate() {
        TellusSampleRequest request = request();
        TellusSample sample = new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 64.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30),
            TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("fixture", "r1", false, "legacy"));

        assertTrue(sample.climate().isEmpty());
        assertEquals(SampleStatus.AVAILABLE, sample.status());
    }

    @Test
    void sevenArgumentSampleCarriesOptionalClimateWithoutChangingCoreStatus() {
        TellusSampleRequest request = request();
        TellusClimateSample climate = TellusClimateSample.available("Csa");
        TellusSample sample = new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 64.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30),
            TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("fixture", "r1", false, "climate"),
            Optional.of(climate));

        assertEquals(Optional.of(climate), sample.climate());
        assertEquals("Csa", sample.climate().orElseThrow().koppenCode());
        assertTrue(sample.complete());
    }

    @Test
    void climateSentinelsRemainExplicitForUnknownAndQueued() {
        TellusClimateSample unknown = TellusClimateSample.unknown();
        TellusClimateSample queued = TellusClimateSample.queued();

        assertEquals(SampleStatus.UNKNOWN, unknown.status());
        assertEquals(SampleStatus.QUEUED, queued.status());
        assertFalse(unknown.known());
        assertFalse(queued.complete());
        assertTrue(unknown.koppenCode() == null);
        assertTrue(queued.koppenCode() == null);
    }

    private static TellusSampleRequest request() {
        TellusCoordinateContext coordinate = TellusCoordinateContextFactory.fromSphericalBlock(
            MODEL, new SphereWorldModel.SphereBlockAddress(0, 32, 32));
        return TellusSampleRequest.fromContext(
            coordinate, SamplingPurpose.BIOME,
            SamplingResolution.exact(SCALE), GEOMETRY, 17L);
    }
}
