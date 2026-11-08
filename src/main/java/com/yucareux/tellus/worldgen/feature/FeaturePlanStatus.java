package com.yucareux.tellus.worldgen.feature;

/** Result state for a pure feature planning request. */
public enum FeaturePlanStatus {
    AVAILABLE,
    EMPTY,
    UNKNOWN,
    QUEUED;

    public boolean hasPlacements() { return this == AVAILABLE; }
}
