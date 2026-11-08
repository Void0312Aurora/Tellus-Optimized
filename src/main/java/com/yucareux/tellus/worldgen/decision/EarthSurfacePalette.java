package com.yucareux.tellus.worldgen.decision;

import java.util.Objects;

/**
 * Minecraft-free Earth surface palette contract. It retains both land and
 * underwater top materials plus production-rule flags without evaluating any
 * Earth source, climate, biome or generator state.
 */
public record EarthSurfacePalette(
    SemanticMaterial top,
    SemanticMaterial underwaterTop,
    SemanticMaterial filler,
    int depth,
    SurfaceForm form,
    boolean strata,
    Flags flags
) {
    public EarthSurfacePalette {
        top = requireKnown(top, "top");
        underwaterTop = requireKnown(underwaterTop, "underwaterTop");
        filler = requireKnown(filler, "filler");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(flags, "flags");
        if (depth < 1) throw new IllegalArgumentException("depth");
    }

    public record Flags(
        boolean water,
        boolean shore,
        boolean badlands,
        boolean builtUp,
        boolean randomBiomes
    ) {
        public static Flags none() {
            return new Flags(false, false, false, false, false);
        }
    }

    private static SemanticMaterial requireKnown(SemanticMaterial material, String name) {
        Objects.requireNonNull(material, name);
        if (material == SemanticMaterial.UNKNOWN) {
            throw new IllegalArgumentException(name + " cannot be UNKNOWN");
        }
        return material;
    }
}
