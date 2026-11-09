package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Identity and owner-thread contracts for participating writers; no world/chunk or default-engine lock claim. */
class TellusTargetWriteGuardTest {
   private record EqualKey(int value) { }

   @Test void equalButDifferentTargetObjectsHaveIndependentIdentityLeases() {
      Object first = new EqualKey(7), second = new EqualKey(7);
      assertEquals(first, second); assertNotSame(first, second);
      try (var a = TellusTargetWriteGuard.tryAcquire(first).orElseThrow();
           var b = TellusTargetWriteGuard.tryAcquire(second).orElseThrow()) {
         a.requireOwned(first); b.requireOwned(second);
         assertThrows(IllegalStateException.class, () -> a.requireOwned(second));
         assertThrows(IllegalStateException.class, () -> b.requireOwned(first));
         assertTrue(TellusTargetWriteGuard.tryAcquire(first).isEmpty());
         assertTrue(TellusTargetWriteGuard.tryAcquire(second).isEmpty());
      }
      try (var again = TellusTargetWriteGuard.tryAcquire(first).orElseThrow()) { again.requireOwned(first); }
   }

   @Test void sameThreadReentryIsQueuedWhileAnotherActualTargetRemainsAvailable() {
      Object target = new Object(), independent = new Object();
      try (var lease = TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) {
         assertTrue(TellusTargetWriteGuard.tryAcquire(target).isEmpty(), "same owner thread is not reentrant");
         try (var other = TellusTargetWriteGuard.tryAcquire(independent).orElseThrow()) {
            other.requireOwned(independent); lease.requireOwned(target);
            assertTrue(TellusTargetWriteGuard.tryAcquire(target).isEmpty());
         }
         lease.requireOwned(target);
      }
   }

   @Test void concurrentContentionReturnsImmediatelyAndReleaseAllowsAFreshOwner() throws Exception {
      Object target = new Object();
      CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      var future = executor.submit(() -> {
         try (var lease = TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) {
            lease.requireOwned(target); acquired.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release handshake timed out");
            lease.requireOwned(target);
         }
         return null;
      });
      try {
         assertTrue(acquired.await(10, TimeUnit.SECONDS), "owner must hold the target before contention");
         assertTrue(TellusTargetWriteGuard.tryAcquire(target).isEmpty(), "no waiting or target callback is needed");
         Object otherTarget = new Object();
         try (var independent = TellusTargetWriteGuard.tryAcquire(otherTarget).orElseThrow()) {
            independent.requireOwned(otherTarget);
            assertTrue(TellusTargetWriteGuard.tryAcquire(target).isEmpty());
         }
         release.countDown(); future.get(10, TimeUnit.SECONDS);
         try (var fresh = TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) { fresh.requireOwned(target); }
      } finally {
         release.countDown(); executor.shutdownNow();
         assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      }
   }

   @Test void foreignThreadCannotUseOrCloseAnActiveLeaseOrReleaseItsTarget() throws Exception {
      Object target = new Object();
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try (var lease = TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) {
         lease.requireOwned(target);
         assertThrows(IllegalStateException.class, () -> lease.requireOwned(new Object()));
         assertThrows(IllegalStateException.class, () -> lease.requireOwned(null));
         executor.submit(() -> {
            assertThrows(IllegalStateException.class, () -> lease.requireOwned(target));
            assertThrows(IllegalStateException.class, lease::close);
            assertTrue(TellusTargetWriteGuard.tryAcquire(target).isEmpty());
         }).get(10, TimeUnit.SECONDS);
         lease.requireOwned(target);
         assertTrue(TellusTargetWriteGuard.tryAcquire(target).isEmpty());
      } finally {
         executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      }
   }

   @Test void staleTokenAndIdempotentCloseDoNotReleaseTheNextLease() throws Exception {
      Object target = new Object();
      var stale = TellusTargetWriteGuard.tryAcquire(target).orElseThrow();
      stale.close(); stale.close();
      assertThrows(IllegalStateException.class, () -> stale.requireOwned(target));
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try (var current = TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) {
         assertNotSame(stale, current); current.requireOwned(target);
         stale.close();
         assertTrue(TellusTargetWriteGuard.tryAcquire(target).isEmpty());
         executor.submit(() -> assertThrows(IllegalStateException.class, stale::close)).get(10, TimeUnit.SECONDS);
         assertTrue(TellusTargetWriteGuard.tryAcquire(target).isEmpty());
         current.requireOwned(target);
      } finally {
         executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      }
      try (var fresh = TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) { fresh.requireOwned(target); }
   }

   @Test void tryWithResourcesReleasesOnRuntimeFailuresAndErrors() {
      Object target = new Object();
      RuntimeException failure = new IllegalStateException("writer callback failure");
      assertSame(failure, assertThrows(RuntimeException.class, () -> {
         try (var lease = TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) {
            lease.requireOwned(target); throw failure;
         }
      }));
      AssertionError error = new AssertionError("writer callback error");
      assertSame(error, assertThrows(AssertionError.class, () -> {
         try (var lease = TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) {
            lease.requireOwned(target); throw error;
         }
      }));
      try (var lease = TellusTargetWriteGuard.tryAcquire(target).orElseThrow()) { lease.requireOwned(target); }
   }

   @Test void nullTargetsAreInvalidAndTargetEqualityCallbacksAreNeverInvoked() {
      assertThrows(NullPointerException.class, () -> TellusTargetWriteGuard.tryAcquire(null));
      Object hostileEquality = new Object() {
         @Override public boolean equals(Object other) { throw new AssertionError("target equality callback must not execute"); }
         @Override public int hashCode() { throw new AssertionError("target hash callback must not execute"); }
      };
      try (var lease = TellusTargetWriteGuard.tryAcquire(hostileEquality).orElseThrow()) {
         lease.requireOwned(hostileEquality);
         assertTrue(TellusTargetWriteGuard.tryAcquire(hostileEquality).isEmpty());
      }
      try (var again = TellusTargetWriteGuard.tryAcquire(hostileEquality).orElseThrow()) { again.requireOwned(hostileEquality); }
      for (var constructor : TellusTargetWriteGuard.Lease.class.getDeclaredConstructors()) {
         assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()), "leases originate only from tryAcquire");
      }
   }
}
