package com.yucareux.tellus.worldgen.sample;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.TellusWorldgenEarthSampleBackend;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Concrete composition root for sample providers; no generator/lifecycle wiring. */
public final class TellusSampleProviderFactory {
    private TellusSampleProviderFactory() {
    }

    /**
     * Composes optional snapshot/preload backends before the Earth legacy bridge.
     * The supplied list is copied and its order is the explicit precedence.
     */
    public static TellusSampleProvider forEarth(
        EarthGeneratorSettings settings,
        List<? extends TellusSourceBackend> optionalBackends
    ) {
        Objects.requireNonNull(settings, "settings");
        ArrayList<TellusSourceBackend> ordered = copy(optionalBackends);
        ordered.add(new TellusWorldgenEarthSampleBackend(settings));
        return new TellusLegacySampleProviderAdapter(
            "tellus:earth-composed", new OrderedSampleBackendSelector(ordered));
    }

    /** Composes snapshot/preload backends, Earth source backend, then injected legacy backends. */
    public static TellusSampleProvider forEarth(
        EarthGeneratorSettings settings,
        List<? extends TellusSourceBackend> snapshotBackends,
        List<? extends TellusSourceBackend> legacyBackends
    ) {
        Objects.requireNonNull(settings, "settings");
        ArrayList<TellusSourceBackend> ordered = copy(snapshotBackends);
        ordered.add(new TellusWorldgenEarthSampleBackend(settings));
        ordered.addAll(copy(legacyBackends));
        return new TellusLegacySampleProviderAdapter(
            "tellus:earth-composed", new OrderedSampleBackendSelector(ordered));
    }

    public static TellusSampleProvider forEarth(EarthGeneratorSettings settings) {
        return forEarth(settings, List.of(), List.of());
    }


    public static TellusSampleProvider forCubeNet(TellusSampleProvider sphericalProvider) {
        return TellusSphericalSampleProvider.forCubeNet(sphericalProvider);
    }

    public static TellusSampleProvider forSphereFace(TellusSampleProvider sphericalProvider) {
        return TellusSphericalSampleProvider.forSphereFace(sphericalProvider);
    }


    public static TellusSampleProvider forCubeNet(SphereChunkSampler sampler) {
        return TellusSphericalSampleProvider.forCubeNet(sampler);
    }

    /** Routes Sphere-Face requests to an injected SphereChunkSampler. */
    public static TellusSampleProvider forSphereFace(SphereChunkSampler sampler) {
        return TellusSphericalSampleProvider.forSphereFace(sampler);
    }

    private static ArrayList<TellusSourceBackend> copy(
        List<? extends TellusSourceBackend> backends
    ) {
        Objects.requireNonNull(backends, "backends");
        ArrayList<TellusSourceBackend> copy = new ArrayList<>(backends.size());
        for (TellusSourceBackend backend : backends) {
            copy.add(Objects.requireNonNull(backend, "backend"));
        }
        return copy;
    }
}
