package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy;
import com.yucareux.tellus.worldgen.MountainSurfaceRules;

/**
 * Pure compatibility adapter from the existing spherical surface policy to the
 * shared semantic surface contract. It performs no policy evaluation and does
 * not call a generator or writer.
 */
public final class CubeNetSurfaceDecisionAdapter {
    private CubeNetSurfaceDecisionAdapter() {
    }

    /** Maps an existing policy result without changing material, depth, or form. */
    public static SurfaceDecision map(CubeNetSurfacePolicy.Decision decision) {
        if (decision == null) {
            return SurfaceDecision.unknown();
        }
        return new SurfaceDecision(
            mapMaterial(decision.top()),
            mapMaterial(decision.filler()),
            decision.depth(),
            mapForm(decision.form())
        );
    }

    public static SemanticMaterial mapMaterial(CubeNetSurfacePolicy.Material material) {
        if (material == null) return SemanticMaterial.UNKNOWN;
        return switch (material) {
            case GRASS -> SemanticMaterial.GRASS;
            case DIRT -> SemanticMaterial.DIRT;
            case COARSE_DIRT -> SemanticMaterial.COARSE_DIRT;
            case PODZOL -> SemanticMaterial.PODZOL;
            case ROOTED_DIRT -> SemanticMaterial.ROOTED_DIRT;
            case MOSS -> SemanticMaterial.MOSS;
            case MYCELIUM -> SemanticMaterial.MYCELIUM;
            case SAND -> SemanticMaterial.SAND;
            case SANDSTONE -> SemanticMaterial.SANDSTONE;
            case RED_SAND -> SemanticMaterial.RED_SAND;
            case SNOW -> SemanticMaterial.SNOW;
            case POWDER_SNOW -> SemanticMaterial.POWDER_SNOW;
            case STONE -> SemanticMaterial.STONE;
            case GRAVEL -> SemanticMaterial.GRAVEL;
            case ANDESITE -> SemanticMaterial.ANDESITE;
            case DIORITE -> SemanticMaterial.DIORITE;
            case COBBLESTONE -> SemanticMaterial.COBBLESTONE;
            case PACKED_ICE -> SemanticMaterial.PACKED_ICE;
            case BLUE_ICE -> SemanticMaterial.BLUE_ICE;
            case MUD -> SemanticMaterial.MUD;
            case PACKED_MUD -> SemanticMaterial.PACKED_MUD;
            case TERRACOTTA -> SemanticMaterial.TERRACOTTA;
        };
    }

    public static SurfaceForm mapForm(MountainSurfaceRules.MountainForm form) {
        if (form == null) return SurfaceForm.NONE;
        return switch (form) {
            case NONE -> SurfaceForm.NONE;
            case RIDGE -> SurfaceForm.RIDGE;
            case CLIFF_FACE -> SurfaceForm.CLIFF_FACE;
            case STEEP_ROCKY_SLOPE -> SurfaceForm.STEEP_ROCKY_SLOPE;
            case TALUS_SLOPE -> SurfaceForm.TALUS_SLOPE;
            case VALLEY_WALL -> SurfaceForm.VALLEY_WALL;
            case SNOWFIELD -> SurfaceForm.SNOWFIELD;
            case GLACIER_BASIN -> SurfaceForm.GLACIER_BASIN;
            case DRAINAGE_CHUTE -> SurfaceForm.DRAINAGE_CHUTE;
            case ALPINE_MEADOW -> SurfaceForm.ALPINE_MEADOW;
        };
    }
}
