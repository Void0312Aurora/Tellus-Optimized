package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yucareux.tellus.worldgen.CubeNetSurfacePolicy;
import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import com.yucareux.tellus.worldgen.TerrainSlopePolicy;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import org.junit.jupiter.api.Test;

/** Lossless semantic mapping tests for the existing spherical surface decision. */
class CubeNetSurfaceDecisionAdapterTest {
    private static final SphereWorldModel.GeoPoint POINT = new SphereWorldModel.GeoPoint(46.0, 12.0, 0.0);

    @Test
    void mapsEveryPolicyMaterialWithoutCollapsingDistinctOutputs() {
        for (CubeNetSurfacePolicy.Material material : CubeNetSurfacePolicy.Material.values()) {
            CubeNetSurfacePolicy.Decision source = new CubeNetSurfacePolicy.Decision(
                material, material, 7, 2, 31.5, MountainSurfaceRules.MountainForm.RIDGE);
            SurfaceDecision mapped = CubeNetSurfaceDecisionAdapter.map(source);

            assertEquals(SemanticMaterial.valueOf(material.name()), mapped.top(), material.name());
            assertEquals(SemanticMaterial.valueOf(material.name()), mapped.filler(), material.name());
            assertEquals(7, mapped.depth());
            assertEquals(SurfaceForm.RIDGE, mapped.form());
        }
    }

    @Test
    void mapsEveryPolicyFormAndPreservesDefaultUnknownBoundaries() {
        for (MountainSurfaceRules.MountainForm form : MountainSurfaceRules.MountainForm.values()) {
            CubeNetSurfacePolicy.Decision source = new CubeNetSurfacePolicy.Decision(
                CubeNetSurfacePolicy.Material.STONE, CubeNetSurfacePolicy.Material.STONE,
                3, 0, Double.NaN, form);
            assertEquals(SurfaceForm.valueOf(form.name()),
                CubeNetSurfaceDecisionAdapter.map(source).form());
        }

        assertEquals(SurfaceDecision.defaultStone(),
            CubeNetSurfaceDecisionAdapter.map(CubeNetSurfacePolicy.Decision.DEFAULT));
        assertEquals(SurfaceDecision.unknown(), CubeNetSurfaceDecisionAdapter.map(null));
    }

    @Test
    void existingPolicyFixturesMapMaterialDepthAndFormExactly() {
        SphereChunkSampler.SurfaceData data = new SphereChunkSampler.SurfaceData(
            20.0, MountainSurfaceRules.ESA_GRASSLAND);
        CubeNetSurfacePolicy.Decision source = CubeNetSurfacePolicy.resolve(POINT, data, 10.0, 7L);
        SurfaceDecision mapped = CubeNetSurfaceDecisionAdapter.map(source);

        assertEquals(source.top().name(), mapped.top().name());
        assertEquals(source.filler().name(), mapped.filler().name());
        assertEquals(source.depth(), mapped.depth());
        assertEquals(source.form().name(), mapped.form().name());
    }

    @Test
    void steepAndSnowFixturesRetainTheirExistingSurfaceSemantics() {
        TerrainSlopePolicy.Gradient steepGradient = new TerrainSlopePolicy.Gradient(2000.0, 65.0, 0.0, 1.0);
        SphereChunkSampler.SurfaceData steepData = new SphereChunkSampler.SurfaceData(
            2000.0, MountainSurfaceRules.ESA_GRASSLAND, Double.NaN,
            SphereChunkSampler.SurfaceData.WaterKind.NONE, null,
            new TerrainSlopePolicy.Neighbourhood(steepGradient, 0.5));
        CubeNetSurfacePolicy.Decision steep = CubeNetSurfacePolicy.resolve(POINT, steepData, 10.0, 7L);
        SurfaceDecision steepMapped = CubeNetSurfaceDecisionAdapter.map(steep);
        assertEquals(steep.top().name(), steepMapped.top().name());
        assertEquals(steep.filler().name(), steepMapped.filler().name());
        assertEquals(steep.depth(), steepMapped.depth());
        assertEquals(steep.form().name(), steepMapped.form().name());

        TerrainSlopePolicy.Gradient snowGradient = new TerrainSlopePolicy.Gradient(2500.0, 0.0, 0.0, 0.0);
        SphereChunkSampler.SurfaceData snowData = new SphereChunkSampler.SurfaceData(
            2500.0, MountainSurfaceRules.ESA_SNOW_ICE, Double.NaN,
            SphereChunkSampler.SurfaceData.WaterKind.NONE, null,
            new TerrainSlopePolicy.Neighbourhood(snowGradient, 0.0));
        CubeNetSurfacePolicy.Decision snow = CubeNetSurfacePolicy.resolve(POINT, snowData, 10.0, 7L);
        SurfaceDecision snowMapped = CubeNetSurfaceDecisionAdapter.map(snow);
        assertEquals(snow.top().name(), snowMapped.top().name());
        assertEquals(snow.filler().name(), snowMapped.filler().name());
        assertEquals(snow.depth(), snowMapped.depth());
        assertEquals(snow.form().name(), snowMapped.form().name());
    }
}
