package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.decision.BiomeDecision;
import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.StrataDecision;
import com.yucareux.tellus.worldgen.decision.SurfaceDecision;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.WaterDecision;
import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.ImmutableTellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Focused Minecraft 1.20.1 mapper/canonical-adapter contracts. */
class TellusMinecraft1201WriteAdapterTest {
    private static final double SCALE = 1.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:mc1201-write", "mc1201-write-v1", "write-projection-v1", "write-noise-v1", SCALE);
    private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
        42L, SCALE, 64, -64, 320, GEOMETRY);
    private static final TellusCoordinateContext CANONICAL = TellusCoordinateContextFactory.fromCubeNetCanonical(
        MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
        new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));
    private static Registry<Biome> BIOMES;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> mapped = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        Holder<Biome> plains = mapped.register(Biomes.PLAINS,
            VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
            Lifecycle.stable());
        BIOMES = mapped.freeze();
    }

    @Test
    void semanticMapperMapsKnownMaterialAndRejectsUnknown() {
        TellusMinecraft1201SemanticMapper mapper = new TellusMinecraft1201SemanticMapper();
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "mapper-test");

        assertEquals(Blocks.STONE.defaultBlockState(), mapper.map(SemanticMaterial.STONE, 80, context));
        assertEquals(Blocks.WATER.defaultBlockState(), mapper.map(SemanticMaterial.WATER, 64, context));
        assertThrows(IllegalArgumentException.class,
            () -> mapper.map(SemanticMaterial.UNKNOWN, 80, context));
    }

    @Test
    void canonicalAdapterGatesUnknownQueuedAndApronBeforeTouchingChunk() {
        TellusMinecraft1201CanonicalColumnAdapter adapter = new TellusMinecraft1201CanonicalColumnAdapter();
        WriteContext canonical = WriteContext.canonical(CANONICAL, GENERATION, "gate-test");

        WriteResult<BlockState> unknown = adapter.write(null, canonical,
            unavailable(CANONICAL, DecisionStatus.UNKNOWN, "unknown"));
        WriteResult<BlockState> queued = adapter.write(null, canonical,
            unavailable(CANONICAL, DecisionStatus.QUEUED, "queued"));
        ImmutableTellusCoordinateContext apron = findApronContext().orElseThrow();
        WriteContext mirror = WriteContext.apronMirror(apron, CANONICAL, GENERATION, "apron-test");
        WriteResult<BlockState> rejectedApron = adapter.write(null, mirror, available(CANONICAL, 80));

        assertEquals(WriteStatus.NO_WRITE_UNKNOWN, unknown.status());
        assertEquals(WriteStatus.NO_WRITE_QUEUED, queued.status());
        assertEquals(WriteStatus.NO_WRITE_INVALID, rejectedApron.status());
        assertTrue(unknown.mappedWrites().isEmpty());
        assertTrue(queued.mappedWrites().isEmpty());
        assertTrue(rejectedApron.mappedWrites().isEmpty());
    }

    @Test
    void canonicalAdapterMapsTopCellAndAcceptsOpaqueInjectedMapper() {
        TellusMinecraft1201CanonicalColumnAdapter defaultAdapter = new TellusMinecraft1201CanonicalColumnAdapter();
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(2, 3), UpgradeData.EMPTY,
            LevelHeightAccessor.create(-64, 320), BIOMES, null);
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "canonical-test");
        WriteResult<BlockState> result = defaultAdapter.write(chunk, context, available(CANONICAL, 80));

        assertEquals(WriteStatus.WRITTEN, result.status());
        assertEquals(1, result.mappedWrites().size());
        assertEquals(Blocks.STONE.defaultBlockState(), result.mappedWrites().get(0));
        assertEquals(Blocks.STONE.defaultBlockState(), chunk.getBlockState(
            new net.minecraft.core.BlockPos(32, 80, 48)));

        AtomicInteger calls = new AtomicInteger();
        SemanticMapper<BlockState> opaque = (material, y, writeContext) -> {
            calls.incrementAndGet();
            return Blocks.DIRT.defaultBlockState();
        };
        TellusMinecraft1201CanonicalColumnAdapter injected =
            new TellusMinecraft1201CanonicalColumnAdapter(new TellusColumnMaterializer(), opaque);
        ProtoChunk opaqueChunk = new ProtoChunk(new ChunkPos(2, 3), UpgradeData.EMPTY,
            LevelHeightAccessor.create(-64, 320), BIOMES, null);
        WriteResult<BlockState> opaqueResult = injected.write(
            opaqueChunk, context, available(CANONICAL, 81));

        assertEquals(WriteStatus.WRITTEN, opaqueResult.status());
        assertEquals(1, calls.get());
        assertEquals(Blocks.DIRT.defaultBlockState(), opaqueChunk.getBlockState(
            new net.minecraft.core.BlockPos(32, 81, 48)));
        assertFalse(opaqueResult.mappedWrites().isEmpty());
    }

    private static ColumnDecision available(TellusCoordinateContext coordinate, int y) {
        return new ColumnDecision(coordinate, DecisionStatus.AVAILABLE, OptionalInt.of(y),
            SurfaceDecision.defaultStone(), WaterDecision.dry(), BiomeDecision.defaultBiome(),
            StrataDecision.stone(-64, y), List.of("fixture"));
    }

    private static ColumnDecision unavailable(
        TellusCoordinateContext coordinate, DecisionStatus status, String diagnostic
    ) {
        return ColumnDecision.unavailable(coordinate, status, diagnostic);
    }

    private static Optional<ImmutableTellusCoordinateContext> findApronContext() {
        CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
        CubeNetApronResolver resolver = new CubeNetApronResolver(layout, 64);
        double edge = layout.faceSize() * 0.5;
        for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
            CubeNetLayout.FacePlacement placement = layout.placement(face);
            for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
                if (layout.arePlanarNeighbors(face, side)) continue;
                CubeNetLayout.FacePoint interior = switch (side) {
                    case WEST -> new CubeNetLayout.FacePoint(face, 0.5, edge);
                    case EAST -> new CubeNetLayout.FacePoint(face, layout.faceSize() - 0.5, edge);
                    case NORTH -> new CubeNetLayout.FacePoint(face, edge, 0.5);
                    case SOUTH -> new CubeNetLayout.FacePoint(face, edge, layout.faceSize() - 0.5);
                };
                CubeNetLayout.PlanarPoint center = layout.toPlanar(interior);
                double outwardX = switch (side) {
                    case WEST -> -placement.uAxisX();
                    case EAST -> placement.uAxisX();
                    case NORTH -> -placement.vAxisX();
                    case SOUTH -> placement.vAxisX();
                };
                double outwardZ = switch (side) {
                    case WEST -> -placement.uAxisZ();
                    case EAST -> placement.uAxisZ();
                    case NORTH -> -placement.vAxisZ();
                    case SOUTH -> placement.vAxisZ();
                };
                CubeNetLayout.PlanarPoint proxy = new CubeNetLayout.PlanarPoint(
                    center.x() + outwardX * 24.0, center.z() + outwardZ * 24.0);
                Optional<ImmutableTellusCoordinateContext> candidate =
                    TellusCoordinateContextFactory.fromCubeNetPlanar(
                        MODEL, layout, resolver, proxy.x(), proxy.z());
                if (candidate.isPresent() && candidate.orElseThrow().owner() instanceof CubeNetApronOwnerView) {
                    return candidate;
                }
            }
        }
        return Optional.empty();
    }
}
