package com.yucareux.tellus.worldgen.sample;

/** Immutable elevation layer. UNKNOWN/QUEUED use NaN as an explicit absent value. */
public record TellusElevationSample(SampleStatus status, double metres) {
    public TellusElevationSample {
        if (status == null) throw new NullPointerException("status");
        if (status == SampleStatus.AVAILABLE && !Double.isFinite(metres)) {
            throw new IllegalArgumentException("available elevation must be finite");
        }
    }

    public static TellusElevationSample unknown() {
        return new TellusElevationSample(SampleStatus.UNKNOWN, Double.NaN);
    }

    public static TellusElevationSample queued() {
        return new TellusElevationSample(SampleStatus.QUEUED, Double.NaN);
    }

    public static TellusElevationSample partial(double metres) {
        return new TellusElevationSample(SampleStatus.PARTIAL, metres);
    }

    public static TellusElevationSample partial() {
        return new TellusElevationSample(SampleStatus.PARTIAL, Double.NaN);
    }

    public boolean known() {
        return status.known();
    }

    public boolean complete() {
        return status.complete();
    }
}
