package com.yucareux.tellus.worldgen.feature;

import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable semantic placement. Coordinates are relative to the supplied
 * owner context; downstream writers resolve them into their local view.
 */
public record FeaturePlacement(
    FeatureKind kind,
    String featureId,
    GeneratorOwner owner,
    GeneratorOwner canonicalOwner,
    PlacementOperation operation,
    PlacementConflictPolicy conflictPolicy,
    SemanticMaterial material,
    int offsetX,
    int offsetY,
    int offsetZ,
    int height,
    boolean apronMirror,
    Map<String, String> metadata
) {
    public FeaturePlacement {
        Objects.requireNonNull(kind, "kind");
        featureId = requireText(featureId, "featureId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(canonicalOwner, "canonicalOwner");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        Objects.requireNonNull(material, "material");
        if (height < 1) throw new IllegalArgumentException("height");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (apronMirror && operation != PlacementOperation.MIRROR) {
            throw new IllegalArgumentException("apron mirror requires MIRROR operation");
        }
        if (!apronMirror && owner != canonicalOwner && operation != PlacementOperation.MIRROR) {
            throw new IllegalArgumentException("non-canonical owner requires mirror operation");
        }
    }

    public static FeaturePlacement canonical(
        FeatureKind kind, String featureId, TellusCoordinateContext context,
        PlacementOperation operation, PlacementConflictPolicy conflictPolicy,
        SemanticMaterial material, int offsetX, int offsetY, int offsetZ,
        int height, Map<String, String> metadata
    ) {
        Objects.requireNonNull(context, "context");
        GeneratorOwner owner = context.owner().kind();
        return new FeaturePlacement(kind, featureId, owner, owner, operation,
            conflictPolicy, material, offsetX, offsetY, offsetZ, height, false, metadata);
    }

    public static FeaturePlacement apronMirror(
        FeatureKind kind, String featureId, TellusCoordinateContext context,
        PlacementConflictPolicy conflictPolicy, SemanticMaterial material,
        int offsetX, int offsetY, int offsetZ, int height,
        Map<String, String> metadata
    ) {
        Objects.requireNonNull(context, "context");
        if (!(context.owner() instanceof com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView apron)) {
            throw new IllegalArgumentException("apron mirror requires Cube-Net apron context");
        }
        return new FeaturePlacement(kind, featureId, GeneratorOwner.CUBE_NET_APRON,
            apron.canonicalOwner().kind(), PlacementOperation.MIRROR, conflictPolicy,
            material, offsetX, offsetY, offsetZ, height, true, metadata);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name);
        return value;
    }
}
