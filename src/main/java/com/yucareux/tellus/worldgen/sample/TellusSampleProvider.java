package com.yucareux.tellus.worldgen.sample;

import java.util.Objects;

/** Pure, side-effect-free terrain sampling boundary. */
public interface TellusSampleProvider {
    /** Stable provider identity for diagnostics and cache namespaces. */
    String providerId();

    /**
     * Reads only the provider's current snapshot. For NON_BLOCKING requests an
     * implementation returns a snapshot, UNKNOWN/PARTIAL, or QUEUED result;
     * this contract never schedules work implicitly.
     */
    TellusSample sample(TellusSampleRequest request);

    /** Requires a complete AVAILABLE result; UNKNOWN/PARTIAL/QUEUED remain explicit. */
    default TellusSample sampleRequired(TellusSampleRequest request) {
        TellusSample result = Objects.requireNonNull(
            this.sample(Objects.requireNonNull(request, "request")), "sample");
        return result.sampleRequired();
    }
}
