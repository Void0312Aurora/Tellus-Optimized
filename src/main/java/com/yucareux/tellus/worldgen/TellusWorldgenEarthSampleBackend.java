package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.worldgen.global.EarthCompatibilityPoint;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusClimateSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusSourceBackend;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only bridge over existing Earth source accessors. It uses memory-only
 * source methods so a provider request never schedules downloads implicitly.
 */
public final class TellusWorldgenEarthSampleBackend implements TellusSourceBackend {
    private static final int UNKNOWN_CLASS = Integer.MIN_VALUE;
    private final EarthGeneratorSettings settings;
    private final WaterSurfaceResolver waterResolver;

    public TellusWorldgenEarthSampleBackend(EarthGeneratorSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.waterResolver = TellusWorldgenSources.previewWaterResolver(settings);
    }

    public EarthGeneratorSettings settings() {
        return this.settings;
    }

    @Override
    public String backendId() {
        return "tellus:worldgen-earth";
    }

    @Override
    public boolean supports(TellusSampleRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.coordinate().owner().kind() != GeneratorOwner.EARTH_PROJECTED) return false;
        if (Double.compare(request.geometry().worldScale(), this.settings.worldScale()) != 0) return false;
        return switch (request.purpose()) {
            case TERRAIN_COLUMN, PRELOAD, BIOME, BIOME_LOCATE, HEIGHTMAP, SURFACE, LOD,
                FEATURE_PROBE, STRUCTURE_PROBE, DEBUG -> true;
        };
    }

    @Override
    public TellusSample sample(TellusSampleRequest request) {
        Objects.requireNonNull(request, "request");
        if (!supports(request)) {
            return TellusSample.unknown(request, "Earth backend does not support request");
        }
        EarthCompatibilityPoint earth = request.coordinate().earth();
        long x = earth.floorBlockX();
        long z = earth.floorBlockZ();
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
            || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            return TellusSample.unknown(request, "Earth coordinate outside source range");
        }
        int blockX = (int)x;
        int blockZ = (int)z;
        double scale = this.settings.worldScale();
        double resolution = request.resolution().metres();

        int rawCover = TellusWorldgenSources.landCover()
            .sampleCoverClassMemoryOnly(blockX, blockZ, scale, resolution);
        int visualCover = rawCover == UNKNOWN_CLASS ? UNKNOWN_CLASS
            : TellusWorldgenSources.landCover()
                .sampleVisualCoverClassMemoryOnly(blockX, blockZ, scale, resolution);
        TellusCoverSample cover = cover(rawCover, visualCover);

        var landMask = TellusWorldgenSources.landMask()
            .sampleLandMaskLocalOnly(blockX, blockZ, scale);
        boolean oceanCandidate = landMask.known() && !landMask.land()
            && (rawCover == 0 || rawCover == 80);
        TellusElevationSource.ResolvedElevationSample elevation =
            TellusWorldgenSources.elevation().sampleResolvedPreviewElevationMetersMemoryOnly(
                request.coordinate().geo(), scale, oceanCandidate, resolution);
        TellusElevationSample elevationSample = Double.isFinite(elevation.elevationMeters())
            ? new TellusElevationSample(SampleStatus.AVAILABLE, elevation.elevationMeters())
            : TellusElevationSample.unknown();

        WaterSurfaceResolver.WaterInfo waterInfo = this.waterResolver.resolveFastWaterInfo(
            blockX, blockZ, rawCover == UNKNOWN_CLASS ? 0 : rawCover);
        TellusWaterSample water = water(elevationSample, waterInfo);
        SampleStatus aggregate = aggregateStatus(elevationSample, cover, water);
        TellusElevationSample finalElevation = elevationSample;
        TellusCoverSample finalCover = cover;
        TellusWaterSample finalWater = water;
        Optional<TellusClimateSample> climate = aggregate == SampleStatus.UNKNOWN
            ? Optional.empty()
            : Optional.ofNullable(TellusWorldgenSources.koppen()
                .sampleDitheredCode(blockX, blockZ, scale))
                .map(TellusClimateSample::available);        if (aggregate == SampleStatus.PARTIAL) {
            finalElevation = partialize(elevationSample);
            finalCover = partialize(cover);
            finalWater = partialize(water);
        }
        TellusSampleProvenance provenance = aggregate == SampleStatus.UNKNOWN
            ? TellusSampleProvenance.unknown("Earth memory-only source snapshot unavailable")
            : TellusSampleProvenance.snapshot(backendId(), "settings:" + this.settings.worldScale(), true,
                "Earth source snapshot; no implicit scheduling");
        return new TellusSample(request, aggregate, finalElevation, finalCover, finalWater, provenance, climate);
    }

    private TellusCoverSample cover(int raw, int visual) {
        if (raw == UNKNOWN_CLASS && visual == UNKNOWN_CLASS) return TellusCoverSample.unknown();
        if (raw == UNKNOWN_CLASS || visual == UNKNOWN_CLASS) return TellusCoverSample.partial();
        return new TellusCoverSample(SampleStatus.AVAILABLE, raw, visual);
    }

    private TellusWaterSample water(TellusElevationSample elevation, WaterSurfaceResolver.WaterInfo info) {
        if (!elevation.known()) return TellusWaterSample.unknown();
        if (!info.isWater()) return TellusWaterSample.dry();
        double surface = (info.surface() - this.settings.effectiveHeightOffset())
            * this.settings.effectiveVerticalWorldScale();
        return Double.isFinite(surface)
            ? new TellusWaterSample(SampleStatus.AVAILABLE, true, info.isOcean(), surface)
            : TellusWaterSample.unknown();
    }

    private static SampleStatus aggregateStatus(
        TellusElevationSample elevation, TellusCoverSample cover, TellusWaterSample water
    ) {
        boolean allAvailable = elevation.complete() && cover.complete() && water.complete();
        if (allAvailable) return SampleStatus.AVAILABLE;
        boolean allUnknown = elevation.status() == SampleStatus.UNKNOWN
            && cover.status() == SampleStatus.UNKNOWN && water.status() == SampleStatus.UNKNOWN;
        return allUnknown ? SampleStatus.UNKNOWN : SampleStatus.PARTIAL;
    }

    private static TellusElevationSample partialize(TellusElevationSample value) {
        return value.status() == SampleStatus.AVAILABLE ? value : TellusElevationSample.partial();
    }

    private static TellusCoverSample partialize(TellusCoverSample value) {
        return value.status() == SampleStatus.AVAILABLE ? value : TellusCoverSample.partial();
    }

    private static TellusWaterSample partialize(TellusWaterSample value) {
        return value.status() == SampleStatus.AVAILABLE ? value : TellusWaterSample.partial();
    }
}
