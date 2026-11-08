package com.yucareux.tellus.worldgen.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic first-match selector; list order is source precedence. */
public final class OrderedSampleBackendSelector implements TellusSourceBackendSelector {
    private final List<TellusSourceBackend> backends;

    public OrderedSampleBackendSelector(List<? extends TellusSourceBackend> backends) {
        Objects.requireNonNull(backends, "backends");
        ArrayList<TellusSourceBackend> copy = new ArrayList<>(backends.size());
        for (TellusSourceBackend backend : backends) {
            copy.add(Objects.requireNonNull(backend, "backend"));
        }
        this.backends = List.copyOf(copy);
    }

    public List<TellusSourceBackend> backends() {
        return this.backends;
    }

    @Override
    public TellusSourceBackend select(TellusSampleRequest request) {
        Objects.requireNonNull(request, "request");
        for (TellusSourceBackend backend : this.backends) {
            if (backend.supports(request)) return backend;
        }
        return null;
    }
}
