package com.yucareux.tellus.worldgen.decision;

import java.util.Objects;

/** Immutable semantic surface layering for one column. */
public record SurfaceDecision(
    SemanticMaterial top,
    SemanticMaterial filler,
    int depth,
    SurfaceForm form
) {
    public SurfaceDecision {
        Objects.requireNonNull(top, "top");
        Objects.requireNonNull(filler, "filler");
        Objects.requireNonNull(form, "form");
        if (depth < 0) throw new IllegalArgumentException("depth");
    }

    /** Compatibility constructor for callers that do not carry a landform. */
    public SurfaceDecision(SemanticMaterial top, SemanticMaterial filler, int depth) {
        this(top, filler, depth, SurfaceForm.NONE);
    }

    public static SurfaceDecision unknown() {
        return new SurfaceDecision(SemanticMaterial.UNKNOWN, SemanticMaterial.UNKNOWN, 0, SurfaceForm.NONE);
    }

    public static SurfaceDecision defaultStone() {
        return new SurfaceDecision(SemanticMaterial.STONE, SemanticMaterial.STONE, 3, SurfaceForm.NONE);
    }
}
