package com.yucareux.tellus.worldgen.decision;

/** Availability of a pure column decision. */
public enum DecisionStatus {
    AVAILABLE,
    PARTIAL,
    UNKNOWN,
    QUEUED;

    public boolean complete() { return this == AVAILABLE; }
    public boolean known() { return this == AVAILABLE || this == PARTIAL; }
}
