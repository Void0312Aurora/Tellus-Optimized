package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yucareux.tellus.world.data.biome.BiomeClassification;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.EarthOwnerView;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Explicit Earth-biome opt-in tests; default engine behavior remains unchanged. */
class TellusColumnDecisionEarthOptInTest {
    private static final double SCALE = 1_000.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:earth-opt-in", "earth-opt-v1", "earth-projection-v1", "earth-noise-v1", SCALE);
    private static final SphereWorldModel.GeoPoint GEO = new SphereWorldModel.GeoPoint(47.6062, -122.3321, 0.0);
    private static final EarthCompatibilityPoint EARTH_POINT = EarthCompatibilityPoint.fromGeoPoint(GEO, SCALE);
    private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromGeo(
        MODEL, GEO, new EarthOwnerView(EARTH_POINT));
    private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
        17L, SCALE, 64, -64, 320, GEOMETRY);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void defaultEngineKeepsPlainsEvenWhenClimateTextIsSupplied() {
        ColumnDecision decision = new TellusColumnDecisionEngine().decide(
            GENERATION, COORDINATE, sample(), "BWh");

        assertEquals(BiomeDecision.defaultBiome(), decision.biome());
    }

    @Test
    void explicitEarthConstructorUsesCover60BWhExactClassification() {
        TellusEarthBiomeClassifierAdapter classifier = new TellusEarthBiomeClassifierAdapter(
            (cover, koppen, x, z, scale) -> null,
            BiomeClassification::findBiomeKey,
            BiomeClassification::findFallbackKey);
        TellusColumnDecisionEngine engine = new TellusColumnDecisionEngine(
            new TellusEarthBiomeDecisionKernel(classifier));

        ColumnDecision decision = engine.decide(GENERATION, COORDINATE, sample(), "BWh");

        assertEquals("minecraft:desert", decision.biome().id());
        assertEquals(true, decision.biome().known());
    }

    private static TellusSample sample() {
        TellusSampleRequest request = TellusSampleRequest.fromContext(
            COORDINATE, SamplingPurpose.BIOME, SamplingResolution.exact(SCALE), GEOMETRY, 17L);
        return new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 100.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 60, 60), TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("earth-opt-in-fixture", "r1", true, "available"));
    }
}
