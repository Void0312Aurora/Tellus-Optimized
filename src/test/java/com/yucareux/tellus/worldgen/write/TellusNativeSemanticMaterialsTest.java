package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TellusNativeSemanticMaterialsTest {
    // Persisted ordinals: keep this literal independent of the enum and mapper.
    private static final List<String> ORIGINAL_IDS = List.of(
        "UNKNOWN", "AIR", "BEDROCK", "STONE", "DEEPSLATE", "DIRT", "COARSE_DIRT",
        "ROOTED_DIRT", "PODZOL", "GRASS", "MOSS", "MYCELIUM", "SAND", "SANDSTONE",
        "GRAVEL", "ANDESITE", "DIORITE", "COBBLESTONE", "CLAY", "MUD", "PACKED_MUD",
        "SNOW", "POWDER_SNOW", "ICE", "PACKED_ICE", "BLUE_ICE", "WATER", "TERRACOTTA",
        "RED_SAND", "GRANITE", "CALCITE", "TUFF", "BASALT", "SMOOTH_BASALT", "BLACKSTONE",
        "SMOOTH_SANDSTONE", "RED_SANDSTONE", "WHITE_TERRACOTTA", "LIGHT_GRAY_TERRACOTTA",
        "RED_TERRACOTTA", "ORANGE_TERRACOTTA");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyKnownMaterialRoundTripsItsExactDefaultState() {
        int roundTrips = 0;
        for (SemanticMaterial material : SemanticMaterial.values()) {
            if (material == SemanticMaterial.UNKNOWN) continue;
            BlockState state = TellusNativeSemanticMaterials.state(material);
            assertSame(state.getBlock().defaultBlockState(), state, material.name());
            assertEquals(Optional.of(material), TellusNativeSemanticMaterials.material(state), material.name());
            roundTrips++;
        }
        assertEquals(53, roundTrips);
        System.out.println("TELLUS_NATIVE_SEMANTIC_MATERIALS roundTrips=" + roundTrips
            + " originalIds=" + ORIGINAL_IDS.size() + " appendedIds=13 totalIds=" + SemanticMaterial.values().length);
    }

    @Test
    void originalOrdinalPrefixStaysStable() {
        assertEquals(41, ORIGINAL_IDS.size());
        SemanticMaterial[] materials = SemanticMaterial.values();
        assertEquals(54, materials.length);
        for (int ordinal = 0; ordinal < ORIGINAL_IDS.size(); ordinal++) {
            String name = ORIGINAL_IDS.get(ordinal);
            assertEquals(name, materials[ordinal].name(), "persisted ordinal " + ordinal);
            assertEquals(ordinal, SemanticMaterial.valueOf(name).ordinal(), name);
        }
    }

    @Test
    void thirteenNewIdsAreAppendedAfterTheOriginalPrefix() {
        List<String> additions = List.of(
            "BROWN_TERRACOTTA", "YELLOW_TERRACOTTA", "DRIPSTONE_BLOCK",
            "COAL_ORE", "COPPER_ORE", "IRON_ORE", "GOLD_ORE", "EMERALD_ORE",
            "DEEPSLATE_COAL_ORE", "DEEPSLATE_COPPER_ORE", "DEEPSLATE_IRON_ORE",
            "DEEPSLATE_GOLD_ORE", "DEEPSLATE_EMERALD_ORE");
        assertEquals(13, additions.size());
        for (int index = 0; index < additions.size(); index++) {
            assertEquals(additions.get(index), SemanticMaterial.values()[ORIGINAL_IDS.size() + index].name());
        }
    }

    @Test
    void legacyGrassMossAndSnowAliasesUseFullDefaultBlocks() {
        assertMapping(SemanticMaterial.GRASS, Blocks.GRASS_BLOCK);
        assertMapping(SemanticMaterial.MOSS, Blocks.MOSS_BLOCK);
        assertMapping(SemanticMaterial.SNOW, Blocks.SNOW_BLOCK);
        assertTrue(TellusNativeSemanticMaterials.material(Blocks.SNOW.defaultBlockState()).isEmpty());
    }

    @Test
    void nativeOresBandsAndDripstoneHaveExplicitExactStates() {
        Map<SemanticMaterial, Block> expected = Map.ofEntries(
            Map.entry(SemanticMaterial.TERRACOTTA, Blocks.TERRACOTTA),
            Map.entry(SemanticMaterial.WHITE_TERRACOTTA, Blocks.WHITE_TERRACOTTA),
            Map.entry(SemanticMaterial.LIGHT_GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA),
            Map.entry(SemanticMaterial.RED_TERRACOTTA, Blocks.RED_TERRACOTTA),
            Map.entry(SemanticMaterial.ORANGE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA),
            Map.entry(SemanticMaterial.BROWN_TERRACOTTA, Blocks.BROWN_TERRACOTTA),
            Map.entry(SemanticMaterial.YELLOW_TERRACOTTA, Blocks.YELLOW_TERRACOTTA),
            Map.entry(SemanticMaterial.DRIPSTONE_BLOCK, Blocks.DRIPSTONE_BLOCK),
            Map.entry(SemanticMaterial.COAL_ORE, Blocks.COAL_ORE),
            Map.entry(SemanticMaterial.COPPER_ORE, Blocks.COPPER_ORE),
            Map.entry(SemanticMaterial.IRON_ORE, Blocks.IRON_ORE),
            Map.entry(SemanticMaterial.GOLD_ORE, Blocks.GOLD_ORE),
            Map.entry(SemanticMaterial.EMERALD_ORE, Blocks.EMERALD_ORE),
            Map.entry(SemanticMaterial.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
            Map.entry(SemanticMaterial.DEEPSLATE_COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
            Map.entry(SemanticMaterial.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
            Map.entry(SemanticMaterial.DEEPSLATE_GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
            Map.entry(SemanticMaterial.DEEPSLATE_EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE));
        assertEquals(18, expected.size());
        expected.forEach(TellusNativeSemanticMaterialsTest::assertMapping);
        System.out.println("TELLUS_NATIVE_SEMANTIC_EXPLICIT oreStates=10 bandStates=7 dripstoneStates=1");
    }

    @Test
    void unknownSemanticMaterialThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> TellusNativeSemanticMaterials.state(SemanticMaterial.UNKNOWN));
    }

    @Test
    void snowyGrassAndFlowingWaterAreRejectedWithoutErasingProperties() {
        BlockState snowyGrass = Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true);
        BlockState flowingWater = Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 1);
        assertTrue(TellusNativeSemanticMaterials.material(snowyGrass).isEmpty());
        assertTrue(TellusNativeSemanticMaterials.material(flowingWater).isEmpty());
        assertTrue(snowyGrass.getValue(BlockStateProperties.SNOWY));
        assertEquals(1, flowingWater.getValue(BlockStateProperties.LEVEL).intValue());
        assertMapping(SemanticMaterial.GRASS, Blocks.GRASS_BLOCK);
        assertMapping(SemanticMaterial.WATER, Blocks.WATER);
    }

    @Test
    void everyNonDefaultPropertyCombinationOfMappedBlocksIsRejected() {
        int rejected = 0;
        for (SemanticMaterial material : SemanticMaterial.values()) {
            if (material == SemanticMaterial.UNKNOWN) continue;
            BlockState defaultState = TellusNativeSemanticMaterials.state(material);
            for (BlockState state : defaultState.getBlock().getStateDefinition().getPossibleStates()) {
                if (state.equals(defaultState)) continue;
                assertTrue(TellusNativeSemanticMaterials.material(state).isEmpty(), material.name() + ": " + state);
                rejected++;
            }
        }
        assertTrue(rejected >= 2, "snowy grass and flowing water must be covered");
        System.out.println("TELLUS_NATIVE_SEMANTIC_PROPERTIES rejectedNonDefaultStates=" + rejected);
    }

    @Test
    void foreignBlocksStayUnrecognizedIncludingAirAndSnowLookalikes() {
        List<Block> foreign = List.of(Blocks.DIAMOND_BLOCK, Blocks.NETHERRACK, Blocks.CAVE_AIR, Blocks.SNOW);
        for (Block block : foreign) {
            assertTrue(TellusNativeSemanticMaterials.material(block.defaultBlockState()).isEmpty());
        }
        System.out.println("TELLUS_NATIVE_SEMANTIC_FOREIGN rejectedBlocks=" + foreign.size());
    }

    private static void assertMapping(SemanticMaterial material, Block block) {
        assertSame(block.defaultBlockState(), TellusNativeSemanticMaterials.state(material), material.name());
        assertEquals(Optional.of(material), TellusNativeSemanticMaterials.material(block.defaultBlockState()), material.name());
    }
}
