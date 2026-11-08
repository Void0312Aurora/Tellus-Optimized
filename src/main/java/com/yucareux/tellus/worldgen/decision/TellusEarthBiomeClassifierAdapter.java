package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.world.data.biome.BiomeClassification;
import com.yucareux.tellus.world.data.biome.BiomeClassificationProviders;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Narrow Earth classifier adapter. It preserves the legacy provider -> exact
 * table -> cover fallback order and converts only the final ResourceKey.
 */
public final class TellusEarthBiomeClassifierAdapter implements TellusEarthBiomeDecisionKernel.Resolver {
    @FunctionalInterface
    public interface ProviderResolver {
        ResourceKey<Biome> resolve(
            int visualCoverClass, String koppenCode, int blockX, int blockZ, double worldScale);
    }

    @FunctionalInterface
    public interface ExactResolver {
        ResourceKey<Biome> resolve(int visualCoverClass, String koppenCode);
    }

    @FunctionalInterface
    public interface FallbackResolver {
        ResourceKey<Biome> resolve(int visualCoverClass);
    }

    private final ProviderResolver provider;
    private final ExactResolver exact;
    private final FallbackResolver fallback;

    public TellusEarthBiomeClassifierAdapter(
        ProviderResolver provider,
        ExactResolver exact,
        FallbackResolver fallback
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.exact = Objects.requireNonNull(exact, "exact");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /** Production wiring; construction is explicit and has no callsite side effects. */
    public static TellusEarthBiomeClassifierAdapter production() {
        return new TellusEarthBiomeClassifierAdapter(
            BiomeClassificationProviders::findBiomeKey,
            BiomeClassification::findBiomeKey,
            BiomeClassification::findFallbackKey);
    }

    public static TellusEarthBiomeDecisionKernel.Resolver resolver() {
        return production();
    }

    @Override
    public String resolve(
        int visualCoverClass,
        String koppenCode,
        long floorBlockX,
        long floorBlockZ,
        double worldScale
    ) {
        if (floorBlockX < Integer.MIN_VALUE || floorBlockX > Integer.MAX_VALUE
            || floorBlockZ < Integer.MIN_VALUE || floorBlockZ > Integer.MAX_VALUE) {
            return null;
        }
        ResourceKey<Biome> key = this.provider.resolve(
            visualCoverClass, koppenCode, (int)floorBlockX, (int)floorBlockZ, worldScale);
        if (key == null) key = this.exact.resolve(visualCoverClass, koppenCode);
        if (key == null) key = this.fallback.resolve(visualCoverClass);
        return key == null ? null : key.location().toString();
    }
}
