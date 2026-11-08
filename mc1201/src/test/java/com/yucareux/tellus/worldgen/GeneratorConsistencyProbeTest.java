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
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Temporary cross-face, water-column and materialization consistency probe. */
class GeneratorConsistencyProbeTest {
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
    void cubeNetAndSphereFaceMatchAcrossAllFacesLocationsAndWaterColumns() {
        double scale = 1.0;
        int seaLevel = 64;
        int minY = -1024;
        int height = 3056;
        SphereWorldModel model = SphereWorldModel.forWorldScale(scale);
        SphereChunkSampler.PointSampler sampler = (point, resolution) -> {
            double lat = Math.toRadians(point.latitude());
            double lon = Math.toRadians(point.longitude());
            double elevation = 420.0 * Math.sin(lat)
                + 260.0 * Math.cos(lon)
                + 90.0 * Math.sin(lat + lon);
            boolean wet = elevation < 0.0;
            return new SphereChunkSampler.SurfaceData(
                elevation,
                wet ? 80 : 30,
                wet ? 0.0 : Double.NaN
            );
        };
        CubeNetChunkGenerator cube = new CubeNetChunkGenerator(
            new FixedBiomeSource(plains), scale, seaLevel, minY, height, sampler, 0);
        CubeNetLayout layout = cube.adapter().layout();
        LevelHeightAccessor accessor = LevelHeightAccessor.create(minY, height);
        long chunks = model.chunksPerFace();
        long half = chunks / 2L;
        long[] samples = {half - 32L, half, half + 32L};
        int columns = 0;
        int mismatchColumns = 0;
        long mismatchBlocks = 0L;
        for (int faceIndex = 0; faceIndex < SphereWorldModel.FACE_COUNT; faceIndex++) {
            SphereFaceChunkGenerator face = new SphereFaceChunkGenerator(
                new FixedBiomeSource(plains), faceIndex, scale, seaLevel, minY, height, sampler);
            CubeNetLayout.Face faceName = CubeNetLayout.Face.of(faceIndex);
            long halfFaceBlocks = half * 16L;
            for (long chunkX : samples) {
                for (long chunkZ : samples) {
                    SphereWorldModel.SphereChunkAddress address =
                        new SphereWorldModel.SphereChunkAddress(faceIndex, chunkX, chunkZ);
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            long canonicalX = address.blockX() + localX;
                            long canonicalZ = address.blockZ() + localZ;
                            CubeNetLayout.PlanarPoint planar = layout.toPlanar(new CubeNetLayout.FacePoint(
                                faceName, canonicalX + 0.5, canonicalZ + 0.5));
                            int cubeX = (int)Math.floor(planar.x());
                            int cubeZ = (int)Math.floor(planar.z());
                            int faceX = Math.toIntExact(canonicalX - halfFaceBlocks);
                            int faceZ = Math.toIntExact(canonicalZ - halfFaceBlocks);
                            NoiseColumn cubeColumn = cube.getBaseColumn(cubeX, cubeZ, accessor, null);
                            NoiseColumn faceColumn = face.getBaseColumn(faceX, faceZ, accessor, null);
                            int columnMismatches = 0;
                            for (int y = minY; y < minY + height; y++) {
                                int cubeState = Block.getId(cubeColumn.getBlock(y));
                                int faceState = Block.getId(faceColumn.getBlock(y));
                                if (cubeState != faceState) {
                                    columnMismatches++;
                                    mismatchBlocks++;
                                }
                            }
                            if (columnMismatches != 0) mismatchColumns++;
                            columns++;
                        }
                    }
                }
            }
        }
        System.out.println("CONSISTENCY_PROBE faces=6 locations=9 columns=" + columns
            + " mismatchColumns=" + mismatchColumns + " mismatchBlocks=" + mismatchBlocks);
        assertEquals(6 * 9 * 256, columns);
        assertEquals(0, mismatchColumns);
        assertEquals(0L, mismatchBlocks);
    }
}
