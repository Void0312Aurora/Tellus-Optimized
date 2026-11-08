package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.sample.TellusSampleProvider;
import com.yucareux.tellus.worldgen.sample.TellusSampleProviderFactory;
import com.yucareux.tellus.worldgen.sample.TellusSourceBackend;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import java.util.List;

/** Worldgen-package facade for the pure sample provider composition root. */
public final class TellusWorldgenSourcesProviderFactory {
    private TellusWorldgenSourcesProviderFactory() {
    }

    public static TellusSampleProvider forEarth(
        EarthGeneratorSettings settings,
        List<? extends TellusSourceBackend> optionalBackends
    ) {
        return TellusSampleProviderFactory.forEarth(settings, optionalBackends);
    }

    public static TellusSampleProvider forEarth(EarthGeneratorSettings settings) {
        return TellusSampleProviderFactory.forEarth(settings);
    }

    public static TellusSampleProvider forCubeNet(TellusSampleProvider sphericalProvider) {
        return TellusSampleProviderFactory.forCubeNet(sphericalProvider);
    }

    public static TellusSampleProvider forSphereFace(TellusSampleProvider sphericalProvider) {
        return TellusSampleProviderFactory.forSphereFace(sphericalProvider);
    }
    public static TellusSampleProvider forCubeNet(SphereChunkSampler sampler) {
        return TellusSampleProviderFactory.forCubeNet(sampler);
    }

    public static TellusSampleProvider forSphereFace(SphereChunkSampler sampler) {
        return TellusSampleProviderFactory.forSphereFace(sampler);
    }

}