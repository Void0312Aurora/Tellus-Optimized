package com.yucareux.tellus.worldgen.decision;

import java.util.Objects;

/** Pure compatibility adapter between legacy-compatible SurfaceDecision and the expanded Earth palette. */
public final class EarthSurfacePaletteAdapter {
    private EarthSurfacePaletteAdapter() {
    }

    /**
     * Expands an already semantic surface decision with explicit Earth-only
     * fields; no climate, classifier or generator lookup is performed.
     */
    public static EarthSurfacePalette fromSurfaceDecision(
        SurfaceDecision decision,
        SemanticMaterial underwaterTop,
        boolean strata,
        EarthSurfacePalette.Flags flags
    ) {
        Objects.requireNonNull(decision, "decision");
        return new EarthSurfacePalette(
            decision.top(), underwaterTop, decision.filler(), decision.depth(),
            decision.form(), strata, flags);
    }

    /** Projects the common top/filler/depth/form view without discarding the palette object itself. */
    public static SurfaceDecision toSurfaceDecision(EarthSurfacePalette palette) {
        Objects.requireNonNull(palette, "palette");
        return new SurfaceDecision(palette.top(), palette.filler(), palette.depth(), palette.form());
    }
}
