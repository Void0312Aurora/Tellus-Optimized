package com.yucareux.tellus.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure rollout gate contracts; no runtime manager or loader hook is constructed. */
class TellusRuntimeRolloutGateTest {
   private static final TellusRuntimeRolloutGate.ParityEvidence COLUMNS_PASS =
      new TellusRuntimeRolloutGate.ParityEvidence(true, true, "columns-run-17");
   private static final TellusRuntimeRolloutGate.ParityEvidence BIOME_PASS =
      new TellusRuntimeRolloutGate.ParityEvidence(true, true, "biome-run-17");

   @Test
   void defaultAndMissingEvidenceRemainDisabled() {
      TellusRuntimeRolloutGate.Result decision = TellusRuntimeRolloutGate.evaluate(
         new TellusRuntimeRolloutGate.Request(false, COLUMNS_PASS, BIOME_PASS, true));
      assertEquals(TellusRuntimeRolloutGate.Status.DISABLED, decision.status());
      assertFalse(decision.enabled());
      assertTrue(decision.diagnostics().contains("explicit rollout flag is false"));
   }

   @Test
   void staleOrFailedParityAndStorageBlockEnablement() {
      TellusRuntimeRolloutGate.Result staleColumns = TellusRuntimeRolloutGate.evaluate(
         new TellusRuntimeRolloutGate.Request(true,
            new TellusRuntimeRolloutGate.ParityEvidence(false, true, "old-columns"), BIOME_PASS, true));
      TellusRuntimeRolloutGate.Result failedBiome = TellusRuntimeRolloutGate.evaluate(
         new TellusRuntimeRolloutGate.Request(true, COLUMNS_PASS,
            new TellusRuntimeRolloutGate.ParityEvidence(true, false, "biome-fail"), true));
      TellusRuntimeRolloutGate.Result storageNotReady = TellusRuntimeRolloutGate.evaluate(
         new TellusRuntimeRolloutGate.Request(true, COLUMNS_PASS, BIOME_PASS, false));

      assertEquals(TellusRuntimeRolloutGate.Status.DISABLED, staleColumns.status());
      assertEquals(TellusRuntimeRolloutGate.Status.DISABLED, failedBiome.status());
      assertEquals(TellusRuntimeRolloutGate.Status.DISABLED, storageNotReady.status());
      assertTrue(staleColumns.diagnostics().get(0).contains("columns"));
      assertTrue(failedBiome.diagnostics().get(0).contains("biome"));
      assertTrue(storageNotReady.diagnostics().get(0).contains("storage"));
   }

   @Test
   void onlyExplicitFlagAndFreshPassingEvidenceEnable() {
      TellusRuntimeRolloutGate.Result decision = TellusRuntimeRolloutGate.evaluate(
         new TellusRuntimeRolloutGate.Request(true, COLUMNS_PASS, BIOME_PASS, true));
      assertEquals(TellusRuntimeRolloutGate.Status.ENABLED, decision.status());
      assertTrue(decision.enabled());
      assertTrue(decision.diagnostics().isEmpty());
   }
}
