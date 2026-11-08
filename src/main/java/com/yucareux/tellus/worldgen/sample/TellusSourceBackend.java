package com.yucareux.tellus.worldgen.sample;

/**
 * Source-facing alias for the injectable legacy/snapshot backend boundary.
 * It intentionally contains no source implementation or scheduling behavior.
 */
public interface TellusSourceBackend {
    String backendId();
    boolean supports(TellusSampleRequest request);
    TellusSample sample(TellusSampleRequest request);
}
