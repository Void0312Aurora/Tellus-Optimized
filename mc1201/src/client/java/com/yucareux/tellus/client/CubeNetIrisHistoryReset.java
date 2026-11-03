package com.yucareux.tellus.client;

import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot invalidation for Iris' temporal camera history after a seam rebase. */
public final class CubeNetIrisHistoryReset {
   private static final AtomicBoolean REQUESTED = new AtomicBoolean();

   private CubeNetIrisHistoryReset() { }

   public static void request() {
      REQUESTED.set(true);
   }

   public static boolean consume() {
      return REQUESTED.getAndSet(false);
   }
}
