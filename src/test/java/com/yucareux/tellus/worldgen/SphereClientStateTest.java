package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yucareux.tellus.client.SphereClientState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SphereClientStateTest {
   @AfterEach
   void resetState() {
      SphereClientState.reset();
   }

   @Test
   void ignoresStaleFrameRevisions() {
      var current = new SphereClientState.Snapshot(true, "tellus:earth", "rancic-ccam30-double-v1", 3,
         4.0, 2000.0, 5.0, 1.0, 12.0F, 9L);
      var stale = new SphereClientState.Snapshot(true, "tellus:earth", "rancic-ccam30-double-v1", 1,
         6.0, 2000.0, 7.0, 1.0, 18.0F, 8L);

      SphereClientState.update(current);
      SphereClientState.update(stale);

      assertEquals(current, SphereClientState.current());
   }

   @Test
   void clearFrameIsAcceptedAtNewRevision() {
      SphereClientState.update(new SphereClientState.Snapshot(true, "tellus:earth", "rancic-ccam30-double-v1", 0,
         0.0, 2000.0, 0.0, 1.0, 0.0F, 4L));

      SphereClientState.update(SphereClientState.Snapshot.empty(5L));

      assertEquals(false, SphereClientState.current().spherical());
      assertEquals(5L, SphereClientState.current().revision());
   }
}
