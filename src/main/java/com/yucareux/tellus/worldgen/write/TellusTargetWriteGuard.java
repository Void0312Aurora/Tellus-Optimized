package com.yucareux.tellus.worldgen.write;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;

/** Nonblocking, non-reentrant ownership for participating writers of the same actual target object. */
public final class TellusTargetWriteGuard {
   private static final IdentityHashMap<Object, Lease> ACTIVE = new IdentityHashMap<>();
   private TellusTargetWriteGuard() { }

   public static Optional<Lease> tryAcquire(Object target) {
      Objects.requireNonNull(target, "target");
      synchronized (ACTIVE) {
         if (ACTIVE.containsKey(target)) return Optional.empty();
         Lease lease = new Lease(target, Thread.currentThread());
         ACTIVE.put(target, lease);
         return Optional.of(lease);
      }
   }

   /** Unforgeable owner-thread token. Ownership covers only callers participating in this guard. */
   public static final class Lease implements AutoCloseable {
      private final Object target;
      private final Thread ownerThread;
      private boolean closed;
      private Lease(Object target, Thread ownerThread) {
         this.target = target;
         this.ownerThread = ownerThread;
      }

      public void requireOwned(Object candidate) {
         synchronized (ACTIVE) {
            if (candidate != this.target || Thread.currentThread() != this.ownerThread
               || this.closed || ACTIVE.get(this.target) != this) {
               throw new IllegalStateException("target write lease is not active for this target and thread");
            }
         }
      }

      @Override public void close() {
         synchronized (ACTIVE) {
            if (Thread.currentThread() != this.ownerThread) {
               throw new IllegalStateException("target write lease must close on its owner thread");
            }
            if (this.closed) return;
            if (ACTIVE.get(this.target) != this) throw new IllegalStateException("target write lease is no longer active");
            ACTIVE.remove(this.target);
            this.closed = true;
         }
      }
   }
}
