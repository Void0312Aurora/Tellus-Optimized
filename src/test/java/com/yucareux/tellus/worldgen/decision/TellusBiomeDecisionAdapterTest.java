package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** Focused pure adapter tests for already-resolved Earth biome identities. */
class TellusBiomeDecisionAdapterTest {
    @Test
    void preservesKnownMinecraftBiomeIdExactly() {
        BiomeDecision decision = TellusBiomeDecisionAdapter.fromId("minecraft:desert", true);

        assertEquals("minecraft:desert", decision.id());
        assertTrue(decision.known());
        assertEquals(decision,
            TellusBiomeDecisionAdapter.fromResolvedId("minecraft:desert", true));
    }

    @Test
    void knownFlagFalseNeverInventsBiome() {
        BiomeDecision decision = TellusBiomeDecisionAdapter.fromId("minecraft:desert", false);

        assertEquals(BiomeDecision.unknown(), decision);
        assertFalse(decision.known());
    }

    @Test
    void nullBlankAndUnknownIdsRemainUnknown() {
        for (String id : new String[]{null, "", " ", "unknown", " UNKNOWN ", "Unknown"}) {
            assertEquals(BiomeDecision.unknown(), TellusBiomeDecisionAdapter.fromId(id, true), id);
        }
    }

    @Test
    void adapterIsMinecraftFreeAndDoesNotNormalizeResolvedText() {
        BiomeDecision decision = TellusBiomeDecisionAdapter.fromId("minecraft:Desert", true);
        assertEquals("minecraft:Desert", decision.id());
        assertFalse(TellusBiomeDecisionAdapter.class.getName().startsWith("net.minecraft."));
        for (Field field : TellusBiomeDecisionAdapter.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("net.minecraft."));
        }
    }
}
