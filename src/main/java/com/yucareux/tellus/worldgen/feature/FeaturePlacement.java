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
    Map<String, String> metadata,
    java.util.Optional<WaterFlowState> flowState
) {
    /** Source-compatible default-state placement; no state properties or metadata transaction. */
    public FeaturePlacement(FeatureKind kind, String featureId, GeneratorOwner owner, GeneratorOwner canonicalOwner,
        PlacementOperation operation, PlacementConflictPolicy conflictPolicy, SemanticMaterial material,
        int offsetX, int offsetY, int offsetZ, int height, boolean apronMirror, Map<String, String> metadata) {
        this(kind, featureId, owner, canonicalOwner, operation, conflictPolicy, material,
            offsetX, offsetY, offsetZ, height, apronMirror, metadata, java.util.Optional.empty());
    }
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
        flowState = Objects.requireNonNull(flowState, "flowState");
        if (flowState.isPresent() && (material != SemanticMaterial.WATER || apronMirror || owner == GeneratorOwner.CUBE_NET_APRON || owner != canonicalOwner
            || conflictPolicy != PlacementConflictPolicy.OWNER_ONLY
            || operation != PlacementOperation.SET && operation != PlacementOperation.FILL)) {
            throw new IllegalArgumentException("exact flow requires canonical WATER SET/FILL OWNER_ONLY placement");
        }
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

    public static FeaturePlacement canonicalFlow(TellusCoordinateContext context, String featureId,
        int bottomY, int height, int level) {
        Objects.requireNonNull(context, "context");
        GeneratorOwner owner = context.owner().kind();
        if (owner == GeneratorOwner.CUBE_NET_APRON) throw new IllegalArgumentException("canonical flow excludes apron");
        return new FeaturePlacement(FeatureKind.CUSTOM, featureId, owner, owner, PlacementOperation.FILL,
            PlacementConflictPolicy.OWNER_ONLY, SemanticMaterial.WATER, 0, bottomY, 0, height, false,
            Map.of("stage", "INLAND_FLOW"), java.util.Optional.of(new WaterFlowState(level)));
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
