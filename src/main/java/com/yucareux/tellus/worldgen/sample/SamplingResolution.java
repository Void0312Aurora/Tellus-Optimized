package com.yucareux.tellus.worldgen.sample;

import java.util.Objects;

/**
 * Stable, quantized resolution declaration. Cache identity uses the integer
 * power-of-two metre bucket rather than a caller's raw floating-point value.
 */
public record SamplingResolution(Kind kind, int metres) {
    public enum Kind {
        EXACT,
        PREVIEW,
        LOD,
        NON_BLOCKING
    }

    public SamplingResolution {
        Objects.requireNonNull(kind, "kind");
        if (metres <= 0) throw new IllegalArgumentException("metres must be positive");
        if ((metres & (metres - 1)) != 0) {
            throw new IllegalArgumentException("metres must be a power-of-two bucket");
        }
    }

    /** Quantizes a requested floating-point resolution upward to a stable bucket. */
    public static SamplingResolution of(Kind kind, double requestedMetres) {
        return new SamplingResolution(kind, quantize(requestedMetres));
    }

    public static SamplingResolution exact(double requestedMetres) {
        return of(Kind.EXACT, requestedMetres);
    }

    public static SamplingResolution preview(double requestedMetres) {
        return of(Kind.PREVIEW, requestedMetres);
    }

    public static SamplingResolution lod(double requestedMetres) {
        return of(Kind.LOD, requestedMetres);
    }

    /** Non-blocking means read snapshot/return queued; it never implies scheduling here. */
    public static SamplingResolution nonBlocking(double requestedMetres) {
        return of(Kind.NON_BLOCKING, requestedMetres);
    }

    /** Canonical bucket used by record equality and CacheKey equality. */
    public int canonicalizedMetres() {
        return this.metres;
    }

    /** Stable diagnostic/cache projection independent of raw request doubles. */
    public String quantizedKey() {
        return this.kind.name() + ":" + this.metres;
    }

    private static int quantize(double requestedMetres) {
        if (!(requestedMetres > 0.0) || !Double.isFinite(requestedMetres)) {
            throw new IllegalArgumentException("requestedMetres must be finite and positive");
        }
        double ceiling = Math.ceil(requestedMetres);
        if (ceiling > (double)(1 << 30)) throw new IllegalArgumentException("resolution is too large");
        int bucket = 1;
        while ((double)bucket < ceiling) bucket <<= 1;
        return bucket;
    }
}
