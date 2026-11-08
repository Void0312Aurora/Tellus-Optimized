package com.yucareux.tellus.worldgen.decision;

import java.util.Objects;

/** Registry-free semantic biome identity; unknown is explicit. */
public record BiomeDecision(String id, boolean known) {
    public BiomeDecision {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("id");
    }

    public static BiomeDecision unknown() { return new BiomeDecision("unknown", false); }
    public static BiomeDecision defaultBiome() { return new BiomeDecision("minecraft:plains", true); }
}
