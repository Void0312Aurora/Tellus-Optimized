package com.yucareux.tellus.worldgen.write;

import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Exact vanilla default states shared by native snapshots and versioned writers. */
public final class TellusNativeSemanticMaterials {
    private static final Map<BlockState, SemanticMaterial> MATERIALS = defaultMaterials();

    private TellusNativeSemanticMaterials() {
    }

    /** Maps a known semantic material to its exact vanilla default state. */
    public static BlockState state(SemanticMaterial material) {
        Block block = switch (Objects.requireNonNull(material, "material")) {
            case AIR -> Blocks.AIR;
            case BEDROCK -> Blocks.BEDROCK;
            case STONE -> Blocks.STONE;
            case DEEPSLATE -> Blocks.DEEPSLATE;
            case DIRT -> Blocks.DIRT;
            case COARSE_DIRT -> Blocks.COARSE_DIRT;
            case ROOTED_DIRT -> Blocks.ROOTED_DIRT;
            case PODZOL -> Blocks.PODZOL;
            case GRASS -> Blocks.GRASS_BLOCK;
            case MOSS -> Blocks.MOSS_BLOCK;
            case MYCELIUM -> Blocks.MYCELIUM;
            case SAND -> Blocks.SAND;
            case SANDSTONE -> Blocks.SANDSTONE;
            case GRAVEL -> Blocks.GRAVEL;
            case ANDESITE -> Blocks.ANDESITE;
            case DIORITE -> Blocks.DIORITE;
            case COBBLESTONE -> Blocks.COBBLESTONE;
            case CLAY -> Blocks.CLAY;
            case MUD -> Blocks.MUD;
            case PACKED_MUD -> Blocks.PACKED_MUD;
            case SNOW -> Blocks.SNOW_BLOCK;
            case POWDER_SNOW -> Blocks.POWDER_SNOW;
            case ICE -> Blocks.ICE;
            case PACKED_ICE -> Blocks.PACKED_ICE;
            case BLUE_ICE -> Blocks.BLUE_ICE;
            case WATER -> Blocks.WATER;
            case TERRACOTTA -> Blocks.TERRACOTTA;
            case RED_SAND -> Blocks.RED_SAND;
            case GRANITE -> Blocks.GRANITE;
            case CALCITE -> Blocks.CALCITE;
            case TUFF -> Blocks.TUFF;
            case BASALT -> Blocks.BASALT;
            case SMOOTH_BASALT -> Blocks.SMOOTH_BASALT;
            case BLACKSTONE -> Blocks.BLACKSTONE;
            case SMOOTH_SANDSTONE -> Blocks.SMOOTH_SANDSTONE;
            case RED_SANDSTONE -> Blocks.RED_SANDSTONE;
            case WHITE_TERRACOTTA -> Blocks.WHITE_TERRACOTTA;
            case LIGHT_GRAY_TERRACOTTA -> Blocks.LIGHT_GRAY_TERRACOTTA;
            case RED_TERRACOTTA -> Blocks.RED_TERRACOTTA;
            case ORANGE_TERRACOTTA -> Blocks.ORANGE_TERRACOTTA;
            case BROWN_TERRACOTTA -> Blocks.BROWN_TERRACOTTA;
            case YELLOW_TERRACOTTA -> Blocks.YELLOW_TERRACOTTA;
            case DRIPSTONE_BLOCK -> Blocks.DRIPSTONE_BLOCK;
            case COAL_ORE -> Blocks.COAL_ORE;
            case COPPER_ORE -> Blocks.COPPER_ORE;
            case IRON_ORE -> Blocks.IRON_ORE;
            case GOLD_ORE -> Blocks.GOLD_ORE;
            case EMERALD_ORE -> Blocks.EMERALD_ORE;
            case DEEPSLATE_COAL_ORE -> Blocks.DEEPSLATE_COAL_ORE;
            case DEEPSLATE_COPPER_ORE -> Blocks.DEEPSLATE_COPPER_ORE;
            case DEEPSLATE_IRON_ORE -> Blocks.DEEPSLATE_IRON_ORE;
            case DEEPSLATE_GOLD_ORE -> Blocks.DEEPSLATE_GOLD_ORE;
            case DEEPSLATE_EMERALD_ORE -> Blocks.DEEPSLATE_EMERALD_ORE;
            case UNKNOWN -> throw new IllegalArgumentException("UNKNOWN material cannot be mapped");
        };
        return block.defaultBlockState();
    }

    /** Returns a material only when every property matches its mapped default state. */
    public static Optional<SemanticMaterial> material(BlockState state) {
        return Optional.ofNullable(MATERIALS.get(Objects.requireNonNull(state, "state")));
    }

    private static Map<BlockState, SemanticMaterial> defaultMaterials() {
        Map<BlockState, SemanticMaterial> materials = new HashMap<>();
        for (SemanticMaterial material : SemanticMaterial.values()) {
            if (material != SemanticMaterial.UNKNOWN) {
                materials.put(state(material), material);
            }
        }
        return Map.copyOf(materials);
    }
}
