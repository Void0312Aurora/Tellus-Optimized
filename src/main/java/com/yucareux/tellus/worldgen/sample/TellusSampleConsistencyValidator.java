package com.yucareux.tellus.worldgen.sample;

import java.util.Objects;

/** Validates aggregate/layer status and non-blocking provenance consistency. */
public final class TellusSampleConsistencyValidator {
    private TellusSampleConsistencyValidator() {
    }

    public static void validate(TellusSample sample) {
        Objects.requireNonNull(sample, "sample");
        validate(sample.request(), sample.status(), sample.elevation(), sample.cover(),
            sample.water(), sample.provenance());
    }

    public static void validate(
        TellusSampleRequest request,
        SampleStatus aggregate,
        TellusElevationSample elevation,
        TellusCoverSample cover,
        TellusWaterSample water,
        TellusSampleProvenance provenance
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(elevation, "elevation");
        Objects.requireNonNull(cover, "cover");
        Objects.requireNonNull(water, "water");
        Objects.requireNonNull(provenance, "provenance");

        SampleStatus[] layers = {elevation.status(), cover.status(), water.status()};
        switch (aggregate) {
            case AVAILABLE -> requireAll(layers, SampleStatus.AVAILABLE,
                "AVAILABLE aggregate requires AVAILABLE layers");
            case PARTIAL -> {
                if (contains(layers, SampleStatus.QUEUED)
                    || !contains(layers, SampleStatus.PARTIAL)) {
                    throw new IllegalArgumentException(
                        "PARTIAL aggregate requires a PARTIAL layer and no QUEUED layer");
                }
            }
            case UNKNOWN -> {
                requireAll(layers, SampleStatus.UNKNOWN,
                    "UNKNOWN aggregate requires UNKNOWN layers");
                if (provenance.queued()) throw new IllegalArgumentException(
                    "UNKNOWN aggregate cannot carry queued provenance");
            }
            case QUEUED -> {
                requireAll(layers, SampleStatus.QUEUED,
                    "QUEUED aggregate requires QUEUED layers");
                if (!request.nonBlocking() || !provenance.queued() || provenance.snapshot()) {
                    throw new IllegalArgumentException(
                        "QUEUED aggregate requires non-blocking request and queued non-snapshot provenance");
                }
            }
        }

        if (aggregate != SampleStatus.QUEUED && provenance.queued()) {
            throw new IllegalArgumentException("only QUEUED aggregate may carry queued provenance");
        }
        if (request.nonBlocking()
            && (aggregate == SampleStatus.AVAILABLE || aggregate == SampleStatus.PARTIAL)
            && !provenance.snapshot()) {
            throw new IllegalArgumentException(
                "non-blocking value result must identify a read-only snapshot");
        }
    }

    private static boolean contains(SampleStatus[] values, SampleStatus expected) {
        for (SampleStatus value : values) if (value == expected) return true;
        return false;
    }

    private static void requireAll(SampleStatus[] values, SampleStatus expected, String message) {
        for (SampleStatus value : values) {
            if (value != expected) throw new IllegalArgumentException(message);
        }
    }
}
