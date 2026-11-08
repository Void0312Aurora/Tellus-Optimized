package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Temporary read-only parity probe; removed after execution. */
class GeneratorParityProbeTest {
    private static Holder<Biome> plains;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> mapped = new MappedRegistry<>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        plains = mapped.register(Biomes.PLAINS,
            VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
            com.mojang.serialization.Lifecycle.stable());
        mapped.freeze();
    }

    @Test
    void cubeNetCanonicalColumnsMatchSphereFaceForTheSamePhysicalAddress() {
        double scale = 1000.0;
        int seaLevel = 64;
        int minY = -64;
        int height = 384;
        SphereWorldModel model = SphereWorldModel.forWorldScale(scale);
        SphereChunkSampler.PointSampler sampler = (point, resolution) -> {
            double lat = Math.toRadians(point.latitude());
            double lon = Math.toRadians(point.longitude());
            double elevation = 18.0 + 7.0 * Math.sin(lat) + 5.0 * Math.cos(lon) + 2.0 * Math.sin(lat + lon);
            return new SphereChunkSampler.SurfaceData(elevation, 30);
        };
        CubeNetChunkGenerator cube = new CubeNetChunkGenerator(
            new FixedBiomeSource(plains), scale, seaLevel, minY, height, sampler, 0);
        SphereFaceChunkGenerator face = new SphereFaceChunkGenerator(
            new FixedBiomeSource(plains), 0, scale, seaLevel, minY, height, sampler);
        long chunkIndex = model.chunksPerFace() / 2L;
        SphereWorldModel.SphereChunkAddress address = new SphereWorldModel.SphereChunkAddress(0, chunkIndex, chunkIndex);
        CubeNetLayout layout = cube.adapter().layout();
        LevelHeightAccessor accessor = LevelHeightAccessor.create(minY, height);
        int mismatches = 0;
        int maxDifference = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                long canonicalX = address.blockX() + localX;
                long canonicalZ = address.blockZ() + localZ;
                CubeNetLayout.PlanarPoint planar = layout.toPlanar(new CubeNetLayout.FacePoint(
                    CubeNetLayout.Face.POS_X, canonicalX + 0.5, canonicalZ + 0.5));
                int cubeX = (int)Math.floor(planar.x());
                int cubeZ = (int)Math.floor(planar.z());
                long halfFaceBlocks = (model.chunksPerFace() / 2L) * 16L;
                int faceX = Math.toIntExact(canonicalX - halfFaceBlocks);
                int faceZ = Math.toIntExact(canonicalZ - halfFaceBlocks);
                int cubeHeight = cube.getBaseHeight(cubeX, cubeZ,
                    Heightmap.Types.WORLD_SURFACE_WG, accessor, null);
                int faceHeight = face.getBaseHeight(faceX, faceZ,
                    Heightmap.Types.WORLD_SURFACE_WG, accessor, null);
                int difference = Math.abs(cubeHeight - faceHeight);
                if (difference != 0) mismatches++;
                maxDifference = Math.max(maxDifference, difference);
            }
        }
        System.out.println("PARITY_PROBE canonicalColumns=256 mismatches=" + mismatches + " maxHeightDifference=" + maxDifference);
        assertEquals(0, mismatches);
        assertEquals(0, maxDifference);
    }
}
