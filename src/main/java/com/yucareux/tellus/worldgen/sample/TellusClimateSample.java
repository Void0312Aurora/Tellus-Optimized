package com.yucareux.tellus.worldgen.sample;

import java.util.Objects;

/** Optional climate snapshot carried alongside the core terrain layers. */
public record TellusClimateSample(SampleStatus status, String koppenCode) {
    public TellusClimateSample {
        Objects.requireNonNull(status, "status");
        koppenCode = normalizeCode(koppenCode);
        if (status == SampleStatus.AVAILABLE && koppenCode == null) {
            throw new IllegalArgumentException("AVAILABLE climate requires a Koppen code");
        }
    }

    public static TellusClimateSample available(String koppenCode) {
        return new TellusClimateSample(SampleStatus.AVAILABLE, koppenCode);
    }

    public static TellusClimateSample partial(String koppenCode) {
        return new TellusClimateSample(SampleStatus.PARTIAL, koppenCode);
    }

    public static TellusClimateSample unknown() {
        return new TellusClimateSample(SampleStatus.UNKNOWN, null);
    }

    public static TellusClimateSample queued() {
        return new TellusClimateSample(SampleStatus.QUEUED, null);
    }

    public String code() {
        return this.koppenCode;
    }

    public boolean known() {
        return this.status.known();
    }

    public boolean complete() {
        return this.status.complete();
    }

    private static String normalizeCode(String code) {
        return code == null || code.isBlank() ? null : code.trim();
    }
}
