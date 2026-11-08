package com.yucareux.tellus.worldgen.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** Focused contract tests for the expanded Earth semantic palette boundary. */
class EarthSurfacePaletteTest {
    @Test
    void preservesUnderwaterStrataFormAndProductionFlags() {
        EarthSurfacePalette.Flags flags = new EarthSurfacePalette.Flags(true, true, true, true, true);
        EarthSurfacePalette palette = new EarthSurfacePalette(
            SemanticMaterial.GRASS,
            SemanticMaterial.MUD,
            SemanticMaterial.DIRT,
            4,
            SurfaceForm.VALLEY_WALL,
            true,
            flags);

        assertEquals(SemanticMaterial.GRASS, palette.top());
        assertEquals(SemanticMaterial.MUD, palette.underwaterTop());
        assertEquals(SemanticMaterial.DIRT, palette.filler());
        assertEquals(4, palette.depth());
        assertEquals(SurfaceForm.VALLEY_WALL, palette.form());
        assertTrue(palette.strata());
        assertTrue(palette.flags().water());
        assertTrue(palette.flags().shore());
        assertTrue(palette.flags().badlands());
        assertTrue(palette.flags().builtUp());
        assertTrue(palette.flags().randomBiomes());
    }

    @Test
    void surfaceDecisionProjectionRetainsCommonFieldsAndCompatibilityConstructor() {
        SurfaceDecision decision = new SurfaceDecision(
            SemanticMaterial.SAND, SemanticMaterial.SANDSTONE, 5, SurfaceForm.RIDGE);
        EarthSurfacePalette palette = EarthSurfacePaletteAdapter.fromSurfaceDecision(
            decision, SemanticMaterial.RED_SAND, false,
            new EarthSurfacePalette.Flags(false, true, false, false, true));

        assertEquals(decision, EarthSurfacePaletteAdapter.toSurfaceDecision(palette));
        assertEquals(SemanticMaterial.RED_SAND, palette.underwaterTop());
        assertFalse(palette.strata());
        assertTrue(palette.flags().shore());
        assertTrue(palette.flags().randomBiomes());

        assertEquals(SurfaceForm.NONE,
            new SurfaceDecision(SemanticMaterial.STONE, SemanticMaterial.STONE, 3).form());
        assertEquals(SurfaceDecision.defaultStone(),
            EarthSurfacePaletteAdapter.toSurfaceDecision(
                new EarthSurfacePalette(SemanticMaterial.STONE, SemanticMaterial.STONE,
                    SemanticMaterial.STONE, 3, SurfaceForm.NONE, false,
                    EarthSurfacePalette.Flags.none())));
    }

    @Test
    void rejectsUnknownMaterialsAndInvalidDepthInsteadOfInventingPalette() {
        EarthSurfacePalette.Flags flags = EarthSurfacePalette.Flags.none();
        assertThrows(IllegalArgumentException.class, () -> new EarthSurfacePalette(
            SemanticMaterial.UNKNOWN, SemanticMaterial.STONE, SemanticMaterial.STONE,
            3, SurfaceForm.NONE, false, flags));
        assertThrows(IllegalArgumentException.class, () -> new EarthSurfacePalette(
            SemanticMaterial.STONE, SemanticMaterial.UNKNOWN, SemanticMaterial.STONE,
            3, SurfaceForm.NONE, false, flags));
        assertThrows(IllegalArgumentException.class, () -> new EarthSurfacePalette(
            SemanticMaterial.STONE, SemanticMaterial.STONE, SemanticMaterial.UNKNOWN,
            3, SurfaceForm.NONE, false, flags));
        assertThrows(IllegalArgumentException.class, () -> new EarthSurfacePalette(
            SemanticMaterial.STONE, SemanticMaterial.STONE, SemanticMaterial.STONE,
            0, SurfaceForm.NONE, false, flags));
        assertThrows(IllegalArgumentException.class, () -> EarthSurfacePaletteAdapter.fromSurfaceDecision(
            SurfaceDecision.unknown(), SemanticMaterial.STONE, false, flags));
    }

    @Test
    void contractIsMinecraftFree() {
        for (Class<?> type : new Class<?>[]{EarthSurfacePalette.class, EarthSurfacePalette.Flags.class,
            EarthSurfacePaletteAdapter.class}) {
            assertFalse(type.getName().startsWith("net.minecraft."));
            for (Field field : type.getDeclaredFields()) {
                assertFalse(field.getType().getName().startsWith("net.minecraft."), field.getName());
            }
        }
    }
}
