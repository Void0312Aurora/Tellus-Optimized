package com.yucareux.tellus.worldgen.decision;

import java.util.List;
import java.util.Objects;

/** Immutable semantic underground layering, independent of Minecraft states. */
public record StrataDecision(List<Layer> layers, boolean known) {
    public record Layer(int minimumY, int maximumY, SemanticMaterial material) {
        public Layer {
            Objects.requireNonNull(material, "material");
            if (maximumY < minimumY) throw new IllegalArgumentException("layer range");
        }
    }

    public StrataDecision {
        layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
    }

    public static StrataDecision unknown() { return new StrataDecision(List.of(), false); }
    public static StrataDecision stone(int minimumY, int maximumY) {
        return new StrataDecision(List.of(new Layer(minimumY, maximumY, SemanticMaterial.STONE)), true);
    }
}
