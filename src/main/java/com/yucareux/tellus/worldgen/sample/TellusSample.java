package com.yucareux.tellus.worldgen.sample;

import java.util.Objects;
import java.util.Optional;

/** Immutable aggregate returned by a pure TellusSampleProvider. */
public record TellusSample(
    TellusSampleRequest request,
    SampleStatus status,
    TellusElevationSample elevation,
    TellusCoverSample cover,
    TellusWaterSample water,
    TellusSampleProvenance provenance,
    Optional<TellusClimateSample> climate,
    Optional<TellusSphericalSurfaceSnapshot> sphericalSurface
) {
    public TellusSample {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(elevation, "elevation");
        Objects.requireNonNull(cover, "cover");
        Objects.requireNonNull(water, "water");
        Objects.requireNonNull(provenance, "provenance");
        climate = Objects.requireNonNull(climate, "climate");
        sphericalSurface = Objects.requireNonNull(sphericalSurface, "sphericalSurface");
        TellusSampleConsistencyValidator.validate(
            request, status, elevation, cover, water, provenance);
        if (sphericalSurface.isPresent()) {
            var surface = sphericalSurface.orElseThrow().surface();
            var owner = request.coordinate().owner().kind();
            if (owner == com.yucareux.tellus.worldgen.global.GeneratorOwner.EARTH_PROJECTED
                || status != SampleStatus.AVAILABLE || !surface.elevationAvailable()
                || Double.compare(surface.elevationMeters(), elevation.metres()) != 0
                || surface.coverClass() != cover.rawClass() || surface.coverClass() != cover.visualClass()
                || surface.waterAvailable() != water.hasWater()
                || surface.waterAvailable() && (Double.compare(surface.waterSurfaceMeters(), water.surfaceMetres()) != 0
                    || surface.oceanWater() != water.ocean())) {
                throw new IllegalArgumentException("spherical surface snapshot does not match sample fields/owner");
            }
        }
    }

    public TellusSample(TellusSampleRequest request, SampleStatus status, TellusElevationSample elevation,
                        TellusCoverSample cover, TellusWaterSample water, TellusSampleProvenance provenance,
                        Optional<TellusClimateSample> climate) {
        this(request, status, elevation, cover, water, provenance, climate, Optional.empty());
    }

    public TellusSample(
        TellusSampleRequest request,
        SampleStatus status,
        TellusElevationSample elevation,
        TellusCoverSample cover,
        TellusWaterSample water,
        TellusSampleProvenance provenance
    ) {
        this(request, status, elevation, cover, water, provenance, Optional.empty());
    }
    public SampleStatus availability() {
        return status;
    }

    public boolean known() {
        return status.known();
    }

    public boolean complete() {
        return status.complete();
    }

    public boolean partial() {
        return status.partial();
    }

    public boolean queued() {
        return status.queued();
    }

    /** Returns this sample only when a complete value is present. */
    public TellusSample sampleRequired() {
        if (!complete()) {
            throw new IllegalStateException("required Tellus sample is " + status);
        }
        return this;
    }

    public TellusSample requireComplete() {
        return sampleRequired();
    }

    public static TellusSample unknown(TellusSampleRequest request, String note) {
        return new TellusSample(
            request,
            SampleStatus.UNKNOWN,
            TellusElevationSample.unknown(),
            TellusCoverSample.unknown(),
            TellusWaterSample.unknown(),
            TellusSampleProvenance.unknown(note)
        );
    }

    public static TellusSample partial(TellusSampleRequest request, String note) {
        return new TellusSample(
            request,
            SampleStatus.PARTIAL,
            TellusElevationSample.partial(),
            TellusCoverSample.partial(),
            TellusWaterSample.partial(),
            TellusSampleProvenance.snapshot("unknown", "unknown", false, note)
        );
    }

    /** Non-blocking result with no snapshot; no scheduling is performed here. */
    public static TellusSample queued(TellusSampleRequest request, String sourceId, String revision, String note) {
        return new TellusSample(
            request,
            SampleStatus.QUEUED,
            TellusElevationSample.queued(),
            TellusCoverSample.queued(),
            TellusWaterSample.queued(),
            TellusSampleProvenance.queued(sourceId, revision, note)
        );
    }
}
