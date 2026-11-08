package com.yucareux.tellus.worldgen.sample;

/** Immutable water layer; an absent surface uses NaN and is not an error. */
public record TellusWaterSample(
    SampleStatus status,
    boolean hasWater,
    boolean ocean,
    double surfaceMetres
) {
    public TellusWaterSample {
        if (status == null) throw new NullPointerException("status");
        if (hasWater && status.available() && !Double.isFinite(surfaceMetres)) {
            throw new IllegalArgumentException("available water surface must be finite");
        }
        if (ocean && !hasWater && status == SampleStatus.AVAILABLE) {
            throw new IllegalArgumentException("ocean requires water");
        }
    }

    public static TellusWaterSample dry() {
        return new TellusWaterSample(SampleStatus.AVAILABLE, false, false, Double.NaN);
    }

    public static TellusWaterSample unknown() {
        return new TellusWaterSample(SampleStatus.UNKNOWN, false, false, Double.NaN);
    }

    public static TellusWaterSample queued() {
        return new TellusWaterSample(SampleStatus.QUEUED, false, false, Double.NaN);
    }

    public static TellusWaterSample partial() {
        return new TellusWaterSample(SampleStatus.PARTIAL, false, false, Double.NaN);
    }

    public static TellusWaterSample partial(boolean hasWater, boolean ocean, double surfaceMetres) {
        return new TellusWaterSample(SampleStatus.PARTIAL, hasWater, ocean, surfaceMetres);
    }

    public boolean known() {
        return status.known();
    }

    public boolean complete() {
        return status.complete();
    }
}
