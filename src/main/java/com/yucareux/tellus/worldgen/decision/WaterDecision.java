package com.yucareux.tellus.worldgen.decision;

import java.util.Objects;
import java.util.OptionalInt;

/** Immutable semantic water decision; absent Y is explicit for unknown/dry water. */
public record WaterDecision(Kind kind, OptionalInt surfaceY) {
    public enum Kind { UNKNOWN, NONE, INLAND, OCEAN }

    public WaterDecision {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(surfaceY, "surfaceY");
        if ((kind == Kind.INLAND || kind == Kind.OCEAN) && surfaceY.isEmpty()) {
            throw new IllegalArgumentException("wet water decision requires surfaceY");
        }
        if ((kind == Kind.NONE || kind == Kind.UNKNOWN) && surfaceY.isPresent()) {
            throw new IllegalArgumentException("dry/unknown water decision has no surfaceY");
        }
    }

    public static WaterDecision unknown() { return new WaterDecision(Kind.UNKNOWN, OptionalInt.empty()); }
    public static WaterDecision dry() { return new WaterDecision(Kind.NONE, OptionalInt.empty()); }
}
