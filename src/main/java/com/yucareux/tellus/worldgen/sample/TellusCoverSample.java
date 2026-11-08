package com.yucareux.tellus.worldgen.sample;

/** Immutable land-cover layer; UNKNOWN/QUEUED use integer sentinels. */
public record TellusCoverSample(SampleStatus status, int rawClass, int visualClass) {
    public TellusCoverSample {
        if (status == null) throw new NullPointerException("status");
    }

    public static TellusCoverSample unknown() {
        return new TellusCoverSample(SampleStatus.UNKNOWN, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static TellusCoverSample queued() {
        return new TellusCoverSample(SampleStatus.QUEUED, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static TellusCoverSample partial() {
        return new TellusCoverSample(SampleStatus.PARTIAL, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static TellusCoverSample partial(int rawClass, int visualClass) {
        return new TellusCoverSample(SampleStatus.PARTIAL, rawClass, visualClass);
    }

    public boolean known() {
        return status.known();
    }

    public boolean complete() {
        return status.complete();
    }
}
