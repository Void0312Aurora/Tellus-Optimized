package com.yucareux.tellus.worldgen.sample;

/** Compatibility selector name with a covariant sample-backend return. */
@FunctionalInterface
public interface TellusSampleBackendSelector extends TellusSourceBackendSelector {
    @Override
    TellusSampleBackend select(TellusSampleRequest request);
}
