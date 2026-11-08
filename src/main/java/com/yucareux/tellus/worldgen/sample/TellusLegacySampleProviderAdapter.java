package com.yucareux.tellus.worldgen.sample;

import java.util.Objects;

/**
 * Legacy source/provider bridge driven by an injected precedence selector.
 * This adapter intentionally does not know TellusWorldgenSources, generators,
 * executors, or network/cache APIs.
 */
public final class TellusLegacySampleProviderAdapter implements TellusSampleProvider {
    private final String providerId;
    private final TellusSourceBackendSelector selector;

    public TellusLegacySampleProviderAdapter(TellusSourceBackendSelector selector) {
        this("tellus:legacy", selector);
    }

    public TellusLegacySampleProviderAdapter(
        String providerId,
        TellusSourceBackendSelector selector
    ) {
        this.providerId = requireText(providerId, "providerId");
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    @Override
    public String providerId() {
        return this.providerId;
    }

    @Override
    public TellusSample sample(TellusSampleRequest request) {
        Objects.requireNonNull(request, "request");
        TellusSourceBackend backend = this.selector.select(request);
        if (backend == null) {
            return TellusSample.unknown(request, "no legacy backend selected");
        }
        TellusSample result = Objects.requireNonNull(backend.sample(request), "backend sample");
        if (!request.equals(result.request())) {
            throw new IllegalArgumentException(
                "legacy backend " + backend.backendId() + " returned a different request");
        }
        // Return the result unchanged so UNKNOWN/PARTIAL/QUEUED and provenance
        // remain observable to the caller; no fallback or scheduling is hidden.
        return result;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
