package com.yucareux.tellus.worldgen.feature;

/** Conflict behavior for downstream writers, independent of block APIs. */
public enum PlacementConflictPolicy {
    OWNER_ONLY,
    PRESERVE_EXISTING,
    REPLACE_TERRAIN,
    MIRROR_OWNER
}
