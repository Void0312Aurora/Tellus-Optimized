package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Locks the shared wrapper to the existing SurfacePatchNoise algorithm. */
class TellusSurfaceVariantRollTest {
    @Test
    void percentAndCoverageRemainExactDelegates() {
        long[] salts = {0L, 23L, Long.MIN_VALUE, 0x5DEECE66DL};
        int[] coordinates = {-1_000_003, -97, 0, 1, 7_919, 2_000_123};
        double[] coverage = {0.0, 10.0, 35.0, 65.0, 100.0};

        for (long salt : salts) {
            for (int x : coordinates) {
                for (int z : coordinates) {
                    assertEquals(SurfacePatchNoise.percent(x, z, salt),
                        TellusSurfaceVariantRoll.percent(x, z, salt));
                    for (double requested : coverage) {
                        assertEquals(SurfacePatchNoise.covers(x, z, salt, requested),
                            TellusSurfaceVariantRoll.covers(x, z, salt, requested));
                    }
                }
            }
        }
    }
}
