package com.yucareux.tellus.worldgen;

/**
 * Pure transition state machine used by the mc1211 server handoff path.
 * Network and tick code can retain the phase without requiring a live server.
 */
public final class CubeNetTransitionState {
   public enum Phase { PREPARE, STAGED, COMMIT, RELEASE }

   private Phase phase = Phase.PREPARE;

   public Phase phase() { return phase; }

   public CubeNetTransitionState ready() { return advance(Phase.STAGED); }
   public CubeNetTransitionState commit() { return advance(Phase.COMMIT); }
   public CubeNetTransitionState release() { return advance(Phase.RELEASE); }
   public CubeNetTransitionState cancel() {
      if (phase == Phase.RELEASE) throw new IllegalStateException("released transition cannot cancel");
      phase = Phase.RELEASE;
      return this;
   }

   public CubeNetTransitionState advance(Phase next) {
      if (next == null) throw new NullPointerException("next");
      boolean valid = switch (phase) {
         case PREPARE -> next == Phase.STAGED || next == Phase.RELEASE;
         case STAGED -> next == Phase.COMMIT || next == Phase.RELEASE;
         case COMMIT -> next == Phase.RELEASE;
         case RELEASE -> false;
      };
      if (!valid) throw new IllegalStateException("invalid Cube-Net transition " + phase + " -> " + next);
      phase = next;
      return this;
   }
}
