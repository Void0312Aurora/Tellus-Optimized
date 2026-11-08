package com.yucareux.tellus.worldgen.write;

/** Fallback behavior when a pure storage commit is not eligible. */
public enum StorageFallbackPolicy {
   /** Do not hand the result to any storage path. */
   NO_COMMIT,
   /** Return an explicit deferred result; a caller may choose a later path. */
   DEFER_READ_ONLY;
}
