package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubeNetTransitionStateTest {
   @Test
   void transitionPhasesFollowPrepareStageCommitRelease() {
      CubeNetTransitionState state = new CubeNetTransitionState();
      assertEquals(CubeNetTransitionState.Phase.PREPARE, state.phase());
      assertEquals(CubeNetTransitionState.Phase.STAGED, state.ready().phase());
      assertEquals(CubeNetTransitionState.Phase.COMMIT, state.commit().phase());
      assertEquals(CubeNetTransitionState.Phase.RELEASE, state.release().phase());
   }

   @Test
   void cancellationReleasesOnlyBeforeFinalRelease() {
      CubeNetTransitionState state = new CubeNetTransitionState();
      assertEquals(CubeNetTransitionState.Phase.RELEASE, state.cancel().phase());
      assertThrows(IllegalStateException.class, state::release);

      CubeNetTransitionState committed = new CubeNetTransitionState().ready().commit();
      assertEquals(CubeNetTransitionState.Phase.RELEASE, committed.cancel().phase());
   }

   @Test
   void invalidPhaseJumpsAreRejected() {
      CubeNetTransitionState state = new CubeNetTransitionState();
      assertThrows(IllegalStateException.class,
         () -> state.advance(CubeNetTransitionState.Phase.COMMIT));
      state.ready();
      assertThrows(IllegalStateException.class,
         () -> state.advance(CubeNetTransitionState.Phase.PREPARE));
   }
}
