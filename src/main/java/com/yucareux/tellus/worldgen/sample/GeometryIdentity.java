package com.yucareux.tellus.worldgen.sample;

import java.util.Locale;
import java.util.Objects;

/** Stable geometry/projection/noise identity used by requests and cache keys. */
public record GeometryIdentity(
    String id,
    String revision,
    String projectionId,
    String noiseAlgorithmId,
    double worldScale
) {
    /** Legacy shorthand retains explicit UNKNOWN labels until provider normalization. */
    public GeometryIdentity(String id, String revision, double worldScale) {
        this(id, revision, "unknown-projection", "unknown-noise", worldScale);
    }

    public GeometryIdentity {
        id = requireStableText(id, "id");
        revision = requireStableText(revision, "revision");
        projectionId = requireStableText(projectionId, "projectionId");
        noiseAlgorithmId = requireStableText(noiseAlgorithmId, "noiseAlgorithmId");
        if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) {
            throw new IllegalArgumentException("worldScale must be finite and positive");
        }
    }

    /** Provider-facing constructor requiring explicit projection and noise identities. */
    public static GeometryIdentity forProvider(
        String id, String revision, String projectionId, String noiseAlgorithmId, double worldScale
    ) {
        GeometryIdentity identity = new GeometryIdentity(
            id, revision, projectionId, noiseAlgorithmId, worldScale);
        return identity.requireConcrete();
    }

    /** True when projection and noise identities are explicit rather than unknown placeholders. */
    public boolean concrete() {
        return !isUnknown(this.projectionId) && !isUnknown(this.noiseAlgorithmId);
    }

    public GeometryIdentity requireConcrete() {
        if (!concrete()) {
            throw new IllegalArgumentException(
                "provider requests require concrete projectionId and noiseAlgorithmId");
        }
        return this;
    }

    /** Normalizes legacy shorthand before it becomes provider-facing request identity. */
    public GeometryIdentity forRequest() {
        return concrete() ? this
            : new GeometryIdentity(this.id, this.revision,
                "legacy-projection-v1", "legacy-noise-v1", this.worldScale);
    }

    /** Stable revision identity including projection and noise algorithm versions. */
    public String revisionKey() {
        return this.revision + "|projection=" + this.projectionId
            + "|noise=" + this.noiseAlgorithmId;
    }

    /** Stable complete geometry identity used in diagnostics and cache projections. */
    public String stableKey() {
        return this.id + "@" + revisionKey() + "|scale=" + Double.toString(this.worldScale);
    }

    private static boolean isUnknown(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.equals("unknown") || normalized.startsWith("unknown-");
    }

    private static String requireStableText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.indexOf('|') >= 0 || value.indexOf('=') >= 0) {
            throw new IllegalArgumentException(name + " must be nonblank and delimiter-free");
        }
        return value;
    }
}
