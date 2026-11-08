package com.yucareux.tellus.worldgen.sample;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Fake/snapshot bridge for tests and preload packages. It only reads the
 * injected function; missing data is represented by the returned sample.
 */
public final class SnapshotSampleBackend implements TellusSourceBackend {
    private final String backendId;
    private final Predicate<TellusSampleRequest> support;
    private final Function<TellusSampleRequest, TellusSample> snapshot;

    public SnapshotSampleBackend(
        String backendId,
        Predicate<TellusSampleRequest> support,
        Function<TellusSampleRequest, TellusSample> snapshot
    ) {
        this.backendId = requireText(backendId, "backendId");
        this.support = Objects.requireNonNull(support, "support");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public static SnapshotSampleBackend fixed(
        String backendId,
        Predicate<TellusSampleRequest> support,
        TellusSample sample
    ) {
        return new SnapshotSampleBackend(backendId, support, ignored -> sample);
    }

    @Override
    public String backendId() {
        return this.backendId;
    }

    @Override
    public boolean supports(TellusSampleRequest request) {
        return this.support.test(Objects.requireNonNull(request, "request"));
    }

    @Override
    public TellusSample sample(TellusSampleRequest request) {
        return Objects.requireNonNull(
            this.snapshot.apply(Objects.requireNonNull(request, "request")), "snapshot");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
