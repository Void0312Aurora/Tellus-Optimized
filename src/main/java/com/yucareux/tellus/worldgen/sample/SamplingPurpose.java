package com.yucareux.tellus.worldgen.sample;

/** Consumer intent used to keep sampling and cache keys explicit. */
public enum SamplingPurpose {
    TERRAIN_COLUMN,
    PRELOAD,
    BIOME,
    BIOME_LOCATE,
    HEIGHTMAP,
    SURFACE,
    LOD,
    FEATURE_PROBE,
    STRUCTURE_PROBE,
    DEBUG;
}
