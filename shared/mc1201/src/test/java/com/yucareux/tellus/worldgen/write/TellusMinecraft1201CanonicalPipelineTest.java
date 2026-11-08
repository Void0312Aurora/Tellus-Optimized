package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.worldgen.decision.TellusColumnDecisionEngine;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.feature.FeaturePlan;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.ImmutableTellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvider;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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

/** Opt-in pipeline gate/order contracts; no generator registration is involved. */
class TellusMinecraft1201CanonicalPipelineTest {
    private static final double SCALE = 1.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:pipeline", "pipeline-v1", "pipeline-projection-v1", "pipeline-noise-v1", SCALE);
    private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
        55L, SCALE, 64, -64, 320, GEOMETRY);
    private static final TellusCoordinateContext CANONICAL = TellusCoordinateContextFactory.fromCubeNetCanonical(
        MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
        new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));
    private static final CanonicalColumnPosition POSITION = CanonicalColumnPosition.resolve(
        WriteContext.canonical(CANONICAL, GENERATION, "position-fixture"));
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
    void disabledPipelineIsLegacyUntouchedAndDoesNotCallProvider() {
        AtomicInteger calls = new AtomicInteger();
        TellusSampleProvider provider = provider(request -> {
            calls.incrementAndGet();
            return availableSample(request);
        });
        TellusMinecraft1201CanonicalPipeline pipeline = pipeline(false, provider, List.of());

        WriteResult<BlockState> result = pipeline.write(null, null, null, null, null);

        assertFalse(pipeline.enabled());
        assertEquals(WriteStatus.NO_WRITE_INVALID, result.status());
        assertTrue(result.diagnostics().get(0).contains("disabled"));
        assertEquals(0, calls.get());
    }

    @Test
    void unknownAndQueuedSamplesStopBeforeTargetAndCanonicalOnlyRejectsApron() {
        TellusMinecraft1201CanonicalPipeline unknownPipeline = pipeline(true,
            provider(request -> TellusSample.unknown(request, "unknown")), List.of());
        TellusSampleRequest exact = request(SamplingResolution.exact(SCALE), 1L, CANONICAL);
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "pipeline-gate");
        WriteResult<BlockState> unknown = unknownPipeline.write(null, context, GENERATION, CANONICAL, exact);

        TellusMinecraft1201CanonicalPipeline queuedPipeline = pipeline(true,
            provider(request -> TellusSample.queued(request, "fixture", "r1", "queued")), List.of());
        TellusSampleRequest queuedRequest = request(SamplingResolution.nonBlocking(SCALE), 2L, CANONICAL);
        WriteResult<BlockState> queued = queuedPipeline.write(null, context, GENERATION, CANONICAL, queuedRequest);

        ImmutableTellusCoordinateContext apron = findApronContext().orElseThrow();
        WriteContext mirror = WriteContext.apronMirror(apron, CANONICAL, GENERATION, "pipeline-apron");
        TellusMinecraft1201CanonicalPipeline apronPipeline = pipeline(true,
            provider(request -> availableSample(request)), List.of());
        WriteResult<BlockState> apronResult = apronPipeline.write(null, mirror, GENERATION, apron,
            request(SamplingResolution.exact(SCALE), 3L, apron));

        assertEquals(WriteStatus.NO_WRITE_UNKNOWN, unknown.status());
        assertEquals(WriteStatus.NO_WRITE_QUEUED, queued.status());
        assertEquals(WriteStatus.NO_WRITE_INVALID, apronResult.status());
        assertTrue(unknown.mappedWrites().isEmpty());
        assertTrue(queued.mappedWrites().isEmpty());
        assertTrue(apronResult.mappedWrites().isEmpty());
    }

    @Test
    void canonicalPipelineOrdersProviderDecisionFeatureAndMapperBeforeWriting() {
        List<String> order = new ArrayList<>();
        TellusSampleProvider provider = provider(request -> {
            order.add("sample");
            return availableSample(request);
        });
        TellusFeaturePlanner planner = (generation, coordinate, decision, sample) -> {
            order.add("feature");
            return FeaturePlan.empty("no feature");
        };
        SemanticMapper<BlockState> mapper = (material, y, context) -> {
            order.add("mapper");
            return Blocks.DIRT.defaultBlockState();
        };
        TellusMinecraft1201CanonicalPipeline pipeline = new TellusMinecraft1201CanonicalPipeline(
            true, provider, new TellusColumnDecisionEngine(), planner,
            new TellusColumnMaterializer(), mapper);
        ProtoChunk target = new ProtoChunk(new ChunkPos(Math.floorDiv(POSITION.x(), 16), Math.floorDiv(POSITION.z(), 16)), UpgradeData.EMPTY,
            LevelHeightAccessor.create(-64, 320), BIOMES, null);
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "pipeline-order");

        WriteResult<BlockState> result = pipeline.write(
            target, context, GENERATION, CANONICAL,
            request(SamplingResolution.exact(SCALE), 4L, CANONICAL));

        assertEquals(WriteStatus.WRITTEN, result.status());
        assertEquals(List.of("sample", "feature"), order.subList(0, 2));
        assertEquals(229, order.stream().filter("mapper"::equals).count());
        assertEquals(Blocks.DIRT.defaultBlockState(), target.getBlockState(
            new net.minecraft.core.BlockPos(POSITION.x(), 164, POSITION.z())));
    }

    private static TellusSampleProvider provider(Function<TellusSampleRequest, TellusSample> sampler) {
        return new TellusSampleProvider() {
            @Override
            public String providerId() {
                return "pipeline-fixture";
            }

            @Override
            public TellusSample sample(TellusSampleRequest request) {
                return sampler.apply(request);
            }
        };
    }

    private static TellusMinecraft1201CanonicalPipeline pipeline(
        boolean enabled, TellusSampleProvider provider, List<String> ignored
    ) {
        return new TellusMinecraft1201CanonicalPipeline(
            enabled, provider, new TellusColumnDecisionEngine(), TellusFeaturePlanner.empty(),
            new TellusColumnMaterializer(), new TellusMinecraft1201SemanticMapper());
    }

    private static TellusSampleRequest request(
        SamplingResolution resolution, long seed, TellusCoordinateContext coordinate
    ) {
        return TellusSampleRequest.fromContext(
            coordinate, SamplingPurpose.TERRAIN_COLUMN, resolution, GEOMETRY, seed);
    }

    private static TellusSample availableSample(TellusSampleRequest request) {
        return new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 100.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30),
            TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("pipeline-fixture", "r1", true, "available"));
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
