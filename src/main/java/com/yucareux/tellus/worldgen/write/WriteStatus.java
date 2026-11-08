package com.yucareux.tellus.worldgen.write;

/** Outcome of a pure semantic write planning operation. */
public enum WriteStatus {
   WRITTEN,
   PARTIAL,
   NO_WRITE_UNKNOWN,
   NO_WRITE_QUEUED,
   NO_WRITE_PARTIAL,
   NO_WRITE_INVALID;

   public boolean wrote() {
      return this == WRITTEN || this == PARTIAL;
   }

   public boolean noWrite() {
      return !this.wrote();
   }
}
