package com.yucareux.tellus.worldgen.persistence;

/** Exact native holder admission: disk-only transactions must not race loaded/pending chunk saves. */
public interface TellusNativeChunkMapAccess {
    boolean tellus$hasNativeHolder(long chunkKey);
}
