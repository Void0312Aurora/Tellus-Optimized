package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.decision.*;
import com.yucareux.tellus.worldgen.feature.TellusFeaturePlanner;
import com.yucareux.tellus.worldgen.global.*;
import com.yucareux.tellus.worldgen.sample.*;
import com.yucareux.tellus.worldgen.write.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Actual spherical base stage: native source grid -> existing sample/kernel -> shared chunk writer. */
public final class TellusNativeSphericalTerrainStage {
    private TellusNativeSphericalTerrainStage() { }

    public static void fill(ChunkAccess target, SphereWorldModel model, CubeNetLayout cubeLayout,
                            SphereChunkSampler.TerrainGrid grid, TellusGenerationContext generation,
                            TellusSphericalDecisionKernel.Settings settings,
                            Function<SphereColumnContext, CubeNetSurfacePolicy.Decision> surfaceResolver,
                            SemanticMapper<BlockState> mapper, TellusTargetWriteGuard.Lease lease) {
        Objects.requireNonNull(lease, "native stage lease").requireOwned(target);
        if (generation.minY() != target.getMinBuildHeight() || generation.maxYExclusive() != target.getMaxBuildHeight()
            || grid.columns().size() != 256) throw new IllegalArgumentException("native terrain stage target/profile differs");
        for (var section : target.getSections()) if (!section.hasOnlyAir())
            throw new IllegalArgumentException("unified spherical fill requires the same fresh target as native fill");
        Map<SphereWorldModel.SphereBlockAddress, SphereChunkSampler.Column> columns = new HashMap<>();
        List<TellusNativeChunkBatch.Request> requests = new ArrayList<>(256);
        for (var column : grid.columns()) {
            if (columns.put(column.address(), column) != null) throw new IllegalArgumentException("duplicate native terrain owner");
            TellusCoordinateContext coordinate = cubeLayout == null
                ? TellusCoordinateContextFactory.fromSphericalBlock(model, column.address())
                : TellusCoordinateContextFactory.fromCubeNetCanonical(model, cubeLayout,
                    new CubeNetLayout.FacePoint(CubeNetLayout.Face.of(column.address().face()),
                        column.address().blockX() + .5, column.address().blockZ() + .5));
            var request = TellusSampleRequest.fromContext(coordinate, SamplingPurpose.TERRAIN_COLUMN,
                SamplingResolution.exact(generation.worldScale()), generation.geometry(), generation.worldSeed());
            requests.add(new TellusNativeChunkBatch.Request(WriteContext.canonical(coordinate, generation, "native-base-stage"),
                generation, coordinate, request));
        }
        // The provider reads the exact already-adjusted native grid. No fresh GIS/biome source query is added.
        TellusSampleProvider provider = cubeLayout == null
            ? TellusSphericalSampleProvider.forSphereFaceNativeColumns(request -> columns.get(
                ((SphericalOwnerView)request.coordinate().owner()).block()))
            : TellusSphericalSampleProvider.forCubeNetNativeSnapshots(request -> {
                var column = columns.get(((CubeNetCanonicalOwnerView)request.coordinate().owner()).block());
                int y = TellusSphericalColumnRules.height(column.data().elevationMeters(), generation.worldScale(),
                    generation.seaLevel(), settings.terrestrialHeightScale(), settings.oceanicHeightScale());
                int water = settings.enableWater() && column.data().waterAvailable()
                    ? TellusSphericalColumnRules.height(column.data().waterSurfaceMeters(), generation.worldScale(),
                        generation.seaLevel(), settings.terrestrialHeightScale(), settings.oceanicHeightScale()) : y;
                if (y <= generation.minY() || y >= generation.maxYExclusive() || water >= generation.maxYExclusive())
                    throw new IllegalArgumentException("native spherical column exceeds vertical range");
                var surface = surfaceResolver == null
                    ? CubeNetSurfacePolicy.resolve(column.point(), column.data(), generation.worldScale(), generation.worldSeed())
                    : surfaceResolver.apply(column.context());
                return TellusSphericalSurfaceSnapshot.resolvedNativeColumn(column, surface);
            });
        var kernel = new TellusSphericalDecisionKernel(settings, (g, coordinate, sample, y) -> {
            var p = CanonicalColumnPosition.resolve(WriteContext.canonical(coordinate, g, "native-biome-read"), model);
            return target.getNoiseBiome(QuartPos.fromBlock(p.x()), QuartPos.fromBlock(y), QuartPos.fromBlock(p.z()))
                .unwrapKey().map(key -> new BiomeDecision(key.location().toString(), true)).orElseGet(BiomeDecision::unknown);
        });
        var pipeline = TellusColumnPipeline.compose(provider, TellusColumnDecisionEngine.withSphericalProductionRules(kernel),
            TellusFeaturePlanner.empty());
        var result = new TellusNativeChunkBatch(new TellusColumnMaterializer(), mapper, model).writeOwned(true, target, requests,
            request -> pipeline.plan(request.generation(), request.coordinate(), request.request()), lease);
        if (result.status() != WriteStatus.WRITTEN) throw new IllegalStateException("native spherical stage stopped: " + result.diagnostics());
        Heightmap.primeHeightmaps(target, EnumSet.allOf(Heightmap.Types.class));
    }
}
