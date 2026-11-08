package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure bridge tests for Earth cover/climate/compatibility-coordinate forwarding. */
class TellusEarthBiomeDecisionKernelTest {
    private static final double SCALE = 1_000.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:earth-biome", "earth-biome-v1", "earth-projection-v1", "earth-noise-v1", SCALE);
    private static final SphereWorldModel.GeoPoint GEO = new SphereWorldModel.GeoPoint(47.6062, -122.3321, 0.0);
    private static final EarthCompatibilityPoint EARTH_POINT = EarthCompatibilityPoint.fromGeoPoint(GEO, SCALE);
    private static final TellusCoordinateContext COORDINATE = TellusCoordinateContextFactory.fromGeo(
        MODEL, GEO, new EarthOwnerView(EARTH_POINT));

    @Test
    void forwardsVisualCoverRawKoppenAndFlooredEarthCoordinatesInExactOrder() {
        List<String> climateOrder = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        TellusEarthBiomeDecisionKernel kernel = new TellusEarthBiomeDecisionKernel(
            (visualCover, koppen, floorX, floorZ, scale) -> {
                arguments.add(visualCover);
                arguments.add(floorX);
                arguments.add(floorZ);
                arguments.add(scale);
                climateOrder.add(koppen);
                return switch (koppen) {
                    case "BWh" -> "minecraft:desert";
                    case "BWk" -> "minecraft:badlands";
                    case "BSh" -> "minecraft:savanna";
                    case "Csa" -> "minecraft:windswept_savanna";
                    default -> null;
                };
            });
        TellusSample sample = sample(60, 60, SampleStatus.AVAILABLE);
        String[] codes = {"BWh", "BWk", "BSh", "Csa"};
        String[] ids = {"minecraft:desert", "minecraft:badlands", "minecraft:savanna", "minecraft:windswept_savanna"};

        for (int i = 0; i < codes.length; i++) {
            BiomeDecision result = kernel.decide(sample, COORDINATE, codes[i]);
            assertEquals(ids[i], result.id());
            assertTrue(result.known());
        }

        assertEquals(List.of("BWh", "BWk", "BSh", "Csa"), climateOrder);
        assertEquals(60, arguments.get(0));
        assertEquals(EARTH_POINT.floorBlockX(), arguments.get(1));
        assertEquals(EARTH_POINT.floorBlockZ(), arguments.get(2));
        assertEquals(SCALE, arguments.get(3));
    }

    @Test
    void missingClimateOrIncompleteSampleRemainsUnknownWithoutResolverCall() {
        int[] calls = {0};
        TellusEarthBiomeDecisionKernel kernel = new TellusEarthBiomeDecisionKernel(
            (visualCover, koppen, floorX, floorZ, scale) -> {
                calls[0]++;
                return "minecraft:desert";
            });
        TellusSample complete = sample(60, 60, SampleStatus.AVAILABLE);

        assertEquals(BiomeDecision.unknown(), kernel.decide(complete, COORDINATE, null));
        assertEquals(BiomeDecision.unknown(), kernel.decide(complete, COORDINATE, " "));
        assertEquals(BiomeDecision.unknown(), kernel.decide(
            sample(Integer.MIN_VALUE, Integer.MIN_VALUE, SampleStatus.UNKNOWN), COORDINATE, "BWh"));
        assertEquals(BiomeDecision.unknown(), kernel.decide(
            sample(60, 60, SampleStatus.PARTIAL), COORDINATE, "BWh"));
        assertEquals(0, calls[0]);
    }

    @Test
    void resolverUnknownIdAndNonEarthOwnerRemainUnknown() {
        TellusEarthBiomeDecisionKernel unknownResolver = new TellusEarthBiomeDecisionKernel(
            (visualCover, koppen, floorX, floorZ, scale) -> null);
        assertEquals(BiomeDecision.unknown(), unknownResolver.decide(
            sample(60, 60, SampleStatus.AVAILABLE), COORDINATE, "BWh"));

        TellusCoordinateContext spherical = TellusCoordinateContextFactory.fromSphericalBlock(
            MODEL, new SphereWorldModel.SphereBlockAddress(0, 32L, 32L));
        TellusEarthBiomeDecisionKernel knownResolver = new TellusEarthBiomeDecisionKernel(
            (visualCover, koppen, floorX, floorZ, scale) -> "minecraft:desert");
        assertEquals(BiomeDecision.unknown(), knownResolver.decide(
            sample(60, 60, SampleStatus.AVAILABLE, spherical), spherical, "BWh"));
    }

    private static TellusSample sample(int rawCover, int visualCover, SampleStatus status) {
        return sample(rawCover, visualCover, status, COORDINATE);
    }

    private static TellusSample sample(
        int rawCover, int visualCover, SampleStatus status, TellusCoordinateContext coordinate
    ) {
        TellusSampleRequest request = TellusSampleRequest.fromContext(
            coordinate, SamplingPurpose.BIOME, SamplingResolution.exact(SCALE), GEOMETRY, 17L);
        if (status == SampleStatus.UNKNOWN) return TellusSample.unknown(request, "unknown");
        if (status == SampleStatus.QUEUED) return TellusSample.queued(request, "fixture", "r1", "queued");
        return new TellusSample(request, status,
            status == SampleStatus.PARTIAL
                ? TellusElevationSample.partial(100.0)
                : new TellusElevationSample(SampleStatus.AVAILABLE, 100.0),
            status == SampleStatus.PARTIAL
                ? TellusCoverSample.partial(rawCover, visualCover)
                : new TellusCoverSample(SampleStatus.AVAILABLE, rawCover, visualCover),
            status == SampleStatus.PARTIAL ? TellusWaterSample.partial() : TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("earth-biome-fixture", "r1", true, status.name()));
    }
}
