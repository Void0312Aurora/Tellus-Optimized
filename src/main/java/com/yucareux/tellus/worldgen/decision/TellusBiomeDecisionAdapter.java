package com.yucareux.tellus.worldgen.decision;

/** Pure adapter for already-resolved biome identities; it never classifies or normalizes ids. */
public final class TellusBiomeDecisionAdapter {
    private TellusBiomeDecisionAdapter() {
    }

    /**
     * Converts an external semantic id and its resolved/known flag without
     * changing the id text. Unknown input never receives a fabricated biome.
     */
    public static BiomeDecision fromId(String id, boolean known) {
        if (!known || id == null || id.isBlank() || id.trim().equalsIgnoreCase("unknown")) {
            return BiomeDecision.unknown();
        }
        return new BiomeDecision(id, true);
    }

    /** Explicit name for callers adapting an already-resolved classifier result. */
    public static BiomeDecision fromResolvedId(String id, boolean known) {
        return fromId(id, known);
    }
}
