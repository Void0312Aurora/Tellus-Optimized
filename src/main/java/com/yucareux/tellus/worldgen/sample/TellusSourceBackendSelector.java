package com.yucareux.tellus.worldgen.sample;

/** Pure source precedence boundary, separated from provider execution. */
@FunctionalInterface
public interface TellusSourceBackendSelector {
    /** Selects one backend by explicit precedence; null means no source applies. */
    TellusSourceBackend select(TellusSampleRequest request);
}
