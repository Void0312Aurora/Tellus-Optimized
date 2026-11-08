package com.yucareux.tellus.worldgen.decision;

import java.util.Objects;

/** Pure compatibility adapter between legacy-compatible SurfaceDecision and the expanded Earth palette. */
public final class EarthSurfacePaletteAdapter {
    private EarthSurfacePaletteAdapter() {
    }

    /**
     * Expands an already semantic surface decision with explicit Earth-only
     * fields, adding the top cell to the semantic filler count. No climate,
     * classifier or generator lookup is performed.
     */
    public static EarthSurfacePalette fromSurfaceDecision(
        SurfaceDecision decision,
        SemanticMaterial underwaterTop,
        boolean strata,
        EarthSurfacePalette.Flags flags
    ) {
        Objects.requireNonNull(decision, "decision");
        return new EarthSurfacePalette(
            decision.top(), underwaterTop, decision.filler(), Math.addExact(decision.depth(), 1),
            decision.form(), strata, flags);
    }

    /** Projects top/filler/form and removes the top cell from Earth's total depth. */
    public static SurfaceDecision toSurfaceDecision(EarthSurfacePalette palette) {
        Objects.requireNonNull(palette, "palette");
        return new SurfaceDecision(palette.top(), palette.filler(), palette.depth() - 1, palette.form());
    }
}
