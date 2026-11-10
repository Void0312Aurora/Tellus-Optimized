package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.cache.Cache;
import com.yucareux.tellus.compat.MinecraftRelease;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.test.MinecraftTestRegistries;
import com.yucareux.tellus.world.data.biome.BiomeClassification;
import com.yucareux.tellus.world.data.biome.BiomeClassificationProviders;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.resolve.TellusResolveSource;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Travel environment decisions, with injected terrain/climate and bundled offline regional data. */
@ResourceLock("tellus-worldgen-sources")
class CubeNetLandEnvironmentTest {
    private static final double SCALE = 30.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final CubeNetLayout LAYOUT = CubeNetLayout.standard(MODEL.config().faceBlocks());
    @TempDir static Path directory;
    private static HolderLookup.RegistryLookup<Biome> biomes;
    private static String oldGame, oldConfig;

    @BeforeAll static void bootstrap() {
        oldGame = System.getProperty("tellus.gameDir"); oldConfig = System.getProperty("tellus.configDir");
        System.setProperty("tellus.gameDir", directory.toString());
        System.setProperty("tellus.configDir", directory.resolve("config").toString());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        biomes = MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME);
    }

    @AfterAll static void restoreProperties() {
        if (oldGame == null) System.clearProperty("tellus.gameDir"); else System.setProperty("tellus.gameDir", oldGame);
        if (oldConfig == null) System.clearProperty("tellus.configDir"); else System.setProperty("tellus.configDir", oldConfig);
    }

    private static CubeNetBiomeSource source(int cover, String climate, SphereChunkSampler.PointSampler terrain) {
        return new CubeNetBiomeSource(biomes, SCALE, 0, SphereSingularityPolicy.DEFAULT_CONFIG,
            new FixedBiomeSource(biomes.getOrThrow(Biomes.PLAINS)), terrain,
            (point, scale, water) -> new CubeNetBiomeSource.ClimateData(climate, null),
            (point, scale) -> CubeNetBiomeSource.WaterData.dry());
    }

    private static Holder<Biome> query(CubeNetBiomeSource source, SphereWorldModel.GeoPoint point) {
        var face = MODEL.continuousForGeo(point);
        return query(source, new CubeNetLayout.FacePoint(CubeNetLayout.Face.of(face.face()), face.blockX(), face.blockZ()));
    }

    private static Holder<Biome> query(CubeNetBiomeSource source, CubeNetLayout.FacePoint point) {
        var planar = LAYOUT.toPlanar(point);
        return source.getNoiseBiome(QuartPos.fromBlock((int)Math.floor(planar.x())), QuartPos.fromBlock(64),
            QuartPos.fromBlock((int)Math.floor(planar.z())), null);
    }

    private static ResourceKey<Biome> expected(int cover, String climate, SphereWorldModel.GeoPoint point) {
        int x = (int)Math.floor(EarthProjection.longitudeToBlockX(point.longitude(), SCALE));
        int z = (int)Math.floor(EarthProjection.latToBlockZ(point.latitude(), SCALE));
        ResourceKey<Biome> key = "1.20.1".equals(MinecraftRelease.VERSION) ? null
            : BiomeClassificationProviders.findBiomeKey(cover, climate, x, z, SCALE);
        if (key == null) key = BiomeClassification.findBiomeKey(cover, climate);
        return key == null ? BiomeClassification.findFallbackKey(cover) : key;
    }

    @Test void historicalBareBWhWasClassifiedAtAntarcticRockInsteadOfArabianDesert() {
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            // The old spherical noise coordinates at (24N,55E), incorrectly consumed as Earth blocks.
            var regional = TellusResolveSource.shared();
            assertEquals("Rock and Ice", regional.sampleEcoregion(539037, 785345, SCALE).name());
            int earthX = (int)Math.floor(EarthProjection.longitudeToBlockX(55, SCALE));
            int earthZ = (int)Math.floor(EarthProjection.latToBlockZ(24, SCALE));
            assertEquals("Arabian sand desert", regional.sampleEcoregion(earthX, earthZ, SCALE).name());
            assertEquals(Biomes.STONY_PEAKS, BiomeClassificationProviders.findBiomeKey(60, "BWh", 539037, 785345, SCALE));
            assertEquals(Biomes.DESERT, BiomeClassificationProviders.findBiomeKey(60, "BWh", earthX, earthZ, SCALE));
            var source = source(60, "BWh", (point, resolution) -> new SphereChunkSampler.SurfaceData(10, 60));
            assertTrue(query(source, new SphereWorldModel.GeoPoint(24, 55, 0)).is(Biomes.DESERT));
        }
    }

    @Test void allSixFacesKeepNativeReleaseClassificationAcrossHotColdSteppeTemperateAndAlpineClimate() {
        int cases = 0;
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            for (String climate : new String[]{"BWh", "BWk", "BSh", "BSk", "Cfb", "ET", null}) {
                AtomicReference<SphereWorldModel.GeoPoint> sampled = new AtomicReference<>();
                var source = source(60, climate, (point, resolution) -> {
                    sampled.compareAndSet(null, point); return new SphereChunkSampler.SurfaceData(10, 60);
                });
                for (var face : CubeNetLayout.Face.values()) {
                    sampled.set(null);
                    var result = query(source, new CubeNetLayout.FacePoint(face, LAYOUT.faceSize() * 0.32, LAYOUT.faceSize() * 0.41));
                    assertEquals(expected(60, climate, sampled.get()), result.unwrapKey().orElseThrow(), face + "/" + climate);
                    cases++;
                }
            }
        }
        assertEquals(42, cases);
    }

    @Test void bareDoesNotMeanDesertInTemperateOrAlpineRockyTerrain() {
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            for (String climate : new String[]{"Cfb", "ET", null}) {
                var source = source(60, climate, (point, resolution) -> new SphereChunkSampler.SurfaceData(1800, 60));
                var result = query(source, new SphereWorldModel.GeoPoint(46, 12, 0));
                assertFalse(result.is(Biomes.DESERT)); assertFalse(result.is(Biomes.BADLANDS));
                assertTrue(result.is(Biomes.STONY_PEAKS) || result.is(Biomes.WINDSWEPT_GRAVELLY_HILLS));
            }
        }
    }

    @Test void hotDesertColdDesertAndSteppeKeepTheirDistinctNativeMappings() {
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            for (String climate : new String[]{"BWh", "BWk", "BSh", "BSk"}) {
                var source = source(60, climate, (point, resolution) -> new SphereChunkSampler.SurfaceData(10, 60));
                assertTrue(query(source, new SphereWorldModel.GeoPoint(24, 55, 0))
                    .is(climate.equals("BWh") ? Biomes.DESERT : Biomes.BADLANDS), climate);
            }
        }
    }

    @Test void regionalClassificationFollowsNativeReleaseRatherThanChangingEarthToFitTheProbe() {
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            var source = source(10, "Cfb", (point, resolution) -> new SphereChunkSampler.SurfaceData(10, 10));
             var nativeKey = "1.20.1".equals(MinecraftRelease.VERSION)
                 ? Biomes.OLD_GROWTH_BIRCH_FOREST : Biomes.OLD_GROWTH_SPRUCE_TAIGA;
             assertEquals(nativeKey, query(source, new SphereWorldModel.GeoPoint(46, 12, 0)).unwrapKey().orElseThrow());
        }
    }

    @Test void injectedClimateSurvivesConflictingGlobalCoherentAridRaster() throws Exception {
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            TellusKoppenSource global = TellusWorldgenSources.koppen();
            Field rasterField = TellusKoppenSource.class.getDeclaredField("raster"); rasterField.setAccessible(true);
            Object previous = rasterField.get(global);
            try {
                rasterField.set(global, constantRaster(5)); // BWk everywhere, unlike injected BWh.
                double x = EarthProjection.longitudeToBlockX(55, SCALE), z = EarthProjection.latToBlockZ(24, SCALE);
                assertEquals("BWk", global.sampleCoherentCode(x, z, SCALE), "The conflicting source must really be available");
                var source = source(60, "BWh", (point, resolution) -> new SphereChunkSampler.SurfaceData(10, 60));
                assertTrue(query(source, new SphereWorldModel.GeoPoint(24, 55, 0)).is(Biomes.DESERT),
                    "A global coherent climate must not replace the selected ClimateSampler");
            } finally { rasterField.set(global, previous); }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object constantRaster(int climateClass) throws Exception {
        Class<?> type = Class.forName(TellusKoppenSource.class.getName() + "$GeoTiffRaster");
        var constructor = type.getDeclaredConstructor(Path.class, FileChannel.class, int.class, int.class,
            int.class, int.class, int.class, long[].class, int[].class, double.class, double.class, double.class, double.class);
        constructor.setAccessible(true);
        Object raster = constructor.newInstance(null, null, 360, 180, 360, 180, 8,
            new long[]{0}, new int[]{0}, 1.0, 1.0, -180.0, 90.0);
        Field cacheField = type.getDeclaredField("tileCache"); cacheField.setAccessible(true);
        byte[] tile = new byte[360 * 180]; Arrays.fill(tile, (byte)climateClass);
        ((Cache<Integer, byte[]>)cacheField.get(raster)).put(0, tile);
        return raster;
    }

    @Test void canyonReliefSamplesNearbyGeographyOnEveryFaceAndRetainsBadlandsPromotion() {
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            for (var face : CubeNetLayout.Face.values()) for (int cover : new int[]{20, 30, 60, 100}) {
                List<SphereWorldModel.GeoPoint> reads = new ArrayList<>();
                var source = source(cover, "BWh", (point, resolution) -> {
                    reads.add(point);
                    return new SphereChunkSampler.SurfaceData(reads.size() <= 2 ? 10 : 300, cover);
                });
                assertTrue(query(source, new CubeNetLayout.FacePoint(face, LAYOUT.faceSize() * 0.32, LAYOUT.faceSize() * 0.41)).is(Biomes.BADLANDS));
                assertEquals(6, reads.size()); // surface, center, four geodetic neighbours
                assertNearby(reads);
            }
        }
    }

    @Test void snowSamplesLocalGeographyAndUnavailableElevationStaysNaN() throws Exception {
        try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
            for (var face : CubeNetLayout.Face.values()) {
                List<SphereWorldModel.GeoPoint> reads = new ArrayList<>();
                var source = source(70, "ET", (point, resolution) -> {
                    reads.add(point); return new SphereChunkSampler.SurfaceData(2000, 70);
                });
                assertTrue(query(source, new CubeNetLayout.FacePoint(face, LAYOUT.faceSize() * 0.32, LAYOUT.faceSize() * 0.41)).is(Biomes.FROZEN_PEAKS));
                assertEquals(6, reads.size()); assertNearby(reads);
            }
            var unavailable = source(60, "BWh", (point, resolution) -> new SphereChunkSampler.SurfaceData(Double.NaN, 60));
            var sample = CubeNetBiomeSource.class.getDeclaredMethod("sampleElevationAtEarth", double.class, double.class);
            sample.setAccessible(true);
             assertTrue(Double.isNaN((double)sample.invoke(unavailable, 0.0, 0.0)));
             assertTrue(Double.isNaN((double)sample.invoke(unavailable, Double.NaN, 0.0)));
             List<SphereWorldModel.GeoPoint> reads = new ArrayList<>();
             var wrapping = source(60, "Cfb", (point, resolution) -> {
                 reads.add(point); return new SphereChunkSampler.SurfaceData(123, 60);
             });
             for (double longitude : new double[]{180.01, -180.01}) {
                 assertEquals(123.0, (double)sample.invoke(wrapping,
                     EarthProjection.longitudeToBlockX(longitude, SCALE), EarthProjection.latToBlockZ(24, SCALE)));
                 var actual = reads.get(reads.size() - 1);
                 assertTrue(actual.longitude() >= -180.0 && actual.longitude() < 180.0);
                 assertEquals(longitude > 180.0 ? -179.99 : 179.99, actual.longitude(), 1.0E-9);
             }
        }
    }

    private static void assertNearby(List<SphereWorldModel.GeoPoint> reads) {
        var center = reads.get(0);
        for (var point : reads) {
            assertTrue(Math.abs(point.latitude() - center.latitude()) < 0.03, reads.toString());
            assertTrue(Math.abs(point.longitude() - center.longitude()) < 0.03, reads.toString());
        }
    }
}
