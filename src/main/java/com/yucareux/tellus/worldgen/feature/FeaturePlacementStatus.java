package com.yucareux.tellus.worldgen.feature;

/** Pure outcome for an opt-in feature placement sink. */
public enum FeaturePlacementStatus {
   WRITTEN,
   NO_WRITE_EMPTY,
   NO_WRITE_UNKNOWN,
   NO_WRITE_QUEUED,
   NO_WRITE_OWNER,
   NO_WRITE_CONFLICT,
   INVALID;

   public boolean wrote() { return this == WRITTEN; }
   public boolean noWrite() { return !this.wrote(); }
}
