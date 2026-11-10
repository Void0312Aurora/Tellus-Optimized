package com.yucareux.tellus.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TellusNativeBootstrapModeTest {
    @Test void loaderDefaultIsExplicitlyReversibleAndExistingGeneratorModeIsImmutable() throws Exception {
        String original = System.getProperty(TellusNativeProductionPipeline.MODE_PROPERTY);
        var field = TellusNativeProductionPipeline.class.getDeclaredField("nativeBootstrapDefault");
        field.setAccessible(true);
        boolean previous = field.getBoolean(null);
        try {
            System.clearProperty(TellusNativeProductionPipeline.MODE_PROPERTY);
            TellusNativeProductionPipeline.nativeBootstrapDefault(false);
            var standalone = new TellusNativeProductionPipeline("standalone");
            assertFalse(standalone.enabled());
            TellusNativeProductionPipeline.nativeBootstrapDefault(true);
            var nativeDefault = new TellusNativeProductionPipeline("native");
            assertTrue(nativeDefault.enabled());
            assertFalse(standalone.enabled());
            System.setProperty(TellusNativeProductionPipeline.MODE_PROPERTY, "legacy");
            var rollback = new TellusNativeProductionPipeline("rollback");
            assertFalse(rollback.enabled());
            assertTrue(nativeDefault.enabled());
            assertEquals("unchanged", rollback.run(null, null, null, lease -> {
                assertNull(lease);
                return "unchanged";
            }));
            System.setProperty(TellusNativeProductionPipeline.MODE_PROPERTY, "invalid");
            assertThrows(IllegalArgumentException.class, () -> new TellusNativeProductionPipeline("invalid"));
        } finally {
            TellusNativeProductionPipeline.nativeBootstrapDefault(previous);
            if (original == null) System.clearProperty(TellusNativeProductionPipeline.MODE_PROPERTY);
            else System.setProperty(TellusNativeProductionPipeline.MODE_PROPERTY, original);
        }
    }
}
