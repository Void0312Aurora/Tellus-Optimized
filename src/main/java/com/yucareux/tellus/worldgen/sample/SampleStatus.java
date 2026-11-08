package com.yucareux.tellus.worldgen.sample;

/** Availability of one pure Tellus sample or sample layer. */
public enum SampleStatus {
    AVAILABLE,
    PARTIAL,
    UNKNOWN,
    /** Non-blocking snapshot has no value yet; provider reports queued state only. */
    QUEUED;

    public boolean available() {
        return this == AVAILABLE || this == PARTIAL;
    }

    public boolean known() {
        return this == AVAILABLE;
    }

    public boolean partial() {
        return this == PARTIAL;
    }

    public boolean unknown() {
        return this == UNKNOWN;
    }

    public boolean queued() {
        return this == QUEUED;
    }

    public boolean complete() {
        return this == AVAILABLE;
    }
}
