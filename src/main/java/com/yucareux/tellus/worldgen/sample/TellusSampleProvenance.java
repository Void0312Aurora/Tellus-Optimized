package com.yucareux.tellus.worldgen.sample;

import java.util.Objects;

/** Source provenance carried by an immutable sample without binding to a source implementation. */
public record TellusSampleProvenance(
    String sourceId,
    String sourceRevision,
    boolean cacheHit,
    boolean snapshot,
    boolean queued,
    String note
) {
    public TellusSampleProvenance {
        sourceId = requireText(sourceId, "sourceId");
        sourceRevision = requireText(sourceRevision, "sourceRevision");
        note = Objects.requireNonNull(note, "note");
        if (queued && snapshot) throw new IllegalArgumentException("queued result is not a snapshot");
    }

    public static TellusSampleProvenance unknown(String note) {
        return new TellusSampleProvenance("unknown", "unknown", false, false, false, note);
    }

    public static TellusSampleProvenance snapshot(String sourceId, String revision, boolean cacheHit, String note) {
        return new TellusSampleProvenance(sourceId, revision, cacheHit, true, false, note);
    }

    public static TellusSampleProvenance queued(String sourceId, String revision, String note) {
        return new TellusSampleProvenance(sourceId, revision, false, false, true, note);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
